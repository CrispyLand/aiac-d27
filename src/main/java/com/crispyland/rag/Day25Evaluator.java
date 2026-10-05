package com.crispyland.rag;

import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.memory.Message;
import com.crispyland.agent.usage.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Day 25 evaluator: runs two long multi-turn scenarios (10-15 turns each) to verify
 * that the agent retains the dialogue goal, remembers clarifications, and continues
 * providing sourced answers throughout a long conversation.
 */
@Service
@ConditionalOnProperty(name = "rag.evaluation.enabled", havingValue = "true")
public class Day25Evaluator {

    private static final Logger log = LoggerFactory.getLogger(Day25Evaluator.class);

    private final RagSearcher searcher;
    private final LlmClient llm;
    private final RagProperties props;

    public Day25Evaluator(RagSearcher searcher, LlmClient llm, RagProperties props) {
        this.searcher = searcher;
        this.llm = llm;
        this.props = props;
    }

    public record Turn(int number, String text, String category, String checkKeywords) {}

    public record TurnResult(
            int number, String scenarioName, String question, String category,
            String effectiveQuery, List<String> finalSources,
            String rawAnswer, RagAnswer parsedAnswer,
            boolean hasSources, boolean hasQuotes,
            int quotesVerified, int quotesRejected, int quotesTotal,
            boolean goalRetained, boolean clarificationRetained,
            String judgeReason,
            TokenUsage ragUsage, long ragLatencyMs,
            TokenUsage answerUsage, long answerLatencyMs) {}

    public record ScenarioResult(String name, String description, String goal,
                                  List<TurnResult> turns,
                                  int sourcedTurns, int totalFactualTurns,
                                  boolean goalRetainedOnCheck, boolean clarificationRetainedOnCheck) {}

    public record EvalResults(List<ScenarioResult> scenarios) {}

    // --- Scenario A: Understanding memory layers (12 turns) ---

    private static final String SCENARIO_A_NAME = "Memory Layers Deep Dive";
    private static final String SCENARIO_A_DESC = "Tests goal retention and sourced answers across 12 turns exploring the memory system";
    private static final String SCENARIO_A_GOAL = "understand how memory layers work end to end";

    private static final List<Turn> SCENARIO_A = List.of(
        new Turn(1,  "I want to understand how memory layers work end to end in this codebase. That is my goal for this conversation.",
                "goal", ""),
        new Turn(2,  "What memory layers exist in the agent?",
                "factual", "short-term,working,long-term,profile,invariant"),
        new Turn(3,  "How does the working memory / facts extraction work?",
                "factual", "extractor,tag,key,value"),
        new Turn(4,  "What tags does the memory extractor use?",
                "factual", "task,decision,profile,knowledge,stage"),
        new Turn(5,  "How are facts stored between turns?",
                "factual", "facts,key,value"),
        new Turn(6,  "What happens when the fact limit is exceeded?",
                "factual", "max,limit,replace,oldest"),
        new Turn(7,  "Let me clarify — I am most interested in how facts survive a server restart. Do they persist to disk?",
                "clarification", ""),
        new Turn(8,  "How does the long-term store differ from working memory?",
                "factual", "long-term,visitor,persist,json"),
        new Turn(9,  "What is the memory router's role?",
                "factual", "router,route,tag,layer"),
        new Turn(10, "Can the model write directly to long-term memory, or does everything go through extraction?",
                "factual", "extract,route,model"),
        new Turn(11, "Let me add a constraint: facts are extracted only from user messages, never from assistant replies. Is that how it works?",
                "constraint", "user,message,never,assistant"),
        new Turn(12, "Given my original goal about memory layers and the constraint I stated, which single class should I read first to understand the full pipeline?",
                "goal-check", "memory")
    );

    // --- Scenario B: Task state machine (11 turns) ---

    private static final String SCENARIO_B_NAME = "Task State Machine";
    private static final String SCENARIO_B_DESC = "Tests clarification retention and sourced answers across 11 turns exploring the task system";
    private static final String SCENARIO_B_GOAL = "understand the task state machine and whether stages can be skipped";

