package com.crispyland.rag;

import java.util.List;

/** Scores and filters vector-search candidates for relevance to a query. */
public interface Reranker {

    /**
     * @param query      the (possibly rewritten) search query
     * @param candidates cosine-scored candidates from stage 1
     * @param config     runtime settings (score threshold, finalK)
     * @return scored candidates with reasons, plus whether a fallback was used
     */
    RerankerResult rerank(String query, List<ScoredChunk> candidates, RagConfig config);
}
