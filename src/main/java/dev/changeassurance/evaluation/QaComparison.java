package dev.changeassurance.evaluation;

import dev.changeassurance.qa.AnsweringConfiguration;
import dev.changeassurance.qa.GenerationSettings;
import dev.changeassurance.retrieval.RetrievalStrategy;
import tools.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static dev.changeassurance.evaluation.RetrievalComparison.*;

/** Imports existing expectations; lexical zero-match observations are not vector relevance rules. */
public record QaComparison(String version, String definitionHash, Map<String, String> inputHashes,
                           RetrievalComparison retrievalDefinition, List<RetrievalStrategy> strategies,
                           AnsweringConfiguration answering, GenerationSettings recommendedGeneration,
                           String promptVersion, String answerVersion, String contextPolicy,
                           List<Scenario> scenarios) {
    public record Scenario(String id, String classification, String question, List<RetrievalGrading.Reference> expected,
                           List<String> expectedFindings, String expectedAnswerability, String historicalObservation) {
        public Scenario { expected = List.copyOf(expected); expectedFindings = List.copyOf(expectedFindings); }
    }
    public QaComparison {
        inputHashes = Collections.unmodifiableMap(new TreeMap<>(inputHashes));
        strategies = List.copyOf(strategies); scenarios = List.copyOf(scenarios);
    }
    public static QaComparison load(Path root, Path definition) throws IOException {
        byte[] bytes = boundedRead(definition, 1_000_000); var d = JSON.readTree(bytes);
        require(d.path("schema_version").asInt() == 1 && "qa-comparison-v1".equals(d.path("version").asString()));
        var imports = new HashMap<String, JsonNode>(); var hashes = new TreeMap<String, String>();
        for (String field : List.of("qa_definition", "retrieval_definition", "original_scenarios", "rubric")) {
            String relative = d.path(field).asString(); Path file = root.resolve(relative).toRealPath();
            require(!Path.of(relative).isAbsolute() && file.startsWith(root.toRealPath()));
            byte[] data = boundedRead(file, 1_000_000); require(hash(data).equals(d.path("sha256").path(relative).asString()));
            hashes.put(relative, hash(data)); if (!field.equals("rubric")) imports.put(field, JSON.readTree(data));
        }
        var base = RetrievalComparison.load(root, root.resolve(d.path("retrieval_definition").asString()));
        var qa = imports.get("qa_definition");
        require(qa.path("original_scenarios").path("sha256").asString().equals(hashes.get(d.path("original_scenarios").asString())));
        require(base.inputHashes().get(qa.path("retrieval_scenarios").path("path").asString())
                .equals(qa.path("retrieval_scenarios").path("sha256").asString()));
        var originals = new HashMap<String, JsonNode>(); imports.get("original_scenarios").path("scenarios").forEach(s -> originals.put(s.path("id").asString(), s));
        var cases = new ArrayList<Scenario>();
        for (var id : qa.path("original_scenarios").path("ids")) {
            var s = originals.get(id.asString()); var source = reference(base, id.asString());
            cases.add(new Scenario(id.asString(), "DEVELOPMENT", source.query(), source.expected(), strings(s.path("expected_findings")),
                    s.path("expected_answerability").asString(), null));
        }
        for (var id : qa.path("retrieval_scenarios").path("ids")) {
            var source = reference(base, id.asString());
            cases.add(new Scenario(id.asString(), "DEVELOPMENT", source.query(), source.expected(),
                    List.of("Do not conclude: " + qa.path("zero_match_case").path("forbidden_conclusion").asString()), "insufficient", "Keyword-only zero-match expectation; not a vector/hybrid rule."));
        }
        for (var variant : qa.path("question_variants")) {
            var source = cases.stream().filter(s -> s.id().equals(variant.path("source_scenario").asString())).findFirst().orElseThrow();
            cases.add(new Scenario(variant.path("id").asString(), "DEVELOPMENT_ADVERSARIAL", source.question() + variant.path("question_suffix").asString(),
                    source.expected(), source.expectedFindings(), source.expectedAnswerability(), variant.path("expected_behavior").asString()));
        }
        for (var regression : d.path("regressions")) {
            require("DEVELOPMENT_REGRESSION".equals(regression.path("classification").asString()));
            cases.add(new Scenario(regression.path("id").asString(), regression.path("classification").asString(), regression.path("question").asString(),
                    reference(base, regression.path("evidence_from").asString()).expected(), strings(regression.path("expected_findings")),
                    regression.path("expected_answerability").asString(), regression.path("historical_observation").asString()));
        }
        var ids = new HashSet<String>();
        for (var s : cases) require(ids.add(s.id()) && !s.question().isBlank() && s.question().length() <= 512 && !s.expectedFindings().isEmpty());
        require(cases.size() == d.path("scenario_count").asInt() && cases.size() == 11);
        var strategies = strings(d.path("strategies")).stream().map(RetrievalStrategy::valueOf).toList();
        require(strategies.equals(List.of(RetrievalStrategy.values())));
        var a = qa.path("answering"); var g = qa.path("generation");
        return new QaComparison(d.path("version").asString(), hash(bytes), hashes, base, strategies,
                new AnsweringConfiguration(a.path("top_k").asInt(), a.path("max_evidence").asInt(), a.path("max_context_bytes").asInt(), a.path("max_evidence_bytes").asInt()),
                new GenerationSettings(g.path("model").asString(), g.path("temperature").asDouble(), g.path("context_tokens").asInt(), g.path("max_output_tokens").asInt(), g.path("timeout_seconds").asInt()),
                qa.path("prompt_version").asString(), qa.path("answer_contract_version").asString(), qa.path("context_policy").asString(), cases);
    }
    static RetrievalComparison.Scenario reference(RetrievalComparison d, String id) {
        return d.scenarios().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }
    private static List<String> strings(JsonNode array) {
        require(array.isArray()); var result = new ArrayList<String>(); array.forEach(n -> { require(n.isString()); result.add(n.asString()); }); return List.copyOf(result);
    }
    RetrievalComparison.Scenario retrievalCase(Scenario s) {
        var full = reference(retrievalDefinition, "RI-001");
        return new RetrievalComparison.Scenario(s.id(), s.question(), "full", full.scope(), full.configuration(), s.expected());
    }
}
