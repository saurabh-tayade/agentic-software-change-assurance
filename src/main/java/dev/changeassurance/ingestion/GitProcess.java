package dev.changeassurance.ingestion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded process transport used only by the three read-only plumbing commands. */
final class GitProcess {
    private GitProcess() { }

    static byte[] read(Path repository, Duration timeout, int outputLimit, String... arguments) {
        if (!List.of("rev-parse", "ls-tree", "cat-file").contains(arguments[0])) {
            throw new IllegalArgumentException("Not an ingestion Git command");
        }
        var command = new ArrayList<>(List.of("git", "--no-pager", "--no-replace-objects",
                "--no-lazy-fetch", "--no-optional-locks", "--git-dir=" + repository.resolve(".git"),
                "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false",
                "-c", "gc.auto=0", "-c", "maintenance.auto=false"));
        command.addAll(List.of(arguments));
        try {
            return execute(new ProcessBuilder(command).directory(repository.toFile()), timeout, outputLimit);
        } catch (IngestionException exception) {
            throw new IngestionException("Git " + arguments[0] + ": " + exception.getMessage(), exception);
        }
    }

    // Package-private to exercise timeout, output overflow, and cleanup with a local JVM child.
    static byte[] execute(ProcessBuilder builder, Duration timeout, int outputLimit) {
        var environment = builder.environment();
        environment.keySet().removeIf(key -> key.startsWith("GIT_") || key.equals("GCM_INTERACTIVE"));
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_ALLOW_PROTOCOL", "");
        environment.put("LC_ALL", "C");
        Process process = null;
        var readers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            process = builder.start();
            process.getOutputStream().close();
            Process child = process;
            Future<byte[]> stdout = readers.submit(() -> readBounded(child, child.getInputStream(), outputLimit));
            Future<byte[]> stderr = readers.submit(() -> readBounded(child, child.getErrorStream(), 16 * 1024));
            if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) {
                throw new IngestionException("process timed out after " + timeout);
            }
            byte[] output = stdout.get(remaining(deadline), TimeUnit.NANOSECONDS);
            byte[] error = stderr.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if (process.exitValue() != 0) {
                String diagnostic = new String(error, StandardCharsets.UTF_8).strip();
                throw new IngestionException("exit " + process.exitValue() + ": " + diagnostic);
            }
            return output;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IngestionException("process interrupted", exception);
        } catch (TimeoutException exception) {
            throw new IngestionException("process timed out after " + timeout, exception);
        } catch (ExecutionException exception) {
            throw new IngestionException("cannot read process output: " + exception.getCause().getMessage(), exception);
        } catch (IOException exception) {
            throw new IngestionException("cannot execute/read process: " + exception.getMessage(), exception);
        } finally {
            if (process != null) {
                if (process.isAlive()) {
                    try {
                        process.descendants().forEach(handle -> handle.destroyForcibly());
                    } catch (RuntimeException ignored) {
                        // Some sandboxes prohibit process enumeration. Always terminate the direct child.
                    }
                    process.destroyForcibly();
                }
                try {
                    process.getInputStream().close();
                    process.getErrorStream().close();
                    process.getOutputStream().close();
                    process.waitFor(1, TimeUnit.SECONDS);
                } catch (IOException ignored) {
                    // Process destruction is already requested; no repository resources are owned here.
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            readers.shutdownNow();
        }
    }

    private static long remaining(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException();
        }
        return remaining;
    }

    private static byte[] readBounded(Process child, InputStream stream, int limit) throws IOException {
        try (stream; var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = stream.read(buffer)) != -1) {
                if (length > limit - output.size()) {
                    child.destroyForcibly();
                    throw new IOException("process output exceeds " + limit + " bytes");
                }
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        }
    }
}
