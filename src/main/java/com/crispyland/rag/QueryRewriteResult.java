package com.crispyland.rag;

import com.crispyland.agent.usage.TokenUsage;

/**
 * The result of one query-rewrite pass.
 *
 * @param original   what the user typed
 * @param rewritten  the search-optimised form, equal to original when rewriting was skipped or failed
 * @param latencyMs  wall-clock time of the rewrite call
 * @param usage      token usage of the rewrite call
 */
public record QueryRewriteResult(String original, String rewritten, long latencyMs, TokenUsage usage) {

    public boolean wasRewritten() {
        return !rewritten.equals(original);
    }

    public static QueryRewriteResult unchanged(String query) {
        return new QueryRewriteResult(query, query, 0, TokenUsage.NONE);
    }
}
