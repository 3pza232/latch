package dev.latch.rule.privilege

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.lang.reflect.Method

/** [PrivilegeBroker] backed by a running Shizuku service. */
class ShizukuPrivilegeBroker : PrivilegeBroker {

    override fun status(): PrivilegeStatus = runCatching {
        if (!Shizuku.pingBinder()) return PrivilegeStatus.UNAVAILABLE
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            PrivilegeStatus.READY
        } else {
            PrivilegeStatus.NOT_GRANTED
        }
    }.getOrDefault(PrivilegeStatus.UNAVAILABLE)

    override suspend fun execute(command: String): ShellResult = withContext(Dispatchers.IO) {
        runCatching {
            coroutineScope {
                val process = ShizukuShell.start(arrayOf("sh", "-c", command))
                val stderr = async(Dispatchers.IO) {
                    process.errorStream.bufferedReader().use { it.readText() }
                }
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                ShellResult(process.waitFor(), stdout, stderr.await())
            }
        }.getOrElse { error ->
            ShellResult(exitCode = -1, stdout = "", stderr = error.message.orEmpty())
        }
    }
}

/**
 * Shizuku 13 keeps its shell launcher private; the public API only exposes binder wrapping.
 * Shizuku 13 does not expose process creation publicly; the reflective call is isolated here.
 */
private object ShizukuShell {

    private val newProcess: Method? = runCatching {
        Shizuku::class.java
            .getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            .apply { isAccessible = true }
    }.getOrNull()

    fun start(command: Array<String>): Process {
        val method = newProcess ?: error("Shizuku shell API is unavailable")
        return method.invoke(null, command, null, null) as Process
    }
}
