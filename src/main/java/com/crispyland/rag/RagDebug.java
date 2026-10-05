package com.crispyland.rag;

import java.util.List;

/**
 * Diagnostic information for one RAG retrieval pass. Displayed in the debug panel on the
 * chat page so the pipeline's decisions are visible and debuggable.
 */
public record RagDebug(
        QueryRewriteResult queryRewrite,
        List<ScoredChunk> stage1Candidates,       // all candidates from cosine search
        List<ScoredChunk> droppedBySimilarity,     // candidates below similarity threshold
        RerankerResult rerankerResult,
        List<RankedChunk> droppedByRerank,         // candidates below rerank score threshold
        List<RankedChunk> finalChunks,             // what was sent to the answer model
        long totalLatencyMs) {

    public boolean rerankWasUsed() {
        return rerankerResult != null && rerankerResult != RerankerResult.SKIPPED;
    }

    public boolean rerankFellBack() {
        return rerankWasUsed() && rerankerResult.fellBack();
    }
}
