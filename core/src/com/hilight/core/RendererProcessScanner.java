package com.hilight.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Read-only duplicate-renderer check, launched once after an exact root/ADB helper has exited.
 *
 * The former shell loop spawned tr/readlink for every PID. On an affected phone that scan alone
 * exceeded 30 seconds, well beyond the entire nine-second shutdown budget. Read proc directly in
 * one process instead. This class never constructs an Engine, signals a PID, or changes files.
 */
public final class RendererProcessScanner {
    private static final String HELPER = "com.hilight.core.AdbHelper";
    private static final int PREFIX_LIMIT = 4096;
    private static final long SCAN_TIMEOUT_MS = 5_000;

    private RendererProcessScanner() { }

    public static void main(String[] args) {
        if (args.length != 0) {
            System.err.println("HiLight renderer scan: no arguments accepted");
            System.exit(2);
            return;
        }
        // Bound even a blocked proc read. The parent also bounds su plus VM startup and shutdown.
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(SCAN_TIMEOUT_MS);
                System.err.println("HiLight renderer scan: timed out; replacement blocked");
                System.exit(124);
            } catch (InterruptedException finished) {
                Thread.currentThread().interrupt();
            }
        }, "hilight-scan-timeout");
        watchdog.setDaemon(true);
        watchdog.start();
        int result = 1;
        long started = System.nanoTime();
        try {
            int checked = scan(Paths.get("/proc"));
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            System.out.println("HiLight renderer scan: clear; checked=" + checked
                    + " elapsedMs=" + elapsedMs);
            result = 0;
        } catch (IOException | RuntimeException failure) {
            // Report the reason/PID, never the contents of another application's command line.
            System.err.println("HiLight renderer scan: blocked: " + failure.getMessage());
        } finally {
            watchdog.interrupt();
        }
        System.exit(result);
    }

    /** Tests use a synthetic proc tree; the privileged entry point accepts no path override. */
    static int scan(Path proc) throws IOException {
        int checked = 0;
        // Enumeration errors propagate. A partial process list is not proof of absence.
        try (DirectoryStream<Path> processes = Files.newDirectoryStream(proc)) {
            for (Path process : processes) {
                if (!isPid(process.getFileName().toString())) continue;
                checked++;
                byte[] prefix;
                try {
                    prefix = readPrefix(process.resolve("cmdline"));
                } catch (IOException unreadable) {
                    prefix = new byte[0];
                }
                String[] argv = new String(prefix, StandardCharsets.UTF_8).split("\u0000", -1);
                if (prefix.length == 0 || argv.length < 2) {
                    checkUnknownCommand(process);
                    continue;
                }
                if (!isAppProcess(baseName(argv[0]))) continue;
                // A fourth field proves the third token ended at NUL, not at the read limit.
                if (argv.length < 4) {
                    throw new IOException("incomplete app_process identity at PID "
                            + process.getFileName());
                }
                if ("/".equals(argv[1]) && HELPER.equals(argv[2])) {
                    throw new IOException("AdbHelper remains at PID " + process.getFileName());
                }
            }
        }
        return checked;
    }

    private static byte[] readPrefix(Path file) throws IOException {
        // proc cmdline commonly has a stat size of zero; read its stream, not File.length().
        try (InputStream input = Files.newInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int separators = 0;
            while (output.size() < PREFIX_LIMIT) {
                int count = input.read(buffer, 0, Math.min(buffer.length, PREFIX_LIMIT - output.size()));
                if (count < 0) break;
                if (count == 0) throw new IOException("no progress reading proc cmdline");
                for (int i = 0; i < count; i++) {
                    output.write(buffer[i]);
                    if (buffer[i] == 0 && ++separators == 3) return output.toByteArray();
                }
            }
            return output.toByteArray();
        }
    }

    private static void checkUnknownCommand(Path process) throws IOException {
        String executable;
        try {
            executable = Files.readSymbolicLink(process.resolve("exe")).toString();
        } catch (NoSuchFileException absent) {
            // Kernel threads, zombies and a process exiting during enumeration have no exe.
            return;
        } catch (IOException unresolved) {
            if (Files.notExists(process)) return;
            throw new IOException("unreadable process identity at PID " + process.getFileName());
        }
        if (executable.endsWith(" (deleted)")) {
            executable = executable.substring(0, executable.length() - " (deleted)".length());
        }
        if (isAppProcess(baseName(executable))) {
            throw new IOException("unreadable app_process command at PID " + process.getFileName());
        }
    }

    private static boolean isPid(String name) {
        if (name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < '0' || name.charAt(i) > '9') return false;
        }
        return true;
    }

    private static String baseName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static boolean isAppProcess(String name) {
        return "app_process".equals(name) || "app_process32".equals(name)
                || "app_process64".equals(name);
    }
}
