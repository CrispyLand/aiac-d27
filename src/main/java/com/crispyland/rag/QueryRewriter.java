package com.crispyland.rag;

import java.util.List;

/** Rewrites a user question into a form optimised for vector search. */
public interface QueryRewriter {

    /**
     * @param query         the user's original question
     * @param recentContext recent conversation messages (user + assistant) for resolving pronouns
     * @return the rewrite result, including the original when skipped or failed
     */
    QueryRewriteResult rewrite(String query, List<String> recentContext);
}