    private static final List<Turn> SCENARIO_B = List.of(
        new Turn(1,  "I want to understand the task state machine in this codebase and whether stages can be skipped.",
                "goal", ""),
        new Turn(2,  "What stages exist in the task state machine?",
                "factual", "planning,execution,validation,done"),
        new Turn(3,  "Which transitions are allowed between stages?",
                "factual", "transition,move,allowed"),
        new Turn(4,  "What is a human-only edge in the state machine?",
                "factual", "human,approve,manual"),
        new Turn(5,  "How does the stage gate work?",
                "factual", "gate,block,guard"),
        new Turn(6,  "What happens if the model tries to skip a stage?",
                "factual", "refuse,illegal,block"),
        new Turn(7,  "I should clarify — I only care about the PLANNING to EXECUTION transition specifically.",
                "clarification", ""),
        new Turn(8,  "What exactly blocks the model from moving to EXECUTION on its own?",
                "factual", "human,gate,block,require"),
        new Turn(9,  "Can the model pause a task?",
                "factual", "pause,resume"),
        new Turn(10, "Given that I only care about PLANNING to EXECUTION, what is the minimal code path I need to trace?",
                "clarification-check", "planning,execution"),
        new Turn(11, "Remind me — what was my original question about the state machine?",
                "goal-check", "skip,stage")
    );

    private static final RagConfig EVAL_CONFIG = new RagConfig(10, 3, 0.30f, true, 5, false);

    private static final String SYSTEM_PROMPT =
            "You are a concise, helpful assistant that answers questions about a codebase. " +
            "You maintain awareness of the conversation's stated goal, any clarifications the user " +
            "has provided, and any constraints they have declared. When answering, always cite your " +
            "sources and include verbatim quotes from the provided context.";

    private static final String FORMAT_INSTRUCTION = """

            IMPORTANT: Structure your response with these EXACT headings:

            ### Answer
            Your concise answer. Use citation markers like [1], [2] to reference sources.
            Always keep in mind the user's stated goal, clarifications, and constraints from earlier in the conversation.

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
            """;

    public EvalResults evaluateAll(long delayMs) {
        List<ScenarioResult> scenarios = new ArrayList<>();
        scenarios.add(runScenario(SCENARIO_A_NAME, SCENARIO_A_DESC, SCENARIO_A_GOAL, SCENARIO_A, delayMs));
        scenarios.add(runScenario(SCENARIO_B_NAME, SCENARIO_B_DESC, SCENARIO_B_GOAL, SCENARIO_B, delayMs));
        return new EvalResults(scenarios);
    }

    private ScenarioResult runScenario(String name, String description, String goal,
                                        List<Turn> turns, long delayMs) {
        log.info("Day25 scenario: {}", name);
        List<TurnResult> results = new ArrayList<>();
        List<String> conversationContext = new ArrayList<>();

        for (Turn turn : turns) {
            try {
                TurnResult r = runTurn(turn, name, goal, conversationContext);
                results.add(r);
                conversationContext.add("User: " + turn.text());
                conversationContext.add("Assistant: " + r.parsedAnswer().answer());
                log.info("  Turn {} [{}]: sources={}, goal={}, clarification={} — {}",
                        turn.number(), turn.category(),
                        r.hasSources(), r.goalRetained(), r.clarificationRetained(),
                        r.judgeReason());
                if (delayMs > 0) Thread.sleep(delayMs);
            } catch (Exception e) {
                log.error("  Turn {} failed: {}", turn.number(), e.getMessage());
                results.add(errorResult(turn, name, e));
                conversationContext.add("User: " + turn.text());
                conversationContext.add("Assistant: ERROR");
            }
        }

        int factualTurns = 0;
        int sourcedTurns = 0;
        boolean goalCheck = false;
        boolean clarCheck = false;
        for (TurnResult r : results) {
            if ("factual".equals(r.category())) {
                factualTurns++;
                if (r.hasSources()) sourcedTurns++;
            }
            if ("goal-check".equals(r.category())) goalCheck = r.goalRetained();
            if ("clarification-check".equals(r.category())) clarCheck = r.clarificationRetained();
        }

        return new ScenarioResult(name, description, goal, results,
                sourcedTurns, factualTurns, goalCheck, clarCheck);
    }

    private TurnResult runTurn(Turn turn, String scenarioName, String scenarioGoal,
                                List<String> conversationContext) {
        // RAG search with full conversation context
        RagSearchResult ragResult = searcher.search(turn.text(), conversationContext, EVAL_CONFIG);

        String effectiveQuery = ragResult.effectiveQuery();
        List<String> finalSources = ragResult.finalChunks().stream()
                .map(sc -> sc.chunk().sourceFile()).toList();

        TokenUsage ragUsage = TokenUsage.NONE;
        long ragLatency = 0;
        if (ragResult.debug() != null) {
            ragLatency = ragResult.debug().totalLatencyMs();
            if (ragResult.debug().queryRewrite() != null)
                ragUsage = ragUsage.plus(ragResult.debug().queryRewrite().usage());
            if (ragResult.debug().rerankerResult() != null)
                ragUsage = ragUsage.plus(ragResult.debug().rerankerResult().usage());
        }

        // Build LLM messages with conversation history
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(SYSTEM_PROMPT));

