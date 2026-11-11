package com.suryasukma.vcamtest.utils

import java.util.concurrent.TimeUnit

/** Minimal, optional root helper. Nothing here runs unless the user taps a tool that needs it. */
object RootShell {
    data class Result(val ok: Boolean, val output: String)

    private val PACKAGE_REGEX = Regex("^[A-Za-z0-9_.]+$")

    fun isValidPackage(pkg: String) = PACKAGE_REGEX.matches(pkg)

    /** Blocking: call from a background thread. */
    fun run(command: String, root: Boolean, timeoutMs: Long = 8000L): Result {
        return try {
            val args = if (root) listOf("su", "-c", command) else listOf("sh", "-c", command)
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) if (out.length < 200_000) out.append(line).append('\n')
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return Result(false, out.toString() + "\n[timeout]")
            }
            reader.join(1000)
            Result(process.exitValue() == 0, out.toString())
        } catch (e: Exception) {
            Result(false, e.message ?: "error")
        }
    }

    fun forceStop(pkg: String): Result {
        if (!isValidPackage(pkg)) return Result(false, "invalid package")
        return run("am force-stop $pkg", root = true)
    }

    /** Relevant log lines, from all processes when root is available. */
    fun readLogs(): Pair<Boolean, String> {
        val filter = "grep -iE 'FDVCam|AndroidRuntime|FATAL EXCEPTION|LSPosed|Xposed|vcamtest' | tail -n 300"
        val rooted = run("logcat -d -v time -t 3000 | $filter", root = true, timeoutMs = 12000L)
        if (rooted.ok) return true to rooted.output
        val own = run("logcat -d -v time -t 500 | $filter", root = false, timeoutMs = 8000L)
        return false to own.output
    }
}
