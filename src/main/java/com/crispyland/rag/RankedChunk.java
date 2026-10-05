package com.crispyland.rag;

/**
 * A chunk after the reranker has scored it.
 *
 * @param chunk        the original chunk
 * @param cosineScore  first-stage cosine similarity
 * @param rerankScore  LLM relevance score (0–10), or -1 when the reranker was not used
 * @param reason       short reason the LLM gave, empty when reranker was not used
 */
public record RankedChunk(Chunk chunk, float cosineScore, int rerankScore, String reason) {

    /** Wraps a ScoredChunk when no reranker was applied. */
    public static RankedChunk fromCosineOnly(ScoredChunk sc) {
        return new RankedChunk(sc.chunk(), sc.score(), -1, "");
    }
}
