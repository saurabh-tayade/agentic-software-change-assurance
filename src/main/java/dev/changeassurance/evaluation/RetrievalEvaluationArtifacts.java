package dev.changeassurance.evaluation;

import dev.changeassurance.persistence.*;
import dev.changeassurance.retrieval.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static dev.changeassurance.evaluation.RetrievalComparison.require;
import static dev.changeassurance.evaluation.RetrievalEvaluationRunner.EvaluationFailure;

/** Offline grading uses only captured execution evidence. No database or model access. */
public final class RetrievalEvaluationArtifacts {
    private RetrievalEvaluationArtifacts() { }
    public record CaseGrade(String scenarioId, RetrievalStrategy strategy, int topK, String failure,
                            RetrievalGrading.Grade metrics) { }
    public record Aggregate(RetrievalStrategy strategy, int topK, int scheduled, int successful, int failed,
                            int emptyExpectationSuccesses, int qualityDenominator, Double macroRecallAtK, Double mrrAtK) { }
    public record Grades(int schemaVersion, String runId, String configurationId, String gradingVersion,
                         List<CaseGrade> cases, List<Aggregate> aggregates) { }

    public static Grades grade(RetrievalEvaluationRun run) {
        require(run.schemaVersion() == 1 && run.configurationId().equals(RetrievalEvaluationRunner.configurationId(
                run.definition(), run.publicationIds(), run.requestedProfileId(), run.executionMode())));
        var grades = new ArrayList<CaseGrade>();
        var seen = new HashSet<String>();
        for (var execution : run.executions()) {
            var scenario = run.definition().scenarios().stream().filter(s -> s.id().equals(execution.scenarioId())).findFirst().orElseThrow();
            require(run.definition().strategies().contains(execution.requestedStrategy()) && run.definition().topK().contains(execution.topK()));
            require(seen.add(execution.requestedStrategy() + ":" + execution.topK() + ":" + scenario.id()));
            require(Objects.equals(execution.publicationId(), run.publicationIds().get(scenario.publicationVariant())));
            require(Objects.equals(execution.profileId(), execution.requestedStrategy() == RetrievalStrategy.KEYWORD ? null : run.requestedProfileId()));
            require(execution.retrievalDurationMs() == null || Double.isFinite(execution.retrievalDurationMs()) && execution.retrievalDurationMs() >= 0);
            RetrievalGrading.Grade metric = null;
            if (execution.failure() == null) {
                require(execution.retrievalDurationMs() != null);
                require(execution.requestedStrategy() == RetrievalStrategy.KEYWORD || run.embeddingProfile() != null
                        && run.embeddingProfile().id().equals(run.requestedProfileId()));
                var publication = run.publications().get(scenario.publicationVariant());
                require(publication != null && publication.id().equals(execution.publicationId()));
                validatePublication(run.definition(), scenario, publication);
                validateResult(scenario, execution.requestedStrategy(), execution.topK(), execution.profileId(), publication, execution.result());
                metric = RetrievalGrading.grade(scenario.expected(), execution.result().matches().stream()
                        .map(m -> new RetrievalGrading.Reference(m.evidence().filePath(), m.evidence().startLine(), m.evidence().endLine())).toList());
            }
            grades.add(new CaseGrade(scenario.id(), execution.requestedStrategy(), execution.topK(), execution.failure(), metric));
        }
        require(seen.size() == run.definition().strategies().size() * run.definition().topK().size() * run.definition().scenarios().size());
        var aggregates = new ArrayList<Aggregate>();
        for (var strategy : run.definition().strategies()) for (int k : run.definition().topK()) {
            var group = grades.stream().filter(g -> g.strategy() == strategy && g.topK() == k).toList();
            var successful = group.stream().filter(g -> g.failure() == null).toList();
            var eligible = successful.stream().map(CaseGrade::metrics).filter(g -> g.expectedReferences() > 0).toList();
            aggregates.add(new Aggregate(strategy, k, group.size(), successful.size(), group.size() - successful.size(),
                    successful.size() - eligible.size(), eligible.size(), eligible.isEmpty() ? null :
                    eligible.stream().mapToDouble(RetrievalGrading.Grade::recall).average().orElseThrow(), eligible.isEmpty() ? null :
                    eligible.stream().mapToDouble(RetrievalGrading.Grade::reciprocalRank).average().orElseThrow()));
        }
        return new Grades(1, run.runId(), run.configurationId(), "source-span-union-first-overlap-v1", List.copyOf(grades), List.copyOf(aggregates));
    }

