package com.crispyland.rag;

import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.memory.Message;
import com.crispyland.agent.usage.TokenUsage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Day 24 evaluator: runs 10 questions in two modes and checks citation quality.
 * <ul>
 *   <li><b>standard</b> — normal RAG with sim+rerank, structured output prompt</li>
 *   <li><b>anti-hallucination</b> — same but with a higher similarity threshold (0.45)
 *       so weak matches are dropped and the model must say "I don't know"</li>
 * </ul>
 * Scores each answer on: correctness, has_sources, has_quotes, quotes_verified.
 */
@Service
@ConditionalOnProperty(name = "rag.evaluation.enabled", havingValue = "true")
public class CitationEvaluator {

    private static final Logger log = LoggerFactory.getLogger(CitationEvaluator.class);

    private final RagSearcher searcher;
    private final LlmClient llm;
    private final RagProperties props;

    public CitationEvaluator(RagSearcher searcher, LlmClient llm, RagProperties props) {
        this.searcher = searcher;
        this.llm      = llm;
        this.props    = props;
    }

    public record Question(int number, String text, String expectedAnswer,
                           String expectedSources, String category) {}

    public record QuestionResult(
            int number, String mode, String question,
            String effectiveQuery,
            List<String> finalSources,
            boolean expectedSourceFound,
            String rawAnswer,
            RagAnswer parsedAnswer,
            String answerScore,
            String judgeReason,
            boolean hasSources,
            boolean hasQuotes,
            int quotesVerified,
            int quotesRejected,
            int quotesTotal,
            TokenUsage ragUsage,
            long ragLatencyMs,
            TokenUsage answerUsage,
            long answerLatencyMs,
            boolean rerankFellBack) {}

    private static final List<Question> QUESTIONS = List.of(
        new Question(1, "Where does the agent store long-term memory?",
                "In a JsonFileLongTermStore that persists to a JSON file on disk, keyed by visitor",
                "JsonFileLongTermStore,AgentConfiguration", "project-specific"),
        new Question(2, "How does the store work?",
                "The question is too vague — multiple stores exist. The model should ask which store the user means.",
                "", "unclear"),
        new Question(3, "I mean the conversation store — how does it persist data between restarts?",
                "JsonFileConversationStore writes conversations as JSON to a file and reloads on startup",
                "JsonFileConversationStore", "clarification"),
        new Question(4, "How does StructuredChunker decide where to split a file?",
                "Uses regex patterns based on file type to find split points like method signatures or headings",
                "StructuredChunker", "project-specific"),
        new Question(5, "Which Google OAuth scopes does the MCP server request?",
                "calendar.readonly and tasks.readonly, both read-only",
                "GoogleAuth", "project-specific"),
        new Question(6, "How are conversations persisted between restarts?",
                "JsonFileConversationStore writes them as JSON to a file and reloads on startup",
                "JsonFileConversationStore", "project-specific"),
        new Question(7, "What MCP servers does the agent connect to?",
                "Calendar, weather, and Frankfurter — configured as SSE and streamable-HTTP connections",
                "application.yml,McpCatalogue", "project-specific"),
        new Question(8, "What is the maximum number of messages kept in conversation history?",
                "Controlled by the maxMessages setting in agent memory configuration",
                "AgentProperties,AgentConfiguration", "project-specific"),
        new Question(9, "What is cosine similarity, and why is it used for embeddings?",
                "Measures angle between vectors, comparing direction rather than magnitude",
                "", "general-knowledge"),
        new Question(10, "Which payment provider does the agent use for subscriptions?",
                "No payment/subscription module exists in the codebase",
                "", "honesty-test")
    );

    public record EvalResults(List<QuestionResult> results, List<Question> questions,
                              List<String> modeNames) {}

