package io.github.ccch1mneyyy.dsh_app.root

/**
 * Which root manager (if any) this device runs, from fixed filesystem markers only. Pure and
 * JVM-testable: every check goes through the injected [fileExists]/[executable] functions and
 * nothing here executes su. [Manager] matches the three managers the privileged background path
 * (`phone/background/RootStarter.kt`) drives with `su -c` — Magisk, KernelSU and APatch.
 */
object SuManager {
    enum class Manager(val label: String) {
        MAGISK("magisk"),
        KERNELSU("kernelsu"),
        APATCH("apatch"),
        UNKNOWN("unknown"),
    }

    /** Well-known su binaries, tried in order; the first present wins. */
    val SU_PATHS = listOf(
        "/data/adb/ap/bin/su",      // APatch
        "/data/adb/ksu/bin/su",     // KernelSU
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/su/bin/su",
    )

    /** The manager from its private marker directories/daemons, or [Manager.UNKNOWN]. */
    fun detect(fileExists: (String) -> Boolean, executable: (String) -> Boolean): Manager = when {
        fileExists("/data/adb/ap/") || executable("/data/adb/apd") -> Manager.APATCH
        fileExists("/data/adb/ksu/") || executable("/data/adb/ksud") -> Manager.KERNELSU
        fileExists("/data/adb/magisk/") || fileExists("/sbin/.magisk/") ||
            executable("/sbin/.magisk/busybox") -> Manager.MAGISK
        else -> Manager.UNKNOWN
    }

    /** The first su binary on [SU_PATHS] that [executable] accepts, or null. */
    fun suPath(executable: (String) -> Boolean): String? = SU_PATHS.firstOrNull(executable)

    /** A device counts as rooted when an executable su binary is present, whatever the manager. */
    fun rooted(executable: (String) -> Boolean): Boolean = suPath(executable) != null
}
