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
 * Runs the 10 control questions in four RAG modes and produces a Markdown comparison report.
 * Only instantiated when {@code rag.evaluation.enabled=true}.
 */
@Service
@ConditionalOnProperty(name = "rag.evaluation.enabled", havingValue = "true")
public class RagEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RagEvaluator.class);

    private final RagSearcher searcher;
    private final LlmClient llm;
    private final RagProperties props;

    public RagEvaluator(RagSearcher searcher, LlmClient llm, RagProperties props) {
        this.searcher = searcher;
        this.llm      = llm;
        this.props    = props;
    }

    public record Question(int number, String text, String expectedAnswer, String expectedSources, String category) {}

    public record QuestionResult(
            int number, String mode, String question,
            String effectiveQuery,
            List<String> finalSources,
            boolean expectedSourceFound,
            int irrelevantChunks,
            String answer,
            String answerScore,
            String judgeReason,
            TokenUsage ragUsage,
            long ragLatencyMs,
            TokenUsage answerUsage,
            long answerLatencyMs,
            boolean rerankFellBack) {}

    private static final List<Question> QUESTIONS = List.of(
        // Q1-Q3: chained conversation — each follow-up uses pronouns that need the prior context.
        // This is the scenario query rewriting was designed for.
        new Question(1, "Where does the agent store long-term memory?",
                "In a JsonFileLongTermStore that persists to a JSON file on disk, keyed by visitor",
                "JsonFileLongTermStore,AgentConfiguration", "project-specific"),
        new Question(2, "How many entries can it hold per visitor?",
                "Limited by a configurable maxEntries ceiling per visitor",
                "AgentProperties,JsonFileLongTermStore,LongTermStore", "follow-up"),
        new Question(3, "What happens to it when a conversation is reset?",
                "It survives the reset — long-term memory is keyed by visitor, not by conversation",
                "Agent,LongTermStore", "follow-up"),
        // Q4-Q8: standalone project-specific questions
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

    /** Full evaluation results for report generation. */
    public record EvalResults(List<QuestionResult> results, List<Question> questions,
                              List<String> modeNames) {}

    /**
     * Runs all questions in four modes and returns Markdown.
     * @param delayMs pause between calls to avoid rate limits
     */
    public String evaluate(long delayMs) {
        return buildMarkdown(evaluateAll(delayMs));
    }

    /** Builds a Markdown report from pre-computed results. */
    public static String buildMarkdown(EvalResults eval) {
        return buildReport(eval.results);
    }

    /**
     * Runs all questions in four modes and returns structured results.
     * @param delayMs pause between calls to avoid rate limits
     */
    public EvalResults evaluateAll(long delayMs) {
        Map<String, RagConfig> modes = new LinkedHashMap<>();
        modes.put("baseline",           new RagConfig(3, 3, 0f, false, 0, false));
        modes.put("similarity-only",    new RagConfig(10, 3, 0.30f, false, 0, false));
        modes.put("sim+rerank",         new RagConfig(10, 3, 0.30f, true, 5, false));
        modes.put("sim+rerank+rewrite", new RagConfig(10, 3, 0.30f, true, 5, true));

        List<QuestionResult> allResults = new ArrayList<>();

        for (Map.Entry<String, RagConfig> mode : modes.entrySet()) {
            log.info("Evaluation mode: {}", mode.getKey());
            // Conversation context for follow-up questions — reset per mode.
            List<String> conversationContext = new ArrayList<>();
            for (Question q : QUESTIONS) {
                try {
                    // Follow-up questions get the accumulated Q&A context from prior questions;
                    // standalone questions get none.
                    List<String> context = "follow-up".equals(q.category)
                            ? List.copyOf(conversationContext) : List.of();
                    QuestionResult r = runQuestion(q, mode.getKey(), mode.getValue(), context);
                    allResults.add(r);
                    // Accumulate context for subsequent follow-ups
                    conversationContext.add("User: " + q.text);
                    conversationContext.add("Assistant: " + r.answer);
                    log.info("  Q{} [{}]: score={}, sources={}", q.number, mode.getKey(),
                            r.answerScore, r.finalSources);
                    if (delayMs > 0) Thread.sleep(delayMs);
                } catch (Exception e) {
                    log.error("  Q{} [{}] failed: {}", q.number, mode.getKey(), e.getMessage());
                    allResults.add(new QuestionResult(q.number, mode.getKey(), q.text, q.text,
                            List.of(), false, 0, "ERROR: " + e.getMessage(), "error", e.getMessage(),
                            TokenUsage.NONE, 0, TokenUsage.NONE, 0, false));
                    // Still accumulate so numbering stays aligned
                    conversationContext.add("User: " + q.text);
                    conversationContext.add("Assistant: ERROR");
                }
            }
        }

        return new EvalResults(allResults, QUESTIONS, new ArrayList<>(modes.keySet()));
    }

    private QuestionResult runQuestion(Question q, String modeName, RagConfig config,
                                       List<String> recentContext) {
        // RAG retrieval — follow-up questions pass conversation context for query rewriting
        RagSearchResult ragResult = searcher.search(q.text, recentContext, config);

        String effectiveQuery = ragResult.effectiveQuery();
        List<String> finalSources = ragResult.finalChunks().stream()
                .map(sc -> sc.chunk().sourceFile())
                .toList();

        // Check expected source
        boolean expectedFound = q.expectedSources.isBlank() ||
                finalSources.stream().anyMatch(s ->
                        containsExpectedSource(s, q.expectedSources));

        int irrelevant = 0;
        if (!q.expectedSources.isBlank()) {
            irrelevant = (int) finalSources.stream()
                    .filter(s -> !containsExpectedSource(s, q.expectedSources))
                    .count();
        }

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

        // Build prompt and get answer
        StringBuilder prompt = new StringBuilder();
        if (!ragResult.isEmpty()) {
            prompt.append("Relevant context from the codebase:\n\n");
            for (ScoredChunk sc : ragResult.finalChunks()) {
                prompt.append("--- [").append(sc.chunk().sourceFile()).append("] ---\n");
                prompt.append(sc.chunk().text()).append("\n\n");
            }
            prompt.append("---\n\n");
        }
        prompt.append("Question: ").append(q.text);

        long answerStart = System.nanoTime();
        ChatRequest req = new ChatRequest(
                props.rerank().model(),
                List.of(
                        Message.system("Answer concisely based on the provided context. If the context does not cover the topic, say so honestly."),
                        Message.user(prompt.toString())
                ),
                0.0, 400, "", List.of(), ""
        );
        ChatResponse resp = llm.complete(req);
        long answerLatency = (System.nanoTime() - answerStart) / 1_000_000;

        String answer = resp.content().strip();
        JudgeVerdict verdict = judgeAnswer(q, answer);

        return new QuestionResult(q.number, modeName, q.text, effectiveQuery,
                finalSources, expectedFound, irrelevant, answer, verdict.score, verdict.reason,
                ragUsage, ragLatency, resp.usage(), answerLatency, fellBack);
    }

    private record JudgeVerdict(String score, String reason) {}

    /**
     * Keyword-based scorer — no LLM judge call, just checks whether the answer contains
     * the key concepts for each question. Faster, cheaper, and more reliable than asking
     * another model to grade the answer.
     */
    private JudgeVerdict judgeAnswer(Question q, String answer) {
        String lower = answer.toLowerCase().replace('\u2019', '\'').replace('\u2018', '\'');
        // An answer that says "I don't know" / "not in the context" is wrong for project
        // questions (the info IS in the codebase) but correct for the honesty test.
        boolean declines = lower.contains("not contain") || lower.contains("not cover") ||
                lower.contains("not mention") || lower.contains("don't know") ||
                lower.contains("no information") || lower.contains("not found") ||
                lower.contains("not available") || lower.contains("i'm sorry") ||
                lower.contains("does not contain") || lower.contains("doesn't contain");

        return switch (q.number) {
            case 1 -> {
                // Q1: Where does the agent store long-term memory?
                // Correct: mentions JSON file / JsonFileLongTermStore / persisted to disk + keyed by visitor
                boolean json = lower.contains("json") || lower.contains("file");
                boolean longTerm = lower.contains("longterm") || lower.contains("long-term") ||
                        lower.contains("long term") || lower.contains("longtermstore");
                if (json && longTerm) yield v("correct", "Mentions JSON file storage for long-term memory");
                if (longTerm) yield v("partial", "Mentions long-term store but not the JSON persistence");
                yield v("wrong", "Does not describe JSON-file-based long-term memory storage");
            }
            case 2 -> {
                // Q2: How many entries can it hold per visitor?
                // Correct: mentions maxEntries / configurable limit / ceiling per visitor
                boolean maxEntries = lower.contains("maxentries") || lower.contains("max entries") ||
                        lower.contains("max-entries") || lower.contains("maximum");
                boolean configurable = lower.contains("configur") || lower.contains("ceiling") ||
                        lower.contains("limit") || lower.contains("cap");
                if (maxEntries || (configurable && lower.contains("visitor")))
                    yield v("correct", "Identifies the configurable per-visitor entry limit");
                if (configurable) yield v("partial", "Mentions a limit but not per-visitor maxEntries");
                yield v("wrong", "Does not describe the per-visitor entry limit");
            }
            case 3 -> {
                // Q3: What happens to it when a conversation is reset?
                // Correct: survives / preserved / not cleared / keyed by visitor not conversation
                boolean survives = lower.contains("surviv") || lower.contains("preserv") ||
                        lower.contains("not clear") || lower.contains("not affect") ||
                        lower.contains("remain") || lower.contains("not reset") ||
                        lower.contains("not removed") || lower.contains("not erased") ||
                        lower.contains("independent") || lower.contains("keyed by visitor");
                if (survives) yield v("correct", "Correctly states long-term memory survives a reset");
                if (lower.contains("clear") || lower.contains("erase") || lower.contains("delete"))
                    yield v("wrong", "Incorrectly says long-term memory is cleared on reset");
                yield v("wrong", "Does not address whether long-term memory survives a reset");
            }
            case 4 -> {
                // Q4: How does StructuredChunker decide where to split?
                // Correct: regex/pattern + file type
                boolean regex = lower.contains("regex") || lower.contains("pattern") ||
                        lower.contains("regular expression") || lower.contains("split");
                boolean fileType = lower.contains("file type") || lower.contains("filetype") ||
                        lower.contains("java") || lower.contains("markdown") || lower.contains("yml");
                if (regex && fileType) yield v("correct", "Describes regex-based splitting by file type");
                if (regex) yield v("partial", "Mentions pattern splitting but not file-type awareness");
                yield v("wrong", "Does not describe regex-based file-type-aware splitting");
            }
            case 5 -> {
                // Q5: Which Google OAuth scopes?
                // Correct: calendar + tasks / read-only / readonly
                boolean calendar = lower.contains("calendar");
                boolean tasks = lower.contains("task");
                boolean readOnly = lower.contains("read-only") || lower.contains("readonly") ||
                        lower.contains("read only");
                if (calendar && (tasks || readOnly))
                    yield v("correct", "Identifies calendar and tasks scopes as read-only");
                if (calendar || readOnly) yield v("partial", "Mentions some scopes but not all");
                if (declines) yield v("wrong", "Declines to answer despite scopes being in the code");
                yield v("wrong", "Does not identify the OAuth scopes");
            }
            case 6 -> {
                // Q6: How are conversations persisted?
                // Correct: JSON file / JsonFileConversationStore / written to disk
                boolean json = lower.contains("json");
                boolean file = lower.contains("file") || lower.contains("disk") || lower.contains("persist");
                boolean store = lower.contains("conversationstore") || lower.contains("conversation store");
                if ((json && file) || store)
                    yield v("correct", "Describes JSON file persistence for conversations");
                if (json || file) yield v("partial", "Mentions persistence but incomplete description");
                yield v("wrong", "Does not describe conversation persistence mechanism");
            }
            case 7 -> {
                // Q7: What MCP servers does the agent connect to?
                // Correct: mentions calendar + weather, or frankfurter, or SSE/streamable-HTTP
                boolean calendar = lower.contains("calendar");
                boolean weather = lower.contains("weather");
                boolean frankfurter = lower.contains("frankfurter");
                int found = (calendar ? 1 : 0) + (weather ? 1 : 0) + (frankfurter ? 1 : 0);
                if (found >= 2) yield v("correct", "Identifies the MCP server connections");
                if (found == 1) yield v("partial", "Identifies some but not all MCP servers");
                if (declines) yield v("wrong", "Declines to answer despite MCP config being in the code");
                yield v("wrong", "Does not identify the MCP servers");
            }
            case 8 -> {
                // Q8: Max messages in conversation history?
                // Correct: mentions maxMessages / max-messages / configurable limit
                boolean maxMsg = lower.contains("maxmessages") || lower.contains("max-messages") ||
                        lower.contains("max_messages") || lower.contains("max messages");
                boolean mentions20 = lower.contains("20");
                if (maxMsg || mentions20)
                    yield v("correct", "Identifies the maxMessages configuration");
                if (lower.contains("limit") || lower.contains("configur"))
                    yield v("partial", "Mentions a message limit but not the maxMessages setting");
                yield v("wrong", "Does not describe the message limit configuration");
            }
            case 9 -> {
                // Q9: What is cosine similarity?
                // Correct: angle/direction between vectors
                boolean angle = lower.contains("angle") || lower.contains("direction");
                boolean vector = lower.contains("vector");
                boolean cosine = lower.contains("cosine") || lower.contains("dot product");
                if ((angle || cosine) && vector)
                    yield v("correct", "Correctly explains cosine similarity for embeddings");
                if (cosine) yield v("partial", "Mentions cosine but incomplete explanation");
                yield v("wrong", "Does not explain cosine similarity");
            }
            case 10 -> {
                // Q10: Honesty test — no payment provider exists
                if (declines || lower.contains("no payment") || lower.contains("no subscription") ||
                        lower.contains("does not exist") || lower.contains("not present") ||
                        lower.contains("no evidence") || lower.contains("doesn't contain"))
                    yield v("correct", "Honest answer: correctly states the information is not available");
                yield v("hallucinated", "Claims to know something that does not exist in the codebase");
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

    private static String buildReport(List<QuestionResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# RAG Evaluation Report\n\n");
        sb.append("Generated: ").append(Instant.now()).append("\n\n");

        // Summary table per mode
        sb.append("## Summary by Mode\n\n");
        sb.append("| Mode | Correct | Partial | Wrong | Hallucinated | Avg RAG ms | Avg Answer ms | Total Tokens |\n");
        sb.append("|------|---------|---------|-------|--------------|------------|---------------|-------------|\n");

        Map<String, List<QuestionResult>> byMode = results.stream()
                .collect(Collectors.groupingBy(QuestionResult::mode, LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<String, List<QuestionResult>> entry : byMode.entrySet()) {
            List<QuestionResult> modeResults = entry.getValue();
            long correct = modeResults.stream().filter(r -> "correct".equals(r.answerScore)).count();
            long partial = modeResults.stream().filter(r -> "partial".equals(r.answerScore)).count();
            long wrong = modeResults.stream().filter(r -> "wrong".equals(r.answerScore)).count();
            long hallucinated = modeResults.stream().filter(r -> "hallucinated".equals(r.answerScore)).count();
            long avgRag = modeResults.stream().mapToLong(QuestionResult::ragLatencyMs).sum() / modeResults.size();
            long avgAnswer = modeResults.stream().mapToLong(QuestionResult::answerLatencyMs).sum() / modeResults.size();
            long totalTokens = modeResults.stream()
                    .mapToLong(r -> r.ragUsage.totalTokens() + r.answerUsage.totalTokens())
                    .sum();
            sb.append("| ").append(entry.getKey())
              .append(" | ").append(correct)
              .append(" | ").append(partial)
              .append(" | ").append(wrong)
              .append(" | ").append(hallucinated)
              .append(" | ").append(avgRag)
              .append(" | ").append(avgAnswer)
              .append(" | ").append(totalTokens)
              .append(" |\n");
        }

        // Per-question comparison
        sb.append("\n## Per-Question Comparison\n\n");
        Map<Integer, List<QuestionResult>> byQuestion = results.stream()
                .collect(Collectors.groupingBy(QuestionResult::number, LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<Integer, List<QuestionResult>> entry : byQuestion.entrySet()) {
            Question q = QUESTIONS.get(entry.getKey() - 1);
            sb.append("### Q").append(q.number).append(": ").append(q.text).append("\n\n");
            sb.append("**Category:** ").append(q.category).append("  \n");
            sb.append("**Expected:** ").append(q.expectedAnswer).append("\n\n");
            sb.append("| Mode | Score | Sources | Expected Found | Irrelevant | Rerank Fallback | Answer (truncated) |\n");
            sb.append("|------|-------|---------|----------------|------------|-----------------|--------------------|\n");
            for (QuestionResult r : entry.getValue()) {
                String answerTrunc = r.answer.length() > 80
                        ? r.answer.substring(0, 80).replace("\n", " ") + "…"
                        : r.answer.replace("\n", " ");
                sb.append("| ").append(r.mode)
                  .append(" | ").append(r.answerScore)
                  .append(" | ").append(String.join(", ", r.finalSources))
                  .append(" | ").append(r.expectedSourceFound ? "✓" : "✗")
                  .append(" | ").append(r.irrelevantChunks)
                  .append(" | ").append(r.rerankFellBack ? "⚠ yes" : "no")
                  .append(" | ").append(answerTrunc)
                  .append(" |\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
