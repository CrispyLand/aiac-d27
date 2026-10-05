package com.crispyland.rag;

import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.memory.Message;
import com.crispyland.agent.usage.TokenUsage;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Rewrites a user question into a concise search query before embedding.
 * <p>
 * Uses recent conversation context to resolve follow-ups ("it", "that", "the same method").
 * Never answers the question — only rephrases it for better semantic retrieval.
 * Falls back to the original query on any error.
 */
@Service
public class LlmQueryRewriter implements QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(LlmQueryRewriter.class);

    private static final String SYSTEM = """
            You are a search query optimizer for a code and documentation index.
            Rewrite the user's question as a short, self-contained declarative phrase \
            that maximises semantic similarity to the relevant source code or documentation.

            Rules:
            - Output ONLY the rewritten query — no explanation, no quotes, no punctuation at the end.
            - Keep it under 30 words.
            - Replace pronouns ("it", "that", "the same") with the actual subject, using the \
              conversation context provided.
            - Use technical vocabulary likely to appear in Java source files or YAML config.
            - If the question is already precise and technical, return it unchanged.
            - Do NOT answer the question. Do NOT provide explanations.
            """;

    private final LlmClient llm;
    private final RagProperties props;

    public LlmQueryRewriter(LlmClient llm, RagProperties props) {
        this.llm   = llm;
        this.props = props;
    }

    @Override
    public QueryRewriteResult rewrite(String query, List<String> recentContext) {
        long start = System.nanoTime();
        try {
            StringBuilder userMsg = new StringBuilder();
            if (recentContext != null && !recentContext.isEmpty()) {
                userMsg.append("Recent conversation context:\n");
                for (String msg : recentContext) {
                    userMsg.append("- ").append(msg).append("\n");
                }
                userMsg.append("\n");
            }
            userMsg.append("Question to rewrite: ").append(query);

            ChatRequest req = new ChatRequest(
                    props.queryRewrite().model(),
                    List.of(Message.system(SYSTEM), Message.user(userMsg.toString())),
                    0.0,
                    60,
                    "",
                    List.of(),
                    ""
            );
            ChatResponse resp = llm.complete(req);
            long latency = (System.nanoTime() - start) / 1_000_000;

            String rewritten = extractQuery(resp.content().strip());
            if (rewritten.isBlank()) {
                log.debug("QueryRewriter returned blank, using original");
                return new QueryRewriteResult(query, query, latency, resp.usage());
            }
            log.debug("QueryRewriter ({}ms): '{}' → '{}'", latency, query, rewritten);
            return new QueryRewriteResult(query, rewritten, latency, resp.usage());

        } catch (Exception e) {
            long latency = (System.nanoTime() - start) / 1_000_000;
            log.warn("QueryRewriter failed ({}ms), using original query: {}", latency, e.getMessage());
            return new QueryRewriteResult(query, query, latency, TokenUsage.NONE);
        }
    }

    /**
     * Strips chain-of-thought reasoning that reasoning models (like gpt-oss-120b) prepend
     * to their output. Takes the last non-empty line, which is typically the actual rewritten
     * query after all the "We need to..." reasoning.
     */
    private static String extractQuery(String raw) {
        if (raw == null || raw.isBlank()) return "";
        // If it's a single line and short, it's the query itself
        String[] lines = raw.split("\n");
        if (lines.length == 1) return raw.strip();

        // Multi-line: the model leaked reasoning. Take the last non-empty line.
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].strip();
            // Skip empty lines and lines that look like reasoning
            if (line.isEmpty()) continue;
            // Remove surrounding quotes if present
            if ((line.startsWith("\"") && line.endsWith("\"")) ||
                    (line.startsWith("'") && line.endsWith("'"))) {
                line = line.substring(1, line.length() - 1).strip();
            }
            if (!line.isEmpty()) return line;
        }
        return raw.strip();
    }
}
