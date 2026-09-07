package com.hilight.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Dependency-free regression harness, also invoked by the JUnit suite. Never reads real proc. */
public final class RendererProcessScannerSelfTest {
    private static final String HELPER = "com.hilight.core.AdbHelper";
    private static int cases;

    private RendererProcessScannerSelfTest() { }

    public static void main(String[] args) throws Exception {
        runAll();
    }

    public static void runAll() throws Exception {
        cases = 0;
        for (String executable : new String[]{"app_process", "app_process32", "/system/bin/app_process64"}) {
            withTree(proc -> {
                process(proc, "123", executable, "/", HELPER, "--owner", "root");
                blocked(proc, "AdbHelper remains");
            });
        }
        withTree(proc -> {
            process(proc, "124", "app_process", "/", HELPER, "--owner", "adb");
            blocked(proc, "AdbHelper remains");
        });
        withTree(proc -> {
            process(proc, "1", "/system/bin/sh", "-c", "exec app_process / " + HELPER);
            process(proc, "2", "app_process64", "/", "com.hilight.core.RendererProcessScanner");
            process(proc, "3", "app_process", "/", HELPER + "Lookalike");
            process(proc, "4", "my_app_process", "/", HELPER);
            process(proc, "5", "app_process", "/other", HELPER);
            require(RendererProcessScanner.scan(proc) == 5, "false positive on unrelated commands");
        });
        withTree(proc -> {
            process(proc, "6", "app_process", "/", "app_process / " + HELPER);
            require(RendererProcessScanner.scan(proc) == 1, "must compare argv tokens, not substrings");
        });
        withTree(proc -> {
            process(proc, "self", "app_process", "/", HELPER);
            process(proc, "123not-a-pid", "app_process", "/", HELPER);
            require(RendererProcessScanner.scan(proc) == 0, "must enumerate only numeric PID entries");
        });
        withTree(proc -> {
            Files.createDirectories(proc.resolve("7")); // process vanished / kernel thread
            require(RendererProcessScanner.scan(proc) == 1, "missing cmdline/exe should be harmless");
        });
        withTree(proc -> {
            Path p = process(proc, "8");
            Files.createSymbolicLink(p.resolve("exe"), Path.of("/system/bin/app_process64"));
            blocked(proc, "unreadable app_process");
        });
        withTree(proc -> {
            Path p = process(proc, "9");
            Files.createSymbolicLink(p.resolve("exe"), Path.of("/system/bin/app_process64 (deleted)"));
            blocked(proc, "unreadable app_process");
        });
        withTree(proc -> {
            Path p = process(proc, "10");
            Files.createSymbolicLink(p.resolve("exe"), Path.of("/system/bin/sh"));
            require(RendererProcessScanner.scan(proc) == 1, "empty unrelated process should be ignored");
        });
        withTree(proc -> {
            Path p = process(proc, "11", "app_process", "/");
            Files.writeString(p.resolve("cmdline"), "app_process\0/\0" + HELPER, StandardCharsets.UTF_8);
            blocked(proc, "incomplete app_process");
        });
        withTree(proc -> {
            Path p = process(proc, "12");
            Files.writeString(p.resolve("cmdline"), "x".repeat(5000), StandardCharsets.UTF_8);
            Files.createSymbolicLink(p.resolve("exe"), Path.of("/system/bin/app_process"));
            blocked(proc, "unreadable app_process");
        });
        withTree(proc -> {
            Path p = process(proc, "13");
            // Deterministic I/O failure, even when tests themselves run as root.
            Files.writeString(p.resolve("exe"), "not a symlink", StandardCharsets.UTF_8);
            blocked(proc, "unreadable process identity");
        });
        withTree(proc -> {
            for (int i = 1; i <= 3000; i++) {
                process(proc, Integer.toString(i), "/system/bin/sh", "worker", "private argument");
            }
            long start = System.nanoTime();
            require(RendererProcessScanner.scan(proc) == 3000, "large process tree not fully scanned");
            System.out.println("synthetic 3000-process scan ms=" + (System.nanoTime() - start) / 1_000_000);
        });
        withTree(proc -> {
            try {
                RendererProcessScanner.scan(proc.resolve("does-not-exist"));
                throw new AssertionError("enumeration failure was accepted");
            } catch (IOException expected) {
                // Expected: an incomplete process list cannot prove renderer absence.
            }
        });
        System.out.println("RendererProcessScannerSelfTest: " + cases + " cases passed");
    }

    private static Path process(Path proc, String pid, String... args) throws IOException {
        Path p = Files.createDirectories(proc.resolve(pid));
        String command = args.length == 0 ? "" : String.join("\0", args) + "\0";
        Files.writeString(p.resolve("cmdline"), command, StandardCharsets.UTF_8);
        return p;
    }

    private static void blocked(Path proc, String message) throws IOException {
        try {
            RendererProcessScanner.scan(proc);
            throw new AssertionError("unsafe process tree accepted");
        } catch (IOException expected) {
            require(expected.getMessage().contains(message), "unexpected failure: " + expected);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface Case { void run(Path proc) throws Exception; }

    private static void withTree(Case test) throws Exception {
        Path proc = Files.createTempDirectory("hilight-proc-test-");
        try {
            test.run(proc);
            cases++;
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(proc)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}