    /**
     * Runs all questions in both modes and returns structured results.
     */
    public EvalResults evaluateAll(long delayMs) {
        Map<String, RagConfig> modes = new LinkedHashMap<>();
        // Standard: sim+rerank with normal threshold
        modes.put("standard",           new RagConfig(10, 3, 0.30f, true, 5, false));
        // Anti-hallucination: higher threshold — weak matches are dropped
        modes.put("anti-hallucination", new RagConfig(10, 3, 0.45f, true, 7, false));

        List<QuestionResult> allResults = new ArrayList<>();

        for (Map.Entry<String, RagConfig> mode : modes.entrySet()) {
            log.info("Day24 evaluation mode: {}", mode.getKey());
            List<String> conversationContext = new ArrayList<>();
            for (Question q : QUESTIONS) {
                try {
                    boolean needsContext = "follow-up".equals(q.category())
                            || "clarification".equals(q.category());
                    List<String> context = needsContext
                            ? List.copyOf(conversationContext) : List.of();
                    QuestionResult r = runQuestion(q, mode.getKey(), mode.getValue(), context);
                    allResults.add(r);
                    conversationContext.add("User: " + q.text());
                    conversationContext.add("Assistant: " + r.parsedAnswer().answer());
                    log.info("  Q{} [{}]: score={}, sources={}, quotes={}/{}",
                            q.number(), mode.getKey(), r.answerScore(),
                            r.hasSources(), r.quotesVerified(), r.quotesTotal());
                    if (delayMs > 0) Thread.sleep(delayMs);
                } catch (Exception e) {
                    log.error("  Q{} [{}] failed: {}", q.number(), mode.getKey(), e.getMessage());
                    allResults.add(errorResult(q, mode.getKey(), e));
                    conversationContext.add("User: " + q.text());
                    conversationContext.add("Assistant: ERROR");
                }
            }
        }

        return new EvalResults(allResults, QUESTIONS, new ArrayList<>(modes.keySet()));
    }

    private static final String STRUCTURED_SYSTEM_PROMPT =
            "You are a concise, helpful assistant. When answering, always cite your sources " +
            "and include verbatim quotes from the provided context. " +
            "If the context does not contain enough information, say so honestly.";

    private static final String FORMAT_INSTRUCTION = """

            IMPORTANT: Structure your response with these EXACT headings:

            ### Answer
            Your concise answer. Use citation markers like [1], [2] to reference sources.

            ### Sources
            List each source you used, one per line:
            - FileName.java | section name (chunk-id)

            ### Quotes
            For each key claim, include a verbatim quote from the context:
            > "exact words from the context" — FileName.java

            Rules:
            - Only cite sources from the provided context. Do not invent sources.
            - Only quote text that appears word-for-word in the context. Do not paraphrase.
            - If the context does not contain enough information, say so in the Answer and write (none) for Sources and Quotes.
            - If the question is ambiguous or too vague (e.g. "the store" could refer to multiple things), \
            ask the user to clarify what they mean rather than guessing. List what the term could refer to if the context shows multiple matches.
            - If the question is general knowledge (not specific to the codebase), answer from your own knowledge and write (none) for Sources and Quotes.
            """;

    private QuestionResult runQuestion(Question q, String modeName, RagConfig config,
                                       List<String> recentContext) {
        RagSearchResult ragResult = searcher.search(q.text(), recentContext, config);

        String effectiveQuery = ragResult.effectiveQuery();
        List<String> finalSources = ragResult.finalChunks().stream()
                .map(sc -> sc.chunk().sourceFile())
                .toList();

        boolean expectedFound = q.expectedSources().isBlank() ||
                finalSources.stream().anyMatch(s -> containsExpectedSource(s, q.expectedSources()));

        TokenUsage ragUsage = TokenUsage.NONE;
        long ragLatency = 0;
        boolean fellBack = false;
        if (ragResult.debug() != null) {
            ragLatency = ragResult.debug().totalLatencyMs();
            if (ragResult.debug().queryRewrite() != null) {
                ragUsage = ragUsage.plus(ragResult.debug().queryRewrite().usage());
            }
            if (ragResult.debug().rerankerResult() != null) {
                ragUsage = ragUsage.plus(ragResult.debug().rerankerResult().usage());
                fellBack = ragResult.debug().rerankFellBack();
            }
        }

        // Build prompt with structured format instruction
        StringBuilder prompt = new StringBuilder();
        if (!ragResult.isEmpty()) {
            prompt.append("Relevant context from the codebase:\n\n");
            for (int i = 0; i < ragResult.finalChunks().size(); i++) {
                ScoredChunk sc = ragResult.finalChunks().get(i);
                Chunk c = sc.chunk();
                prompt.append("--- [").append(i + 1).append("] ")
                      .append(c.sourceFile()).append(" | ").append(c.section())
                      .append(" (").append(c.chunkId()).append(") ---\n");
                prompt.append(c.text()).append("\n\n");
            }
            prompt.append("---\n\n");
        } else {
            prompt.append("No relevant context was found in the codebase for this question.\n\n");
        }
        prompt.append("Question: ").append(q.text());
        prompt.append(FORMAT_INSTRUCTION);

        long answerStart = System.nanoTime();
        ChatRequest req = new ChatRequest(
                props.rerank().model(),
                List.of(
                        Message.system(STRUCTURED_SYSTEM_PROMPT),
                        Message.user(prompt.toString())
                ),
                0.0, 800, "", List.of(), ""
        );
        ChatResponse resp = llm.complete(req);
        long answerLatency = (System.nanoTime() - answerStart) / 1_000_000;

        String rawAnswer = resp.content().strip();
        RagAnswer parsed = RagAnswer.parse(rawAnswer, ragResult.finalChunks());
        JudgeVerdict verdict = judgeAnswer(q, parsed);

        int verified = (int) parsed.quotes().stream()
                .filter(qo -> qo.status() == RagAnswer.QuoteStatus.VERIFIED).count();
        int rejected = (int) parsed.quotes().stream()
                .filter(qo -> qo.status() == RagAnswer.QuoteStatus.REJECTED).count();

        return new QuestionResult(q.number(), modeName, q.text(), effectiveQuery,
                finalSources, expectedFound, rawAnswer, parsed,
                verdict.score, verdict.reason,
                parsed.hasSources(), parsed.hasQuotes(),
                verified, rejected, parsed.quotes().size(),
                ragUsage, ragLatency, resp.usage(), answerLatency, fellBack);
    }

