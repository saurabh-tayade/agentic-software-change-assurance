package dev.changeassurance.qa;

/** Only the adapter knows Spring AI/Ollama response objects. No tools or actions are exposed. */
public interface ModelGateway {
    GenerationSettings settings();
    Response generate(String systemPrompt, String contextJson, String outputSchema);

    record Response(String json, Diagnostics diagnostics) { }
    /** Null usage means unavailable, never an estimated token count. */
    record Diagnostics(String provider, String model, long durationMillis,
                       Integer inputTokens, Integer outputTokens, String finishReason,
                       String returnedModel, Integer connectTimeoutSeconds, Boolean thinkingEnabled, String keepAlive) {
        public Diagnostics(String provider, String model, long durationMillis,
                           Integer inputTokens, Integer outputTokens, String finishReason) {
            this(provider, model, durationMillis, inputTokens, outputTokens, finishReason, null, null, null, null);
        }
    }
}
