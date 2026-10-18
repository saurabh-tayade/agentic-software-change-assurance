package dev.changeassurance.evaluation;

import dev.changeassurance.ingestion.RepositorySelection.Scope;
import dev.changeassurance.processing.ChunkingConfiguration;
import dev.changeassurance.retrieval.RetrievalStrategy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Imports pinned historical ground truth; never manufactures references from search results. */
public record RetrievalComparison(String version, String definitionHash, Map<String, String> inputHashes,
                                  String repositoryId, Map<String, String> sourceHashes,
                                  Map<String, String> strategyVersions, List<RetrievalStrategy> strategies,
                                  List<Integer> topK, List<Scenario> scenarios) {
    public record Scenario(String id, String query, String publicationVariant, Scope scope,
                           ChunkingConfiguration configuration, List<RetrievalGrading.Reference> expected) { }
    static final JsonMapper JSON = JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    public RetrievalComparison {
        inputHashes = Collections.unmodifiableMap(new TreeMap<>(inputHashes));
        sourceHashes = Collections.unmodifiableMap(new TreeMap<>(sourceHashes));
        strategyVersions = Collections.unmodifiableMap(new TreeMap<>(strategyVersions));
        strategies = List.copyOf(strategies); topK = List.copyOf(topK); scenarios = List.copyOf(scenarios);
    }

    public static RetrievalComparison load(Path root, Path definition) throws IOException {
        byte[] bytes = boundedRead(definition, 1_000_000);
        var d = JSON.readTree(bytes);
        require(d.path("schema_version").asInt() == 1 && d.path("version").asString().equals("retrieval-comparison-v1"));
        require(d.path("scenario_count").asInt() == 15 && d.path("executions_per_configuration").asInt() == 1);
        var hashes = new TreeMap<String, String>();
        var imported = new HashMap<String, JsonNode>();
        for (String field : List.of("keyword_definition", "original_scenarios", "corpus_manifest")) {
            String relative = d.path(field).asString();
            byte[] content = boundedRead(safe(root, relative), 1_000_000);
            String hash = hash(content);
            require(hash.equals(d.path("sha256").path(relative).asString()));
            hashes.put(relative, hash); imported.put(field, JSON.readTree(content));
        }
        var keyword = imported.get("keyword_definition");
        require(keyword.path("original_scenarios").asString().equals(d.path("original_scenarios").asString()));
        var sources = new TreeMap<String, String>();
        var texts = new HashMap<String, String>();
        Path corpus = safe(root, d.path("corpus_manifest").asString()).getParent();
        for (var entry : imported.get("corpus_manifest").path("files").properties()) {
            byte[] content = boundedRead(safe(corpus, entry.getKey()), 1_000_000);
            require(hash(content).equals(entry.getValue().asString()));
            sources.put(entry.getKey(), hash(content));
            texts.put(entry.getKey(), new String(content, java.nio.charset.StandardCharsets.UTF_8));
        }
        var cases = new ArrayList<Scenario>();
        var ids = new HashSet<String>();
        for (var group : List.of(imported.get("original_scenarios"), keyword)) {
            for (var s : group.path("scenarios")) {
                String id = s.path("id").asString();
                require(!id.isBlank() && ids.add(id));
                String query = s.path(s.has("query") ? "query" : "question").asString();
                require(!query.isBlank() && query.length() <= 512);
                var includes = new ArrayList<String>();
                s.path("scope_includes").forEach(n -> includes.add(n.asString()));
                int chunks = s.has("max_chunks") ? s.path("max_chunks").asInt() : keyword.path("max_chunks").asInt();
                var refs = new ArrayList<RetrievalGrading.Reference>();
                require(s.path("expected_evidence").isArray());
                for (var r : s.path("expected_evidence")) {
                    var ref = new RetrievalGrading.Reference(r.path("path").asString(), r.path("start_line").asInt(), r.path("end_line").asInt());
                    require(texts.containsKey(ref.path()) && texts.get(ref.path()).lines().count() >= ref.end());
                    if (r.has("contains")) {
                        String span = String.join("\n", texts.get(ref.path()).lines().skip(ref.start() - 1).limit(ref.end() - ref.start() + 1).toList());
                        require(span.contains(r.path("contains").asString()));
                    }
                    refs.add(ref);
                }
                String variant = !includes.isEmpty() ? "scoped" : chunks != keyword.path("max_chunks").asInt() ? "limited" : "full";
                cases.add(new Scenario(id, query, variant, new Scope(includes, List.of()),
                        new ChunkingConfiguration(keyword.path("max_lines").asInt(), chunks), List.copyOf(refs)));
            }
        }
        var strategies = new ArrayList<RetrievalStrategy>();
        d.path("strategies").forEach(n -> strategies.add(RetrievalStrategy.valueOf(n.asString())));
        var ks = new ArrayList<Integer>(); d.path("top_k").forEach(n -> ks.add(n.asInt()));
        require(cases.size() == 15 && strategies.equals(List.of(RetrievalStrategy.values())) && ks.equals(List.of(5, 12)));
        require(d.path("repository_id").asString().equals("parcel-quotes-v1"));
        var versions = new TreeMap<String, String>();
        for (var strategy : strategies) versions.put(strategy.name(), d.path("strategy_versions").path(strategy.name()).asString());
        require(versions.equals(Map.of("KEYWORD", "postgres-keyword-v1", "VECTOR", "postgres-vector-v1",
                "HYBRID", "postgres-hybrid-rrf-v1:k=60:depth=min(100,max(20,4*topK)):weights=1,1")));
        return new RetrievalComparison(d.path("version").asString(), hash(bytes), hashes,
                d.path("repository_id").asString(), sources, versions, strategies, ks, cases);
    }

    static byte[] boundedRead(Path path, int max) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(max + 1);
            require(bytes.length <= max); return bytes;
        }
    }
    private static Path safe(Path root, String relative) throws IOException {
        require(relative != null && !relative.isBlank() && !Path.of(relative).isAbsolute());
        Path real = root.resolve(relative).toRealPath();
        require(real.startsWith(root.toRealPath())); return real;
    }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid retrieval evaluation definition or artifact");
    }
}
