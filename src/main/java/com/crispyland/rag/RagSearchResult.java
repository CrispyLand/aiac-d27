package com.crispyland.rag;

import java.util.List;

/**
 * Full result of one RAG retrieval pass. Carries the final chunks to inject into the prompt
 * plus all diagnostic detail for the debug panel.
 */
public record RagSearchResult(
        String originalQuery,
        String effectiveQuery,       // after rewrite, or same as original
        List<ScoredChunk> finalChunks,
        RagDebug debug) {

    public boolean isEmpty() {
        return finalChunks.isEmpty();
    }

    public boolean wasRewritten() {
        return debug != null && debug.queryRewrite() != null && debug.queryRewrite().wasRewritten();
    }

    public static RagSearchResult empty(String query) {
        return new RagSearchResult(query, query, List.of(), null);
    }
}
