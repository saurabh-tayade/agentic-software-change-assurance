package dev.changeassurance.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static dev.changeassurance.ingestion.IngestionResult.*;
import static org.junit.jupiter.api.Assertions.*;

class GitSnapshotIngestorTests {
    @TempDir
    Path temporary;
    private Path repository;
    private final GitSnapshotIngestor ingestor = new GitSnapshotIngestor();

    @BeforeEach
    void createRepository() throws IOException {
        repository = Files.createDirectory(temporary.resolve("repository with spaces"));
        git("init", "-q", "--initial-branch=main", "--object-format=sha1");
        write("src/main/java/example/Example.java", "class Example {}\n");
        write("src/test/java/example/ExampleTest.java", "class ExampleTest {}\n");
        write("docs/Guide.md", "# Committed guide\r\n");
        write("README.md", "# Example\n");
        write("schema.sql", "select 1;\n");
        commit();
    }

    @Test
    void discoversArtifactsWithProvenanceAndStableOrderingWithoutWrites() throws Exception {
        Map<String, String> before = repositoryState();
        IngestionResult result = ingest("HEAD");
        assertEquals(git("rev-parse", "HEAD").strip(), result.snapshot().commitSha());
        assertEquals("fictional-example", result.snapshot().repositoryId());
        assertEquals(RepositorySelection.Scope.all(), result.snapshot().scope());
        assertEquals("git-snapshot-v1", result.snapshot().policyVersion());
        assertEquals(List.of("README.md", "docs/Guide.md", "src/main/java/example/Example.java",
                "src/test/java/example/ExampleTest.java"), result.artifacts().stream().map(SourceArtifact::path).toList());
        assertEquals(new Summary(5, 4, 0, 1, 0), result.summary());
        assertEquals(ArtifactType.SOURCE, artifact(result, "src/main/java/example/Example.java").type());
        assertEquals(ArtifactType.TEST, artifact(result, "src/test/java/example/ExampleTest.java").type());
        assertEquals(ArtifactType.DOCUMENTATION, artifact(result, "README.md").type());
        assertEquals(Format.JAVA, artifact(result, "src/main/java/example/Example.java").format());
        assertEquals(Format.MARKDOWN, artifact(result, "README.md").format());
        for (SourceArtifact artifact : result.artifacts()) {
            byte[] expected = Files.readAllBytes(repository.resolve(artifact.path()));
            assertArrayEquals(expected, artifact.contentBytes());
            assertEquals(result.snapshot(), artifact.snapshot());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)), artifact.sha256());
            assertEquals(git("rev-parse", "HEAD:" + artifact.path()).strip(), artifact.gitBlobId());
        }
        assertEquals(Reason.FILE_TYPE, coverage(result, "schema.sql").reason());
        assertEquals(result, ingest("HEAD"));
        assertEquals(before, repositoryState(), "Ingestion must not change worktree or Git metadata bytes/mtimes");
        assertThrows(UnsupportedOperationException.class, () -> result.artifacts().clear());
        assertFalse(result.limitations().isEmpty());
    }

    @Test
    void readsOldCommitsAndIgnoresStagedUnstagedUntrackedAndReplacementContent() throws Exception {
        String old = git("rev-parse", "HEAD").strip();
        write("README.md", "# New committed content\n");
        commit();
        String current = git("rev-parse", "HEAD").strip();
        git("replace", old, current); // Fixture-only mutation; production disables replacement objects.
        write("README.md", "# Staged content\n");
        git("add", "README.md");
        write("README.md", "# Unstaged content\n");
        write("untracked.md", "# Not committed\n");
        Files.delete(repository.resolve("docs/Guide.md"));
        Map<String, String> before = repositoryState();
        assertEquals("# Example\n", artifact(ingest(old), "README.md").content());
        assertEquals(old, ingest(old).snapshot().commitSha());
        assertEquals("# New committed content\n", artifact(ingest("HEAD"), "README.md").content());
        assertEquals("# Committed guide\r\n", artifact(ingest(old), "docs/Guide.md").content());
        assertEquals(5, ingest(old).summary().discovered());
        assertEquals(before, repositoryState());
    }

    @Test
    void resolvesAnnotatedTagsAndRejectsNonCommitObjects() {
        git("tag", "-a", "v1", "-m", "Fixture tag");
        assertEquals(ingest("HEAD"), ingest("v1"));
        assertThrows(IngestionException.class, () -> ingest(git("rev-parse", "HEAD:README.md").strip()));
    }

    @Test
    void rejectsInvalidRepositoriesRevisionsAndNestedDirectories() throws IOException {
        Path empty = Files.createDirectory(temporary.resolve("not a repository"));
        for (Path path : List.of(empty, empty.resolve("missing"), repository.resolve("src"))) {
            assertThrows(IngestionException.class,
                    () -> ingestor.ingest(new RepositorySelection(path, "example", "HEAD")));
        }
        assertThrows(IngestionException.class, () -> ingest("missing-revision"));
        assertThrows(IngestionException.class, () -> ingest("HEAD~99"));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositorySelection(repository, "example", "--help"));
        assertThrows(IllegalArgumentException.class,
                () -> new RepositorySelection(repository, " ", "HEAD"));
    }

    @Test
    void appliesLiteralIncludesExcludesAndDefaultDirectoryExclusions() throws IOException {
        write("target/Generated.java", "class Generated {}\n");
        write("module/node_modules/readme.md", "dependency\n");
        write("src2/Other.java", "class Other {}\n");
        commit();
        var scope = new RepositorySelection.Scope(List.of("src", "docs", "src"), List.of("src/test"));
        IngestionResult result = ingestor.ingest(new RepositorySelection(repository, "example", "HEAD", scope));
        assertEquals(List.of("docs/Guide.md", "src/main/java/example/Example.java"),
                result.artifacts().stream().map(SourceArtifact::path).toList());
        assertEquals(Reason.EXPLICIT_EXCLUSION, coverage(result, "src/test/java/example/ExampleTest.java").reason());
        assertEquals(Reason.OUTSIDE_SCOPE, coverage(result, "src2/Other.java").reason());
        assertEquals(new Summary(8, 2, 6, 0, 0), result.summary());
        assertEquals(Reason.GENERATED_OR_DEPENDENCY_DIRECTORY, coverage(ingest("HEAD"), "target/Generated.java").reason());
        assertEquals(Reason.GENERATED_OR_DEPENDENCY_DIRECTORY,
                coverage(ingest("HEAD"), "module/node_modules/readme.md").reason());
        var exact = new RepositorySelection.Scope(List.of("README.md"), List.of());
        assertEquals(1, ingestor.ingest(new RepositorySelection(repository, "example", "HEAD", exact)).artifacts().size());
        var missing = new RepositorySelection.Scope(List.of("absent"), List.of());
        assertEquals(new Summary(8, 0, 8, 0, 0),
                ingestor.ingest(new RepositorySelection(repository, "example", "HEAD", missing)).summary());
    }

    @Test
    void rejectsUnsafeScopePathsBeforeRunningGit() {
        for (String path : List.of("../outside", "/absolute", "a/../b", "a//b", "a/", ".", ".git/config",
                "C:/outside", "a\\b", "a\nb", ":(glob)**", "")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new RepositorySelection.Scope(List.of(path), List.of()), path);
            assertThrows(IllegalArgumentException.class,
                    () -> new RepositorySelection.Scope(List.of(), List.of(path)), path);
        }
    }

    @Test
    void handlesUnusualFilenamesWithoutGitQuotingOrPathspecInterpretation() throws IOException {
        write("docs/a space é.md", "\uFEFF# UTF-8 BOM retained\r\n");
        write("-option.md", "literal file\n");
        write("docs/line\nbreak.md", "unsafe path\n");
        commit();
        IngestionResult result = ingest("HEAD");
        assertArrayEquals(Files.readAllBytes(repository.resolve("docs/a space é.md")),
                artifact(result, "docs/a space é.md").contentBytes());
        assertEquals("literal file\n", artifact(result, "-option.md").content());
        assertEquals(Reason.UNSAFE_PATH, coverage(result, "docs/line\nbreak.md").reason());
        assertEquals(Disposition.FAILED, coverage(result, "docs/line\nbreak.md").disposition());
    }

    @Test
    void doesNotFollowTreeSymlinksOrSubmodules() throws IOException {
        Path outside = temporary.resolve("outside.md");
        Files.writeString(outside, "must not be ingested\n");
        Files.createSymbolicLink(repository.resolve("linked.md"), outside);
        git("add", "linked.md");
        git("update-index", "--add", "--cacheinfo", "160000," + git("rev-parse", "HEAD").strip() + ",nested-module");
        git("commit", "-q", "-m", "Links fixture");
        IngestionResult result = ingest("HEAD");
        assertEquals(Reason.SYMLINK, coverage(result, "linked.md").reason());
        assertEquals(Reason.SUBMODULE, coverage(result, "nested-module").reason());
        assertEquals(Disposition.UNSUPPORTED, coverage(result, "nested-module").disposition());
        assertEquals(new Summary(7, 4, 0, 3, 0), result.summary());
        assertTrue(result.artifacts().stream().noneMatch(a -> a.content().contains("must not be ingested")));
        assertEquals("must not be ingested\n", Files.readString(outside));
    }

    @Test
    void excludesOversizedBlobsAndReportsInvalidEncodingAndBinaryContent() throws IOException {
        Files.write(repository.resolve("large.md"), new byte[1024 * 1024 + 1]);
        Files.write(repository.resolve("invalid.md"), new byte[]{(byte) 0xc3, 0x28});
        Files.write(repository.resolve("utf16.md"), new byte[]{(byte) 0xff, (byte) 0xfe, 0x41, 0});
        Files.write(repository.resolve("binary.java"), new byte[]{0x41, 0, 0x42});
        write("empty.md", "");
        commit();
        IngestionResult result = ingest("HEAD");
        assertEquals(Reason.FILE_SIZE_LIMIT, coverage(result, "large.md").reason());
        assertEquals(Disposition.EXCLUDED, coverage(result, "large.md").disposition());
        assertEquals(Reason.INVALID_UTF8, coverage(result, "invalid.md").reason());
        assertEquals(Reason.INVALID_UTF8, coverage(result, "utf16.md").reason());
        assertEquals(Reason.BINARY_CONTENT, coverage(result, "binary.java").reason());
        assertEquals("", artifact(result, "empty.md").content());
        assertEquals(new Summary(10, 5, 1, 4, 0), result.summary());
    }

    @Test
    void boundsAggregateRetainedContentDeterministically() throws IOException {
        byte[] content = new byte[1024 * 1024];
        Arrays.fill(content, (byte) 'a');
        for (int index = 0; index < 17; index++) {
            Files.write(repository.resolve("a%02d.md".formatted(index)), content);
        }
        commit();
        IngestionResult result = ingest("HEAD");
        assertTrue(result.artifacts().stream().mapToLong(a -> a.contentBytes().length).sum() <= 16 * 1024 * 1024);
        assertEquals(Reason.TOTAL_CONTENT_LIMIT, coverage(result, "a16.md").reason());
        assertEquals(result, ingest("HEAD"));
    }

    @Test
    void missingPromisorBlobIsAFailureAndDoesNotFetchOrWrite() throws Exception {
        String blob = git("rev-parse", "HEAD:README.md").strip();
        Files.delete(repository.resolve(".git/objects/" + blob.substring(0, 2) + "/" + blob.substring(2)));
        git("config", "remote.origin.promisor", "true");
        git("config", "remote.origin.url", temporary.resolve("unavailable-remote").toString());
        Map<String, String> before = repositoryState();
        IngestionResult result = ingest("HEAD");
        CoverageEntry failure = coverage(result, "README.md");
        assertEquals(Disposition.FAILED, failure.disposition());
        assertEquals(Reason.OBJECT_READ_FAILED, failure.reason());
        assertTrue(failure.detail().contains("Git cat-file: exit"), failure.detail());
        assertEquals(new Summary(5, 3, 0, 1, 1), result.summary());
        assertEquals(before, repositoryState());
    }

    @Test
    void missingCommitTreeFailsInsteadOfReturningIncompleteInventory() throws IOException {
        String tree = git("rev-parse", "HEAD^{tree}").strip();
        Files.delete(repository.resolve(".git/objects/" + tree.substring(0, 2) + "/" + tree.substring(2)));
        assertThrows(IngestionException.class, () -> ingest("HEAD"));
    }

    @Test
    void rejectsAlternateObjectStoresAndLinkedWorktrees() throws IOException {
        Files.writeString(repository.resolve(".git/objects/info/alternates"), temporary.toString());
        assertTrue(assertThrows(IngestionException.class, () -> ingest("HEAD")).getMessage().contains("alternate"));
        Path linked = Files.createDirectory(temporary.resolve("linked"));
        Files.writeString(linked.resolve(".git"), "gitdir: " + repository.resolve(".git"));
        assertThrows(IngestionException.class,
                () -> ingestor.ingest(new RepositorySelection(linked, "example", "HEAD")));
    }

    @Test
    void readsSha256Repositories() throws IOException {
        repository = Files.createDirectory(temporary.resolve("sha256"));
        git("init", "-q", "--initial-branch=main", "--object-format=sha256");
        write("README.md", "# SHA256 repository\n");
        commit();
        IngestionResult result = ingest("HEAD");
        assertEquals(64, result.snapshot().commitSha().length());
        assertEquals(64, artifact(result, "README.md").gitBlobId().length());
        assertEquals("# SHA256 repository\n", artifact(result, "README.md").content());
    }

    @Test
    void ingestsTheUnmodifiedControlledCorpusFromATemporaryCommit() throws IOException {
        Path corpus = Path.of("evaluation/corpora/parcel-quotes/v1");
        try (var paths = Files.walk(corpus)) {
            for (Path source : paths.filter(Files::isRegularFile).toList()) {
                Path destination = repository.resolve("corpus").resolve(corpus.relativize(source));
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination);
            }
        }
        commit();
        var scope = new RepositorySelection.Scope(List.of("corpus"), List.of());
        IngestionResult result = ingestor.ingest(new RepositorySelection(repository, "parcel-quotes", "HEAD", scope));
        assertEquals(5, result.artifacts().size());
        assertEquals(ArtifactType.TEST,
                artifact(result, "corpus/tests/example/quotes/QuotePolicyTest.java").type());
        for (SourceArtifact artifact : result.artifacts()) {
            assertArrayEquals(Files.readAllBytes(repository.resolve(artifact.path())), artifact.contentBytes());
        }
    }

    @Test
    void rejectsAnOversizedTreeAndAcceptsAnEmptyTree() throws IOException {
        String blob = git("rev-parse", "HEAD:README.md").strip();
        Path specification = temporary.resolve("tree-specification");
        var rows = new StringBuilder();
        for (int index = 0; index < 10_001; index++) {
            rows.append("100644 blob ").append(blob).append("\tfile-").append(index).append(".md\n");
        }
        Files.writeString(specification, rows);
        String tree = gitInput(specification, "mktree").strip();
        String commit = git("commit-tree", tree, "-m", "Large tree fixture").strip();
        assertTrue(assertThrows(IngestionException.class, () -> ingest(commit)).getMessage().contains("10000 entries"));
        Files.writeString(specification, "");
        String emptyTree = gitInput(specification, "mktree").strip();
        String emptyCommit = git("commit-tree", emptyTree, "-m", "Empty tree fixture").strip();
        assertEquals(new Summary(0, 0, 0, 0, 0), ingest(emptyCommit).summary());
    }

    @Test
    void rejectsNonUtf8TreePathsWithoutInventingReplacementPaths() throws IOException {
        String blob = git("rev-parse", "HEAD:README.md").strip();
        byte[] header = ("100644 blob " + blob + "\t").getBytes(StandardCharsets.US_ASCII);
        byte[] row = Arrays.copyOf(header, header.length + 5);
        row[header.length] = (byte) 0xff;
        row[header.length + 1] = '.';
        row[header.length + 2] = 'm';
        row[header.length + 3] = 'd';
        Path specification = temporary.resolve("invalid-tree-path");
        Files.write(specification, row);
        String tree = gitInput(specification, "mktree", "-z").strip();
        String commit = git("commit-tree", tree, "-m", "Invalid path encoding fixture").strip();
        assertTrue(assertThrows(IngestionException.class, () -> ingest(commit)).getMessage().contains("not valid UTF-8"));
    }

    @Test
    void doesNotExecuteConfiguredHooksFiltersOrTextConversion() throws Exception {
        Path marker = temporary.resolve("executed");
        Path hooks = Files.createDirectory(temporary.resolve("hooks"));
        Path script = hooks.resolve("probe");
        Files.writeString(script, "#!/bin/sh\necho executed > '" + marker + "'\n");
        assertTrue(script.toFile().setExecutable(true));
        Files.copy(script, hooks.resolve("post-checkout"));
        Files.copy(script, hooks.resolve("post-index-change"));
        write(".gitattributes", "*.md filter=probe diff=probe\n");
        commit();
        git("config", "core.hooksPath", hooks.toString());
        git("config", "core.fsmonitor", script.toString());
        git("config", "filter.probe.clean", script.toString());
        git("config", "filter.probe.smudge", script.toString());
        git("config", "diff.probe.textconv", script.toString());
        Map<String, String> before = repositoryState();
        assertEquals("# Example\n", artifact(ingest("HEAD"), "README.md").content());
        assertFalse(Files.exists(marker));
        assertEquals(before, repositoryState());
    }

    private IngestionResult ingest(String revision) {
        return ingestor.ingest(new RepositorySelection(repository, "fictional-example", revision));
    }

    private static SourceArtifact artifact(IngestionResult result, String path) {
        return result.artifacts().stream().filter(artifact -> artifact.path().equals(path)).findFirst().orElseThrow();
    }

    private static CoverageEntry coverage(IngestionResult result, String path) {
        return result.coverage().stream().filter(entry -> entry.path().equals(path)).findFirst().orElseThrow();
    }

    private void write(String path, String content) throws IOException {
        Files.createDirectories(repository.resolve(path).getParent());
        Files.writeString(repository.resolve(path), content);
    }

    private void commit() {
        git("add", "--all");
        git("commit", "-q", "-m", "Controlled test fixture");
    }

    private String git(String... arguments) {
        return executeFixtureGit(gitBuilder(arguments));
    }

    private String gitInput(Path input, String... arguments) {
        return executeFixtureGit(gitBuilder(arguments).redirectInput(input.toFile()));
    }

    private ProcessBuilder gitBuilder(String... arguments) {
        var command = new ArrayList<>(List.of("git", "-c", "user.name=Fixture Author",
                "-c", "user.email=fixture@example.invalid", "-c", "commit.gpgsign=false",
                "-c", "tag.gpgsign=false", "-c", "core.hooksPath=/dev/null", "-c", "init.templateDir="));
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command).directory(repository.toFile());
    }

    private String executeFixtureGit(ProcessBuilder builder) {
        return new String(GitProcess.execute(builder, Duration.ofSeconds(10), 4 * 1024 * 1024), StandardCharsets.UTF_8);
    }

    private Map<String, String> repositoryState() throws IOException {
        var state = new TreeMap<String, String>();
        try (var paths = Files.walk(repository)) {
            for (Path path : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(p)).toList()) {
                String content = Files.isSymbolicLink(path) ? Files.readSymbolicLink(path).toString()
                        : HexFormat.of().formatHex(Files.readAllBytes(path));
                state.put(repository.relativize(path).toString(), content + "@"
                        + Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS));
            }
        }
        return state;
    }
}