    private record JudgeVerdict(String score, String reason) {}

    /**
     * Keyword-based scorer adapted for structured answers.
     * Checks the parsed answer text (not the raw response with headings).
     */
    private JudgeVerdict judgeAnswer(Question q, RagAnswer parsed) {
        String lower = parsed.answer().toLowerCase()
                .replace('\u2019', '\'').replace('\u2018', '\'');

        boolean declines = parsed.insufficientContext() ||
                lower.contains("not contain") || lower.contains("not cover") ||
                lower.contains("don't know") || lower.contains("no information") ||
                lower.contains("not found") || lower.contains("not available");

        return switch (q.number()) {
            case 1 -> {
                boolean json = lower.contains("json") || lower.contains("file");
                boolean longTerm = lower.contains("longterm") || lower.contains("long-term") ||
                        lower.contains("long term") || lower.contains("longtermstore");
                if (json && longTerm) yield v("correct", "Mentions JSON file storage for long-term memory");
                if (longTerm) yield v("partial", "Mentions long-term store but not JSON persistence");
                yield v("wrong", "Does not describe JSON-file-based long-term memory storage");
            }
            case 2 -> {
                // Q2: "How does the store work?" — deliberately vague.
                // Correct: model asks for clarification or says the question is ambiguous.
                // Wrong: model picks one store and answers confidently without asking.
                boolean asksClarification = lower.contains("which store") || lower.contains("could you clarify") ||
                        lower.contains("could you specify") || lower.contains("do you mean") ||
                        lower.contains("please clarify") || lower.contains("please specify") ||
                        lower.contains("be more specific") || lower.contains("more specific") ||
                        lower.contains("several store") || lower.contains("multiple store") ||
                        lower.contains("different store") || lower.contains("various store") ||
                        lower.contains("which one") || lower.contains("ambiguous") ||
                        lower.contains("not clear which") || lower.contains("rephrase");
                boolean insufficient = parsed.insufficientContext();
                if (asksClarification)
                    yield v("correct", "Asks for clarification — recognizes the question is ambiguous");
                if (insufficient)
                    yield v("correct", "Declines due to insufficient context — appropriate for a vague question");
                yield v("wrong", "Answers confidently without asking which store is meant");
            }
            case 3 -> {
                // Q3: Clarification — "I mean the conversation store"
                // Correct: describes JsonFileConversationStore / JSON file persistence
                boolean json = lower.contains("json");
                boolean file = lower.contains("file") || lower.contains("disk") || lower.contains("persist");
                boolean store = lower.contains("conversationstore") || lower.contains("conversation store");
                if ((json && file) || store)
                    yield v("correct", "Describes JSON file persistence for conversations");
                if (json || file) yield v("partial", "Mentions persistence but incomplete");
                yield v("wrong", "Does not describe conversation persistence mechanism");
            }
            case 4 -> {
                boolean regex = lower.contains("regex") || lower.contains("pattern") ||
                        lower.contains("regular expression") || lower.contains("split");
                boolean fileType = lower.contains("file type") || lower.contains("filetype") ||
                        lower.contains("java") || lower.contains("markdown") || lower.contains("yml");
                if (regex && fileType) yield v("correct", "Describes regex-based splitting by file type");
                if (regex) yield v("partial", "Mentions pattern splitting but not file-type awareness");
                yield v("wrong", "Does not describe regex-based file-type-aware splitting");
            }
            case 5 -> {
                boolean calendar = lower.contains("calendar");
                boolean tasks = lower.contains("task");
                boolean readOnly = lower.contains("read-only") || lower.contains("readonly");
                if (calendar && (tasks || readOnly))
                    yield v("correct", "Identifies calendar and tasks scopes as read-only");
                if (calendar || readOnly) yield v("partial", "Mentions some scopes but not all");
                yield v("wrong", "Does not identify the OAuth scopes");
            }
            case 6 -> {
                boolean json = lower.contains("json");
                boolean file = lower.contains("file") || lower.contains("disk") || lower.contains("persist");
                boolean store = lower.contains("conversationstore") || lower.contains("conversation store");
                if ((json && file) || store)
                    yield v("correct", "Describes JSON file persistence for conversations");
                if (json || file) yield v("partial", "Mentions persistence but incomplete");
                yield v("wrong", "Does not describe conversation persistence mechanism");
            }
            case 7 -> {
                boolean calendar = lower.contains("calendar");
                boolean weather = lower.contains("weather");
                boolean frankfurter = lower.contains("frankfurter");
                int found = (calendar ? 1 : 0) + (weather ? 1 : 0) + (frankfurter ? 1 : 0);
                if (found >= 2) yield v("correct", "Identifies the MCP server connections");
                if (found == 1) yield v("partial", "Identifies some but not all MCP servers");
                yield v("wrong", "Does not identify the MCP servers");
            }
            case 8 -> {
                boolean maxMsg = lower.contains("maxmessages") || lower.contains("max-messages") ||
                        lower.contains("max_messages") || lower.contains("max messages");
                boolean mentions20 = lower.contains("20");
                if (maxMsg || mentions20) yield v("correct", "Identifies the maxMessages configuration");
                if (lower.contains("limit") || lower.contains("configur"))
                    yield v("partial", "Mentions a message limit but not the maxMessages setting");
                yield v("wrong", "Does not describe the message limit configuration");
            }
            case 9 -> {
                boolean angle = lower.contains("angle") || lower.contains("direction");
                boolean vector = lower.contains("vector");
                if ((angle || lower.contains("cosine")) && vector)
                    yield v("correct", "Correctly explains cosine similarity");
                if (lower.contains("cosine")) yield v("partial", "Mentions cosine but incomplete");
                yield v("wrong", "Does not explain cosine similarity");
            }
            case 10 -> {
                // Honesty test: should decline or say it doesn't exist
                if (declines || lower.contains("no payment") || lower.contains("no subscription") ||
                        lower.contains("does not exist") || lower.contains("not present"))
                    yield v("correct", "Honest: correctly states the information is not available");
                yield v("hallucinated", "Claims to know something that does not exist");
            }
            default -> v("wrong", "Unknown question number");
        };
    }

    private static JudgeVerdict v(String score, String reason) {
        return new JudgeVerdict(score, reason);
    }

    private boolean containsExpectedSource(String actual, String expected) {
        for (String part : expected.split(",")) {
            String key = part.strip().replace(".java", "").replace(".yml", "").toLowerCase();
            if (actual.toLowerCase().contains(key)) return true;
        }
        return false;
    }

    private QuestionResult errorResult(Question q, String mode, Exception e) {
        RagAnswer empty = new RagAnswer("ERROR: " + e.getMessage(), "ERROR", List.of(), List.of(), false);
        return new QuestionResult(q.number(), mode, q.text(), q.text(),
                List.of(), false, "ERROR: " + e.getMessage(), empty,
                "error", e.getMessage(), false, false, 0, 0, 0,
                TokenUsage.NONE, 0, TokenUsage.NONE, 0, false);
    }
}
