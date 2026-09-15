package com.catanav

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Hard-constraint #2 as an executable test: the manifest must never contain location
 * or network permissions, and may only ever declare the explicitly allowed set.
 * Runs on every `gradlew test`.
 */
class ManifestAuditTest {

    private val forbidden = listOf(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
    )

    private val allowed = setOf(
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_HEALTH",
        "android.permission.WAKE_LOCK",
        "android.permission.HIGH_SAMPLING_RATE_SENSORS",
        "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.POST_NOTIFICATIONS",
    )

    private fun manifestFile(): File {
        // Unit tests run with the module directory as the working directory, but be
        // robust to a root-project working directory too.
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
    }

    @Test
    fun forbiddenPermissions_areAbsent() {
        val text = manifestFile().readText()
        for (p in forbidden) {
            assertTrue("FORBIDDEN permission present in manifest: $p", !text.contains(p))
        }
    }

    @Test
    fun everyDeclaredPermission_isOnTheAllowlist() {
        val text = manifestFile().readText()
        val declared = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()
        assertTrue("manifest should declare at least one permission", declared.isNotEmpty())
        val offenders = declared.filter { it !in allowed }
        if (offenders.isNotEmpty()) {
            fail("Permissions outside the allowed set: $offenders")
        }
    }

    @Test
    fun noLocationOrNetworkStringsAnywhereInManifest() {
        val text = manifestFile().readText()
        assertTrue("manifest must not mention LOCATION at all", !text.contains("LOCATION"))
        assertTrue(
            "manifest must not mention INTERNET at all",
            !text.contains("INTERNET"),
        )
    }
}
