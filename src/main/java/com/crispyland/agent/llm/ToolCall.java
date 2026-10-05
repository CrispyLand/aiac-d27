package com.crispyland.agent.llm;

import java.util.Map;

/**
 * The model asking for a tool to be run.
 *
 * @param id        the provider's handle for this call. Opaque and load-bearing: the result has to
 *                  be sent back quoting it, or a reply to one of two parallel calls is matched to
 *                  the wrong question
 * @param arguments what the model filled the schema in with, already parsed. Arrives from the
 *                  provider as a JSON <em>string</em> and is turned into this map by
 *                  {@link CerebrasLlmClient}, so that the layer which executes tools never has to
 *                  parse anything — and so that a model emitting malformed JSON is caught at the
 *                  boundary rather than three classes further in
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {

    public ToolCall {
        arguments = (arguments == null) ? Map.of() : Map.copyOf(arguments);
    }
}
