package dev.changeassurance.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GitProcessTests {
    @TempDir
    Path temporary;

    @Test
    void boundsStdoutAndStderrAndKillsChildOnOverflow() throws Exception {
        for (String mode : new String[]{"stdout", "stderr"}) {
            IngestionException failure = assertThrows(IngestionException.class,
                    () -> GitProcess.execute(child(mode), Duration.ofSeconds(5), 1024));
            assertTrue(failure.getMessage().contains("exceeds"), failure.getMessage());
            assertChildStopped();
        }
    }

    @Test
    void timesOutAndKillsChild() throws Exception {
        IngestionException failure = assertThrows(IngestionException.class,
                () -> GitProcess.execute(child("sleep"), Duration.ofSeconds(1), 1024));
        assertTrue(failure.getMessage().contains("timed out"), failure.getMessage());
        assertChildStopped();
    }

    @Test
    void reportsExitCodeAndStderr() {
        IngestionException failure = assertThrows(IngestionException.class,
                () -> GitProcess.execute(child("exit"), Duration.ofSeconds(5), 1024));
        assertTrue(failure.getMessage().contains("exit 7: deliberate failure"));
    }

    @Test
    void clearsInheritedGitOverridesAndPreservesArgumentsWithoutAShell() {
        ProcessBuilder builder = child("arguments");
        builder.environment().put("GIT_DIR", "/wrong/repository");
        builder.environment().put("GIT_CONFIG_COUNT", "999");
        builder.command().add("$(touch should-not-exist); spaces");
        String result = new String(GitProcess.execute(builder, Duration.ofSeconds(5), 1024), StandardCharsets.UTF_8);
        assertEquals("null|null|$(touch should-not-exist); spaces", result);
        assertFalse(Files.exists(temporary.resolve("should-not-exist")));
    }

    @Test
    void preservesInterruptionAndCleansUp() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(IngestionException.class,
                    () -> GitProcess.execute(child("sleep"), Duration.ofSeconds(5), 1024));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private ProcessBuilder child(String mode) {
        String classes = Path.of("target/test-classes").toAbsolutePath().toString();
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classes, Child.class.getName(), mode, temporary.resolve("pid").toString())
                .directory(temporary.toFile());
    }

    private void assertChildStopped() throws Exception {
        long pid = Long.parseLong(Files.readString(temporary.resolve("pid")));
        var handle = ProcessHandle.of(pid);
        if (handle.isPresent()) {
            handle.get().onExit().get(2, TimeUnit.SECONDS);
        }
        assertTrue(ProcessHandle.of(pid).isEmpty() || !ProcessHandle.of(pid).orElseThrow().isAlive());
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
            switch (args[0]) {
                case "sleep" -> Thread.sleep(30_000);
                case "stdout", "stderr" -> {
                    var output = args[0].equals("stdout") ? System.out : System.err;
                    byte[] bytes = new byte[8192];
                    while (true) {
                        output.write(bytes);
                        output.flush();
                    }
                }
                case "exit" -> {
                    System.err.print("deliberate failure");
                    System.exit(7);
                }
                case "arguments" -> System.out.print(System.getenv("GIT_DIR") + "|"
                        + System.getenv("GIT_CONFIG_COUNT") + "|" + args[2]);
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
    }
}
