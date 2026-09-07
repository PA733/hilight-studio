package com.hilight.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class RootCommandTest {

    @Test
    fun `root launch is detached and explicitly owned`() {
        val start = RootCommand.start(
            "/storage/emulated/0/Android/data/com.hilight.studio/files/hilight",
            "root-instance-1",
        )

        assertTrue(start.contains("nohup app_process"))
        assertTrue(start.contains("--owner root"))
        assertTrue(start.contains("--instance 'root-instance-1'"))
        assertTrue(start.contains("< /dev/null"))
        assertTrue(start.contains("& echo \$!"))
        assertFalse(start.contains("pkill"))
    }

    @Test
    fun `bridge path is safely single quoted for the phone shell`() {
        val start = RootCommand.start("/data/a user's/light", "root-instance-2")
        assertTrue(start.contains("'/data/a user'\\''s/light'"))
    }

    @Test
    fun `root stop validates pid and owner before cooperative term`() {
        val stop = RootCommand.stop(4321, "root", "root-instance-1")
        assertTrue(stop.contains("/proc/4321/cmdline"))
        assertTrue(stop.contains("' --owner root '"))
        assertTrue(stop.contains("kill -TERM 4321"))
        assertFalse(stop.contains("kill -TERM \$p"))
        assertTrue(stop.contains("[ \"\$arg\" = \"root-instance-1\" ]"))
        assertTrue(stop.contains("\$i -lt 65"))
        assertTrue(stop.contains("then exit 1"))
        assertFalse(stop.contains("pkill"))
        assertTrue(stop.indexOf("kill -TERM") < stop.indexOf("exec app_process"))
    }

    @Test
    fun `renderer instance identity is an exact argv token not a prefix`() {
        val stop = RootCommand.stop(4321, "root", "root-1")
        assertTrue(stop.contains("while IFS= read -r arg"))
        assertTrue(stop.contains("[ \"\$prev\" = --instance ]"))
        assertTrue(stop.contains("[ \"\$arg\" = \"root-1\" ]"))
        assertFalse(stop.contains("grep -Fq -- '--instance root-1'"))
    }

    @Test
    fun `adb stop rejects a root-owned helper with the same entry point`() {
        val stop = RootCommand.stop(4321, "adb")
        assertTrue(stop.contains("com.hilight.core.AdbHelper"))
        assertTrue(stop.contains("! printf"))
        assertTrue(stop.contains("--owner root"))
        assertTrue(stop.contains("kill -TERM 4321"))
    }

    @Test
    fun `duplicate check uses one bounded process and preserves its exit status`() {
        val stop = RootCommand.stop(4321, "root", "root-1")
        assertFalse(stop.contains("for d in /proc/"))
        assertFalse(stop.contains("readlink"))
        assertTrue(stop.endsWith("exec app_process / com.hilight.core.RendererProcessScanner"))
        assertTrue(stop.contains("[ -n \"\$CLASSPATH\" ] || exit 1"))
    }

    @Test
    fun `absent source still scans and propagates scanner failure or timeout`() {
        // Execute only the absent-PID path with fake pm/app_process binaries. Never signal a PID.
        assumeTrue(File("/bin/sh").isFile && File("/proc").isDirectory)
        assumeTrue(!File("/proc/${Int.MAX_VALUE}").exists())
        val directory = Files.createTempDirectory("hilight-command-test").toFile()
        try {
            val pm = File(directory, "pm")
            pm.writeText("#!/bin/sh\nprintf 'package:/test/base.apk\\n'\n")
            assertTrue(pm.setExecutable(true))
            val scanner = File(directory, "app_process")
            for (exitCode in listOf(0, 1, 124)) {
                scanner.writeText("#!/bin/sh\n" +
                    "[ \"\$1\" = / ] || exit 98\n" +
                    "[ \"\$2\" = com.hilight.core.RendererProcessScanner ] || exit 99\n" +
                    "[ \"\$CLASSPATH\" = /test/base.apk ] || exit 97\n" +
                    "exit $exitCode\n")
                assertTrue(scanner.setExecutable(true))
                assertEquals(exitCode, runStop(directory))
            }
            // A missing installed APK must fail closed, not launch against an inherited classpath.
            pm.writeText("#!/bin/sh\nexit 0\n")
            assertEquals(1, runStop(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun runStop(bin: File): Int {
        val builder = ProcessBuilder("/bin/sh", "-c", RootCommand.stop(Int.MAX_VALUE, "root", "root-test"))
            .redirectErrorStream(true)
        builder.environment()["PATH"] = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
        val process = builder.start()
        try {
            assertTrue("stop command did not exit", process.waitFor(5, TimeUnit.SECONDS))
            return process.exitValue()
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
            process.outputStream.close()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `root launch rejects shell metacharacters in renderer identity`() {
        RootCommand.start("/data/local/tmp/hilight", "bad; kill 1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stop rejects shell metacharacters in renderer identity`() {
        RootCommand.stop(123, "root", "bad; kill 1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stop rejects nonpositive pid`() {
        RootCommand.stop(0, "root", "root-test")
    }
}
