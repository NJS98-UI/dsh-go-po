package io.github.ccch1mneyyy.dsh_app.root

import org.junit.Assert.*
import org.junit.Test

class SuManagerTest {
    @Test fun detectsEachManagerFromItsPrivateMarkers() {
        fun detect(dirs: Set<String>, exes: Set<String>): SuManager.Manager =
            SuManager.detect({ it in dirs }, { it in exes })

        assertEquals(SuManager.Manager.APATCH, detect(setOf("/data/adb/ap/"), emptySet()))
        assertEquals(SuManager.Manager.APATCH, detect(emptySet(), setOf("/data/adb/apd")))
        assertEquals(SuManager.Manager.KERNELSU, detect(setOf("/data/adb/ksu/"), emptySet()))
        assertEquals(SuManager.Manager.KERNELSU, detect(emptySet(), setOf("/data/adb/ksud")))
        assertEquals(SuManager.Manager.MAGISK, detect(setOf("/data/adb/magisk/"), emptySet()))
        assertEquals(SuManager.Manager.MAGISK, detect(setOf("/sbin/.magisk/"), emptySet()))
        assertEquals(SuManager.Manager.MAGISK, detect(emptySet(), setOf("/sbin/.magisk/busybox")))
        assertEquals(SuManager.Manager.UNKNOWN, detect(emptySet(), emptySet()))
    }

    @Test fun apatchBeatsKernelsuBeatsMagiskWhenMarkersOverlap() {
        val everything = setOf("/data/adb/ap/", "/data/adb/ksu/", "/data/adb/magisk/")
        assertEquals(SuManager.Manager.APATCH, SuManager.detect({ it in everything }, { false }))
        val noAp = setOf("/data/adb/ksu/", "/data/adb/magisk/")
        assertEquals(SuManager.Manager.KERNELSU, SuManager.detect({ it in noAp }, { false }))
    }

    @Test fun suPathAndRootedFollowTheFirstPresentBinary() {
        assertEquals("/system/bin/su", SuManager.suPath { it == "/system/bin/su" })
        assertEquals("/sbin/su", SuManager.suPath { it == "/sbin/su" })
        assertNull(SuManager.suPath { false })
        assertTrue(SuManager.rooted { it == "/system/xbin/su" })
        assertFalse(SuManager.rooted { false })
    }
}
