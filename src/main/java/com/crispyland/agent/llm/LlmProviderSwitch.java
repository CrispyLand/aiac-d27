package com.crispyland.agent.llm;

import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delegates to one of two LLM providers (Cerebras or Ollama) and lets the active one
 * be switched at runtime. Both speak the same OpenAI-compatible protocol.
 */
public class LlmProviderSwitch implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderSwitch.class);

    public enum Provider {
        CEREBRAS("Cerebras", "cloud"),
        OLLAMA("Ollama", "local");

        private final String label;
        private final String badge;

        Provider(String label, String badge) {
            this.label = label;
            this.badge = badge;
        }

        public String label() { return label; }
        public String badge() { return badge; }
    }

    private final LlmClient cerebras;
    private final LlmClient ollama;
    private final String ollamaModel;
    private final AtomicReference<Provider> active;

    public LlmProviderSwitch(LlmClient cerebras, LlmClient ollama, String ollamaModel) {
        this.cerebras = cerebras;
        this.ollama = ollama;
        this.ollamaModel = ollamaModel;
        this.active = new AtomicReference<>(Provider.CEREBRAS);
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        Provider current = active.get();
        if (current == Provider.OLLAMA) {
            // Override model and strip unsupported fields for Ollama
            request = new ChatRequest(
                    ollamaModel,
                    request.messages(),
                    request.temperature(),
                    request.maxCompletionTokens(),
                    "",  // reasoning_effort not supported
                    request.stopSequences(),
                    "",  // response_format/json_schema not supported
                    request.tools(),
                    request.rounds());
            log.debug("Routing to Ollama (model={})", ollamaModel);
        }
        return (current == Provider.OLLAMA ? ollama : cerebras).complete(request);
    }

    public void switchTo(Provider provider) {
        Provider previous = active.getAndSet(provider);
        if (previous != provider) {
            log.info("LLM provider switched: {} → {}", previous, provider);
        }
    }

    public Provider activeProvider() {
        return active.get();
    }
}