        // Include conversation history so the model sees the full dialogue
        for (int i = 0; i < conversationContext.size(); i += 2) {
            String userMsg = conversationContext.get(i).replaceFirst("^User: ", "");
            messages.add(Message.user(userMsg));
            if (i + 1 < conversationContext.size()) {
                String assistantMsg = conversationContext.get(i + 1).replaceFirst("^Assistant: ", "");
                messages.add(Message.assistant(assistantMsg));
            }
        }

        // Current turn with RAG context
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
        prompt.append("Question: ").append(turn.text());
        prompt.append(FORMAT_INSTRUCTION);
        messages.add(Message.user(prompt.toString()));

        long answerStart = System.nanoTime();
        ChatRequest req = new ChatRequest(
                props.rerank().model(), messages,
                0.0, 800, "", List.of(), "");
        ChatResponse resp = llm.complete(req);
        long answerLatency = (System.nanoTime() - answerStart) / 1_000_000;

        String rawAnswer = resp.content().strip();
        RagAnswer parsed = RagAnswer.parse(rawAnswer, ragResult.finalChunks());

        // Check goal and clarification retention
        boolean goalRetained = false;
        boolean clarRetained = false;
        String judgeReason;
        String ansLower = parsed.answer().toLowerCase();

        switch (turn.category()) {
            case "goal-check" -> {
                // Does the answer reference the original goal?
                String[] goalWords = scenarioGoal.toLowerCase().split("\\s+");
                int matched = 0;
                for (String w : goalWords) {
                    if (w.length() > 3 && ansLower.contains(w)) matched++;
                }
                goalRetained = matched >= goalWords.length / 2;
                // Also check turn-specific keywords
                if (!turn.checkKeywords().isBlank()) {
                    boolean hasKeyword = false;
                    for (String kw : turn.checkKeywords().split(",")) {
                        if (ansLower.contains(kw.trim())) { hasKeyword = true; break; }
                    }
                    goalRetained = goalRetained || hasKeyword;
                }
                judgeReason = goalRetained
                        ? "References the original goal in the answer"
                        : "Does not reference the original goal — goal may be lost";
            }
            case "clarification-check" -> {
                // Does the answer reference the earlier clarification?
                if (!turn.checkKeywords().isBlank()) {
                    int matched = 0;
                    String[] keywords = turn.checkKeywords().split(",");
                    for (String kw : keywords) {
                        if (ansLower.contains(kw.trim())) matched++;
                    }
                    clarRetained = matched >= Math.max(1, keywords.length / 2);
                }
                judgeReason = clarRetained
                        ? "References the earlier clarification in the answer"
                        : "Does not reference the earlier clarification";
            }
            case "factual" -> {
                if (!turn.checkKeywords().isBlank()) {
                    int matched = 0;
                    String[] keywords = turn.checkKeywords().split(",");
                    for (String kw : keywords) {
                        if (ansLower.contains(kw.trim())) matched++;
                    }
                    boolean correct = matched >= Math.max(1, keywords.length / 3);
                    judgeReason = correct
                            ? "Answer contains expected keywords (" + matched + "/" + keywords.length + ")"
                            : "Missing expected keywords (" + matched + "/" + keywords.length + ")";
                } else {
                    judgeReason = "No keywords to check";
                }
            }
            default -> judgeReason = "Non-factual turn (" + turn.category() + ")";
        }

        int verified = (int) parsed.quotes().stream()
                .filter(q -> q.status() == RagAnswer.QuoteStatus.VERIFIED).count();
        int rejected = (int) parsed.quotes().stream()
                .filter(q -> q.status() == RagAnswer.QuoteStatus.REJECTED).count();

        return new TurnResult(turn.number(), scenarioName, turn.text(), turn.category(),
                effectiveQuery, finalSources, rawAnswer, parsed,
                parsed.hasSources(), parsed.hasQuotes(),
                verified, rejected, parsed.quotes().size(),
                goalRetained, clarRetained, judgeReason,
                ragUsage, ragLatency, resp.usage(), answerLatency);
    }

    private TurnResult errorResult(Turn turn, String scenarioName, Exception e) {
        RagAnswer empty = RagAnswer.parse("Error: " + e.getMessage(), List.of());
        return new TurnResult(turn.number(), scenarioName, turn.text(), turn.category(),
                turn.text(), List.of(), "ERROR: " + e.getMessage(), empty,
                false, false, 0, 0, 0, false, false,
                "Error: " + e.getMessage(),
                TokenUsage.NONE, 0, TokenUsage.NONE, 0);
    }
}