    public static Path write(Path outputRoot, RetrievalEvaluationRun run) throws IOException {
        var grades = grade(run);
        require(run.runId().matches("[0-9a-f-]{36}"));
        Files.createDirectories(outputRoot);
        Path directory = Files.createDirectory(outputRoot.resolve(run.runId())); // Refuse existing runs, including symlinks.
        writeNew(directory.resolve("execution.json"), run);
        writeNew(directory.resolve("grades.json"), grades);
        return directory;
    }
    public static RetrievalEvaluationRun read(Path path) throws IOException {
        return RetrievalComparison.JSON.readValue(RetrievalComparison.boundedRead(path, 64 * 1024 * 1024), RetrievalEvaluationRun.class);
    }
    public static void writeNew(Path path, Object value) throws IOException {
        byte[] bytes = (RetrievalComparison.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
        require(bytes.length <= 64 * 1024 * 1024);
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    static void validatePublication(RetrievalComparison d, RetrievalComparison.Scenario scenario, PersistedPublication p) {
        if (p == null || !p.ingestion().snapshot().repositoryId().equals(d.repositoryId())
                || !p.ingestion().snapshot().equals(p.processing().snapshot())
                || !p.ingestion().snapshot().scope().equals(scenario.scope())
                || !p.processing().configuration().equals(scenario.configuration())) throw new EvaluationFailure("PUBLICATION_MISMATCH");
        var expectedPaths = new TreeSet<String>();
        d.sourceHashes().keySet().stream().filter(path -> scenario.scope().includes().isEmpty()
                || scenario.scope().includes().stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"))).forEach(expectedPaths::add);
        var actualPaths = new TreeSet<String>();
        for (var source : p.ingestion().artifacts()) {
            if (!actualPaths.add(source.path()) || !Objects.equals(d.sourceHashes().get(source.path()), source.sha256())
                    || !source.sha256().equals(RetrievalComparison.hash(source.contentBytes()))
                    || !source.snapshot().equals(p.ingestion().snapshot())) throw new EvaluationFailure("CORPUS_MISMATCH");
        }
        if (!actualPaths.equals(expectedPaths)) throw new EvaluationFailure("CORPUS_MISMATCH");
    }

    static void validateResult(RetrievalComparison.Scenario scenario, RetrievalStrategy strategy, int k, String profile,
                               PersistedPublication p, RetrievalResult result) {
        String expectedStrategy = switch (strategy) {
            // Freeze artifact-v1 semantics for offline replay even if future retrievers change.
            case KEYWORD -> "postgres-keyword-v1";
            case VECTOR -> "postgres-vector-v1:profile=" + profile;
            case HYBRID -> "postgres-hybrid-rrf-v1:k=60:depth=" + Math.min(100, Math.max(20, 4 * k)) + ":weights=1,1:profile=" + profile;
        };
        if (result == null || !result.strategy().equals(expectedStrategy) || result.topK() != k || !result.query().equals(scenario.query()))
            throw new EvaluationFailure("STRATEGY_CONFIGURATION_MISMATCH");
        var processing = p.processing();
        var metadata = new PublicationMetadata(p.ingestion().artifacts().stream().map(a -> a.path()).toList(),
                processing.configuration(), processing.status(), processing.artifacts(), processing.ingestionCoverage(),
                processing.ingestionLimitations(), processing.ingestionSummary(), processing.processingLimitations());
        if (!result.publication().equals(new PublicationSummary(p.id(), p.snapshotId(), p.derivationId(), p.ingestion().snapshot(), metadata)))
            throw new EvaluationFailure("PUBLICATION_MISMATCH");
        if (result.matches().size() > k) throw new EvaluationFailure("INVALID_RANKING");
        var seen = new HashSet<String>();
        RetrievalResult.Match previous = null;
        int rank = 1;
        for (var match : result.matches()) {
            if (match.evidence() == null || !seen.add(match.evidence().id()) || match.rank() != rank++ || !Double.isFinite(match.score())
                    || previous != null && compare(previous, match) > 0) throw new EvaluationFailure("INVALID_RANKING");
            if (!processing.chunks().contains(match.evidence()) || !p.ingestion().snapshot().equals(match.evidence().snapshot()))
                throw new EvaluationFailure("EVIDENCE_PROVENANCE_MISMATCH");
            PublicationIntegrity.verifyChunk(match.evidence());
            var source = p.ingestion().artifacts().stream().filter(a -> a.path().equals(match.evidence().filePath())).findFirst().orElseThrow();
            if (!source.sha256().equals(match.evidence().sourceHash()) || !source.gitBlobId().equals(match.evidence().gitBlobId()))
                throw new EvaluationFailure("EVIDENCE_PROVENANCE_MISMATCH");
            var lines = source.content().split("(?<=\n)", -1);
            int start = match.evidence().startLine(), end = match.evidence().endLine();
            if (start < 1 || end < start || end > lines.length || !String.join("", Arrays.copyOfRange(lines, start - 1, end)).equals(match.evidence().content()))
                throw new EvaluationFailure("EVIDENCE_PROVENANCE_MISMATCH");
            previous = match;
        }
    }
    private static int compare(RetrievalResult.Match a, RetrievalResult.Match b) {
        return Comparator.comparingDouble(RetrievalResult.Match::score).reversed()
                .thenComparing(m -> m.evidence().filePath(), (String x, String y) -> Arrays.compareUnsigned(x.getBytes(StandardCharsets.UTF_8), y.getBytes(StandardCharsets.UTF_8)))
                .thenComparingInt(m -> m.evidence().startLine()).thenComparing(m -> m.evidence().id()).compare(a, b);
    }
}
