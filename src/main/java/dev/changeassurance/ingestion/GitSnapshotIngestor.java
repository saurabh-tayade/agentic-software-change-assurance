package dev.changeassurance.ingestion;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static dev.changeassurance.ingestion.IngestionResult.*;

/** Reads committed Git objects only. No checkout, filters, parsing, or repository writes. */
@Component
public final class GitSnapshotIngestor {
    private static final int MAX_TREE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 16 * 1024 * 1024;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SNAPSHOT_TIMEOUT = Duration.ofSeconds(60);
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            "target", "build", "out", "node_modules", "vendor", ".gradle");
    private static final List<String> LIMITATIONS = List.of(
            "Committed objects only; staged, unstaged, and untracked changes are ignored.",
            "Java/Markdown discovery only; test classification is a path heuristic, not syntax analysis.",
            "Coverage counts tree leaves, including gitlinks; submodule contents are not inspected.",
            "Policy git-snapshot-v1: strict UTF-8; binary control characters unsupported; fixed resource limits.");

    public IngestionResult ingest(RepositorySelection selection) {
        long deadline = System.nanoTime() + SNAPSHOT_TIMEOUT.toNanos();
        Path repository = repositoryRoot(selection.repositoryPath());
        String commit = text(run(repository, deadline, 1024, "rev-parse", "--verify", "--end-of-options",
                selection.revision() + "^{commit}")).strip();
        if (!objectId(commit)) {
            throw new IngestionException("Revision did not resolve to a full Git commit object ID");
        }
        var snapshot = new Snapshot(selection.repositoryId(), commit, selection.scope(), "git-snapshot-v1");
        List<TreeEntry> entries = tree(run(repository, deadline, MAX_TREE_BYTES,
                "ls-tree", "-r", "-z", "--full-tree", commit));
        var artifacts = new ArrayList<SourceArtifact>();
        var coverage = new ArrayList<CoverageEntry>();
        int retainedBytes = 0;
        for (TreeEntry entry : entries) {
            checkDeadline(deadline);
            CoverageEntry skipped = selectionOutcome(entry, selection.scope());
            if (skipped != null) {
                coverage.add(skipped);
                continue;
            }
            byte[] bytes;
            try {
                String sizeText = text(run(repository, deadline, 64, "cat-file", "-s", entry.objectId())).strip();
                long size = Long.parseLong(sizeText);
                if (size < 0) {
                    throw new IngestionException("Negative blob size");
                }
                if (size > MAX_FILE_BYTES) {
                    coverage.add(outcome(entry, Disposition.EXCLUDED, Reason.FILE_SIZE_LIMIT,
                            "Blob exceeds " + MAX_FILE_BYTES + " bytes"));
                    continue;
                }
                if (size > MAX_TOTAL_BYTES - retainedBytes) {
                    coverage.add(outcome(entry, Disposition.EXCLUDED, Reason.TOTAL_CONTENT_LIMIT,
                            "Retained content would exceed " + MAX_TOTAL_BYTES + " bytes"));
                    continue;
                }
                bytes = run(repository, deadline, MAX_FILE_BYTES, "cat-file", "blob", entry.objectId());
                if (bytes.length != size || !blobId(bytes, entry.objectId().length()).equals(entry.objectId())) {
                    throw new IngestionException("Blob length or Git object digest mismatch");
                }
            } catch (IngestionException | NumberFormatException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IngestionException("Snapshot ingestion interrupted", exception);
                }
                checkDeadline(deadline);
                coverage.add(outcome(entry, Disposition.FAILED, Reason.OBJECT_READ_FAILED, exception.getMessage()));
                continue;
            }
            String content;
            try {
                content = decode(bytes);
            } catch (CharacterCodingException exception) {
                coverage.add(outcome(entry, Disposition.UNSUPPORTED, Reason.INVALID_UTF8,
                        "Blob is not valid UTF-8; no replacement decoding was performed"));
                continue;
            }
            if (content.codePoints().anyMatch(value -> Character.isISOControl(value)
                    && value != '\t' && value != '\n' && value != '\r')) {
                coverage.add(outcome(entry, Disposition.UNSUPPORTED, Reason.BINARY_CONTENT,
                        "Text contains binary/control characters other than TAB, LF, or CR"));
                continue;
            }
            Format format = entry.path().endsWith(".java") ? Format.JAVA : Format.MARKDOWN;
            ArtifactType type = format == Format.MARKDOWN ? ArtifactType.DOCUMENTATION
                    : isTest(entry.path()) ? ArtifactType.TEST : ArtifactType.SOURCE;
            artifacts.add(new SourceArtifact(snapshot, entry.path(), type, format,
                    entry.objectId(), digest("SHA-256", bytes), content));
            retainedBytes += bytes.length;
        }
        checkDeadline(deadline);
        return new IngestionResult(snapshot, artifacts, coverage, LIMITATIONS);
    }

    private static Path repositoryRoot(Path selected) {
        try {
            Path root = selected.toRealPath();
            Path git = root.resolve(".git");
            if (!Files.isDirectory(root) || !Files.isDirectory(git, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isReadable(git)) {
                throw new IngestionException("Select a readable repository root with an ordinary .git directory; "
                        + "bare repositories, subdirectories, and linked worktrees are unsupported");
            }
            for (String relative : List.of("objects", "objects/info", "refs")) {
                if (Files.isSymbolicLink(git.resolve(relative))) {
                    throw new IngestionException("Linked Git metadata is unsupported: " + relative);
                }
            }
            for (String relative : List.of("commondir", "objects/info/alternates", "objects/info/http-alternates")) {
                if (Files.exists(git.resolve(relative), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IngestionException("Shared/alternate object stores are unsupported: " + relative);
                }
            }
            return root;
        } catch (IOException exception) {
            throw new IngestionException("Repository does not exist or is inaccessible: " + selected, exception);
        }
    }

    private static CoverageEntry selectionOutcome(TreeEntry entry, RepositorySelection.Scope scope) {
        String path = entry.path();
        if (!RepositorySelection.safePath(path)) {
            return outcome(entry, Disposition.FAILED, Reason.UNSAFE_PATH, "Unsafe repository-relative tree path");
        }
        if (!scope.includes(path)) {
            return outcome(entry, Disposition.EXCLUDED, Reason.OUTSIDE_SCOPE, "Outside included path prefixes");
        }
        if (scope.excludes(path)) {
            return outcome(entry, Disposition.EXCLUDED, Reason.EXPLICIT_EXCLUSION, "Matches excluded path prefix");
        }
        String[] parts = path.split("/");
        if (Arrays.stream(parts, 0, parts.length - 1).anyMatch(EXCLUDED_DIRECTORIES::contains)) {
            return outcome(entry, Disposition.EXCLUDED, Reason.GENERATED_OR_DEPENDENCY_DIRECTORY,
                    "Matches a default generated/dependency directory");
        }
        if (entry.mode().equals("120000")) {
            return outcome(entry, Disposition.UNSUPPORTED, Reason.SYMLINK, "Symbolic link is not followed");
        }
        if (entry.mode().equals("160000")) {
            return outcome(entry, Disposition.UNSUPPORTED, Reason.SUBMODULE, "Gitlink is not traversed");
        }
        if ((!entry.mode().equals("100644") && !entry.mode().equals("100755"))
                || (!path.endsWith(".java") && !path.endsWith(".md"))) {
            return outcome(entry, Disposition.UNSUPPORTED, Reason.FILE_TYPE, "Only regular .java and .md files supported");
        }
        return null;
    }

    private static boolean isTest(String path) {
        String[] parts = path.split("/");
        return Arrays.stream(parts, 0, parts.length - 1).anyMatch(part -> part.equals("test") || part.equals("tests"));
    }

    private static CoverageEntry outcome(TreeEntry entry, Disposition disposition, Reason reason, String detail) {
        return new CoverageEntry(entry.path(), disposition, reason, detail);
    }

    private static List<TreeEntry> tree(byte[] bytes) {
        String listing = text(bytes);
        if (!listing.isEmpty() && !listing.endsWith("\0")) {
            throw new IngestionException("Truncated Git tree listing");
        }
        var entries = new ArrayList<TreeEntry>();
        for (String row : listing.split("\0")) {
            if (row.isEmpty()) {
                continue;
            }
            int tab = row.indexOf('\t');
            String[] fields = tab < 0 ? new String[0] : row.substring(0, tab).split(" ");
            if (fields.length != 3 || !fields[0].matches("[0-7]{6}") || !objectId(fields[2])
                    || (!fields[1].equals("blob") && !fields[1].equals("commit"))) {
                throw new IngestionException("Malformed Git tree entry");
            }
            entries.add(new TreeEntry(fields[0], fields[2], row.substring(tab + 1)));
            if (entries.size() > MAX_ENTRIES) {
                throw new IngestionException("Tree exceeds " + MAX_ENTRIES + " entries; no complete inventory returned");
            }
        }
        entries.sort(Comparator.comparing(TreeEntry::path));
        return entries;
    }

    private static byte[] run(Path repository, long deadline, int limit, String... arguments) {
        checkDeadline(deadline);
        Duration timeout = Duration.ofNanos(Math.min(COMMAND_TIMEOUT.toNanos(), deadline - System.nanoTime()));
        return GitProcess.read(repository, timeout, limit, arguments);
    }

    private static void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted()) {
            throw new IngestionException("Snapshot ingestion interrupted");
        }
        if (System.nanoTime() >= deadline) {
            throw new IngestionException("Snapshot exceeds " + SNAPSHOT_TIMEOUT + "; no complete result returned");
        }
    }

    private static boolean objectId(String value) {
        return value.matches("[0-9a-f]{40}|[0-9a-f]{64}");
    }

    private static String text(byte[] bytes) {
        try {
            return decode(bytes);
        } catch (CharacterCodingException exception) {
            throw new IngestionException("Git metadata/path is not valid UTF-8", exception);
        }
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String blobId(byte[] bytes, int idLength) {
        byte[] header = ("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII);
        MessageDigest digest = messageDigest(idLength == 40 ? "SHA-1" : "SHA-256");
        digest.update(header);
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static String digest(String algorithm, byte[] bytes) {
        return HexFormat.of().formatHex(messageDigest(algorithm).digest(bytes));
    }

    private static MessageDigest messageDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required JDK digest unavailable", exception);
        }
    }

    private record TreeEntry(String mode, String objectId, String path) { }
}
