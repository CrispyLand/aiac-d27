package com.crispyland.rag;

import com.crispyland.agent.usage.TokenUsage;
import java.util.List;

/**
 * What the reranker returned for one retrieval pass.
 *
 * @param ranked         all candidates with their LLM scores and reasons
 * @param fellBack       true when the LLM call failed and similarity order was used instead
 * @param fallbackReason why the fallback happened, empty when it did not
 * @param latencyMs      wall-clock time of the reranker call
 * @param usage          token usage of the reranker call
 */
public record RerankerResult(
        List<RankedChunk> ranked,
        boolean fellBack,
        String fallbackReason,
        long latencyMs,
        TokenUsage usage) {

    public static final RerankerResult SKIPPED =
            new RerankerResult(List.of(), false, "", 0, TokenUsage.NONE);
}
