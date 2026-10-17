package dev.changeassurance.qa;

/** Conservative local inference settings; token context is distinct from byte selection budgets. */
public record GenerationSettings(String model, double temperature, int contextTokens,
                                 int maxOutputTokens, int timeoutSeconds) {
    public GenerationSettings {
        if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")
                || !Double.isFinite(temperature) || temperature < 0 || temperature > 0.2
                || contextTokens < 8192 || contextTokens > 16384 || maxOutputTokens < 128 || maxOutputTokens > 1024
                || timeoutSeconds < 1 || timeoutSeconds > 180) {
            throw new IllegalArgumentException("Invalid local Q&A generation settings");
        }
    }
    public static GenerationSettings defaults() {
        return new GenerationSettings("qwen3:4b-instruct", 0, 8192, 768, 120);
    }
}
