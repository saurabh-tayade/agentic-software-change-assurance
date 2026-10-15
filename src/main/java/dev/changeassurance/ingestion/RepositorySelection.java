package dev.changeassurance.ingestion;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Caller-supplied identity and literal repository-relative scope; no Git pathspecs. */
public record RepositorySelection(Path repositoryPath, String repositoryId, String revision, Scope scope) {
    public RepositorySelection {
        Objects.requireNonNull(repositoryPath, "repositoryPath");
        requireText(repositoryId, "repositoryId");
        requireText(revision, "revision");
        if (revision.startsWith("-") || revision.length() > 1024
                || revision.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("revision must be a non-option Git revision without control characters");
        }
        Objects.requireNonNull(scope, "scope");
    }

    public RepositorySelection(Path repositoryPath, String repositoryId, String revision) {
        this(repositoryPath, repositoryId, revision, Scope.all());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be nonempty");
        }
    }

    static boolean safePath(String path) {
        return path != null && !path.isEmpty() && !path.startsWith("/")
                && !path.contains("\\") && !path.contains(":")
                && path.chars().noneMatch(Character::isISOControl)
                && Arrays.stream(path.split("/", -1)).noneMatch(part ->
                        part.isEmpty() || part.equals(".") || part.equals("..") || part.equalsIgnoreCase(".git"));
    }

    public record Scope(List<String> includes, List<String> excludes) {
        public Scope {
            includes = canonical(includes);
            excludes = canonical(excludes);
        }

        public static Scope all() {
            return new Scope(List.of(), List.of());
        }

        private static List<String> canonical(List<String> paths) {
            Objects.requireNonNull(paths, "scope paths");
            if (paths.size() > 100) {
                throw new IllegalArgumentException("scope allows at most 100 prefixes per list");
            }
            for (String path : paths) {
                if (!safePath(path) || path.length() > 4096) {
                    throw new IllegalArgumentException("unsafe repository-relative scope path: " + path);
                }
            }
            return paths.stream().distinct().sorted().toList();
        }

        boolean includes(String path) {
            return includes.isEmpty() || includes.stream().anyMatch(prefix -> matches(path, prefix));
        }

        boolean excludes(String path) {
            return excludes.stream().anyMatch(prefix -> matches(path, prefix));
        }

        private static boolean matches(String path, String prefix) {
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
    }
}
