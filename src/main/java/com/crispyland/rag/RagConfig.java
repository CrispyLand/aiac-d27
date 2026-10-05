package com.crispyland.rag;

/**
 * Runtime RAG settings for a single request. Built from form fields; falls back to the
 * defaults in {@link RagProperties} for anything the form did not specify.
 */
public record RagConfig(
        int candidateK,
        int finalK,
        float similarityThreshold,
        boolean rerankEnabled,
        int rerankScoreThreshold,
        boolean queryRewriteEnabled) {

    public RagConfig {
        if (finalK > candidateK) finalK = candidateK;
    }

    public static RagConfig from(RagProperties props) {
        return new RagConfig(
                props.search().candidateK(),
                props.search().finalK(),
                props.search().similarityThreshold(),
                props.rerank().enabled(),
                props.rerank().scoreThreshold(),
                props.queryRewrite().enabled());
    }

    /**
     * Merges form values with defaults: any null/out-of-range form value falls through
     * to the property default.
     */
    public static RagConfig merge(Integer candidateK, Integer finalK, Float similarityThreshold,
                                  Boolean rerankEnabled, Integer rerankScoreThreshold,
                                  Boolean queryRewriteEnabled, RagProperties defaults) {
        int ck  = (candidateK != null && candidateK >= 1 && candidateK <= 20)
                ? candidateK : defaults.search().candidateK();
        int fk  = (finalK != null && finalK >= 1 && finalK <= 8)
                ? finalK : defaults.search().finalK();
        float st = (similarityThreshold != null && similarityThreshold >= 0f && similarityThreshold <= 1f)
                ? similarityThreshold : defaults.search().similarityThreshold();
        boolean re = (rerankEnabled != null) ? rerankEnabled : defaults.rerank().enabled();
        int rst = (rerankScoreThreshold != null && rerankScoreThreshold >= 0 && rerankScoreThreshold <= 10)
                ? rerankScoreThreshold : defaults.rerank().scoreThreshold();
        boolean qr = (queryRewriteEnabled != null) ? queryRewriteEnabled : defaults.queryRewrite().enabled();
        return new RagConfig(ck, fk, st, re, rst, qr);
    }
}
