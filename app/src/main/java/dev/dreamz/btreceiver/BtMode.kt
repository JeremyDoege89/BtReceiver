package dev.dreamz.btreceiver

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Speaker/regular switching is done by the bundled assets/btmode.sh, installed as root to
 * /data/adb/btreceiver with a post-fs-data boot hook, so a rooted phone needs nothing but the APK.
 */
object BtMode {
    private const val DIR = "/data/adb/btreceiver"
    private const val SCRIPT = "$DIR/btmode.sh"

    enum class Mode { SPEAKER, NORMAL }

    data class Status(
        val mode: Mode? = null,
        val bluetoothOn: Boolean = false,
        val activeDevice: String? = null,
        val sdk: Int = 0,
        val soc: String = "",
        val rootManager: String = "",
        val hasResetprop: Boolean = false,
        val sinkRunning: Boolean = false,
        val error: String? = null,
    )

    class Result(val code: Int, val output: String)

    /** Falls back to plain `su` on root managers without `--mount-master`. */
    @Volatile private var mountMaster = true

    /**
     * Runs [cmd] as root, in the global mount namespace where available (on KernelSU /data/adb
     * isn't visible from an app's namespace otherwise). [stdin] is fed to the shell.
     */
    suspend fun su(cmd: String, stdin: ByteArray? = null): Result = withContext(Dispatchers.IO) {
        fun run(args: List<String>): Result = try {
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            p.outputStream.use { out -> stdin?.let { out.write(it) } }
            val text = p.inputStream.bufferedReader().readText()
            Result(p.waitFor(), text.trim())
        } catch (e: Exception) {
            Result(-1, e.message ?: e.toString())
        }
        if (mountMaster) {
            val r = run(listOf("su", "--mount-master", "-c", cmd))
            val unsupported = r.code != 0 &&
                Regex("unknown option|unrecognized option|invalid option", RegexOption.IGNORE_CASE).containsMatchIn(r.output)
            if (!unsupported) return@withContext r
            mountMaster = false
        }
        run(listOf("su", "-c", cmd))
    }

    /** Copies the bundled script into place (every launch, so app updates ship script fixes) and runs its setup. */
    suspend fun install(context: Context): Result {
        val script = context.assets.open("btmode.sh").use { it.readBytes() }
        return su("mkdir -p $DIR && cat > $SCRIPT && chmod 755 $SCRIPT && sh $SCRIPT setup", script)
    }

    suspend fun status(): Status {
        val r = su("sh $SCRIPT status")
        if (r.code != 0) {
            return Status(error = "Root access failed. Allow BT Receiver in your root manager (KernelSU, Magisk or APatch).\n${r.output}")
        }
        val lines = r.output.lines().map { it.trim() }
        fun value(prefix: String) = lines.firstOrNull { it.startsWith(prefix) }?.substringAfter(prefix)?.trim()
        return Status(
            mode = when (value("Mode:")) {
                "speaker" -> Mode.SPEAKER
                "normal" -> Mode.NORMAL
                else -> null
            },
            bluetoothOn = value("Bluetooth on:") == "yes",
            activeDevice = lines.firstOrNull { it.startsWith("mDevice:") }
                ?.let { Regex("""\((.+?)\) name=""").find(it)?.groupValues?.get(1) ?: it.substringAfter("mDevice:").substringBefore(" ") },
            sdk = value("SDK:")?.toIntOrNull() ?: 0,
            soc = value("SoC:").orEmpty(),
            rootManager = value("Root:").orEmpty(),
            hasResetprop = value("resetprop:").let { it != null && it != "none" },
            sinkRunning = value("Sink service:") == "running",
        )
    }

    /** Takes about 4 seconds: the script restarts the Bluetooth process. */
    suspend fun set(mode: Mode): Result =
        su("sh $SCRIPT ${if (mode == Mode.SPEAKER) "speaker" else "normal"}")

    /** Restores normal Bluetooth and removes the script and boot hook; run before uninstalling the app. */
    suspend fun uninstall(): Result = su("sh $SCRIPT uninstall")

    /** Fallback when the Bluetooth media session isn't reachable: a media key to the active session. */
    suspend fun dispatchKey(key: String): Result = su("cmd media_session dispatch $key")
}
