package com.crispyland.agent.llm;

import java.util.List;
import java.util.Map;

/**
 * One tool as it is offered to the model.
 * <p>
 * The agent never writes this by hand. Every field is something a server said over MCP, forwarded
 * unchanged — which is the whole claim being made: the model is being told about capabilities that
 * were not known when this class was compiled.
 *
 * @param parameters the tool's JSON Schema, still as the nested map the protocol delivered.
 *                   Deliberately not a string and deliberately not modelled as Java types: it is
 *                   somebody else's schema, this application has no business understanding it, and
 *                   the only code that needs to is the one class allowed to touch JSON. Passing the
 *                   map keeps that true — {@link CerebrasLlmClient} converts it, nobody else parses it
 */
public record ToolSpec(String name, String description, Map<String, Object> parameters) {

    public ToolSpec {
        parameters = (parameters == null) ? Map.of() : Map.copyOf(parameters);
    }

    /**
     * The whole offered set, in the shape it goes on the wire, <em>for counting only</em>.
     * <p>
     * This string is never sent. {@link CerebrasLlmClient} builds the real array with Jackson; this is
     * a second rendering that exists so {@code ContextPlanner} can price the tools without either
     * parsing JSON itself or reaching into the HTTP client. A tool costs hundreds of tokens and
     * the estimator counted it as zero, which is how a pre-flight check waves through a prompt ten
     * times the size it believes — the duplication is worth it to close that.
     * <p>
     * Built as one string rather than summed per tool so the array's own brackets and separators
     * are counted too, and so the tokenizer sees the same run of text the provider will.
     */
    public static String renderAll(List<ToolSpec> tools) {
        if (tools == null || tools.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(512).append('[');
        for (int i = 0; i < tools.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            tools.get(i).render(out);
        }
        return out.append(']').toString();
    }

    private void render(StringBuilder out) {
        out.append("{\"type\":\"function\",\"function\":{\"name\":");
        write(out, name);
        out.append(",\"description\":");
        write(out, description);
        out.append(",\"parameters\":");
        write(out, parameters);
        out.append("}}");
    }

    /**
     * A schema of unknown shape, flattened to JSON-ish text.
     * <p>
     * Not escaped, and that is not an oversight: escaping changes a token count by a rounding error
     * and this output is never parsed by anything. What matters is that every key and every
     * description the server wrote is present exactly once, because those are what the cost is
     * actually made of.
     */
    private static void write(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String text -> out.append('"').append(text).append('"');
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    out.append('"').append(entry.getKey()).append("\":");
                    write(out, entry.getValue());
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(out, list.get(i));
                }
                out.append(']');
            }
            default -> out.append(value);
        }
    }
}
