package com.crispyland.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        int chunkSize,
        int overlap,
        String indexDir,
        Search search,
        Embedding embedding,
        Rerank rerank,
        QueryRewrite queryRewrite) {

    public RagProperties {
        chunkSize = (chunkSize <= 0) ? 800 : chunkSize;
        overlap   = (overlap < 0)    ? 100 : overlap;
        indexDir  = (indexDir == null || indexDir.isBlank()) ? "./data/index" : indexDir;
        if (search == null)       search       = new Search(10, 3, 0.30f);
        if (embedding == null)    embedding    = new Embedding(true, "text-embedding-3-small", 1536);
        if (rerank == null)       rerank       = new Rerank(true, "gpt-oss-120b", 5, 1000, 600);
        if (queryRewrite == null) queryRewrite = new QueryRewrite(false, "gpt-oss-120b");
    }

    /** First-pass vector search parameters. */
    public record Search(int candidateK, int finalK, float similarityThreshold) {
        public Search {
            candidateK = (candidateK <= 0) ? 10 : candidateK;
            finalK     = (finalK <= 0)     ? 3  : finalK;
            if (finalK > candidateK) finalK = candidateK;
        }
    }

    public record Embedding(boolean enabled, String model, int dimension) {
        public Embedding {
            model     = (model == null || model.isBlank()) ? "text-embedding-3-small" : model;
            dimension = (dimension <= 0) ? 1536 : dimension;
        }
    }

    /**
     * LLM reranker settings.
     *
     * @param scoreThreshold 0–10; candidates below this are dropped after reranking
     * @param maxChunkChars  truncate candidate text to this many characters in the reranker prompt
     * @param maxTokens      max completion tokens for the reranker call
     */
    public record Rerank(boolean enabled, String model, int scoreThreshold,
                         int maxChunkChars, int maxTokens) {
        public Rerank {
            model         = (model == null || model.isBlank()) ? "gpt-oss-120b" : model;
            scoreThreshold = Math.max(0, Math.min(10, scoreThreshold));
            maxChunkChars  = (maxChunkChars <= 0) ? 1000 : maxChunkChars;
            maxTokens      = (maxTokens <= 0) ? 600 : maxTokens;
        }
    }

    /** LLM query rewrite settings. */
    public record QueryRewrite(boolean enabled, String model) {
        public QueryRewrite {
            model = (model == null || model.isBlank()) ? "gpt-oss-120b" : model;
        }
    }
}
