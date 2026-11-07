package com.mbols2510.mangamanager.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the merged-manifest checker (run by the Gradle task `checkReleaseMergedManifest`)
 * is not vacuous: each fixture breaks exactly one rule and must be rejected.
 */
class MergedManifestCheckerTest {

    private val appId = "com.mbols2510.mangamanager"

    private val goodPermissions = """
        <uses-permission android:name="android.permission.INTERNET" />
        <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
        <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
        <uses-permission android:name="android.permission.WAKE_LOCK" />
        <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
        <uses-permission android:name="$appId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" />
    """

    private val launcher = """
        <activity android:name="com.mbols2510.mangamanager.MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    """

    private val guardedLibraryComponents = """
        <service android:name="androidx.work.impl.background.systemjob.SystemJobService"
            android:exported="true" android:permission="android.permission.BIND_JOB_SERVICE" />
        <receiver android:name="androidx.work.impl.diagnostics.DiagnosticsReceiver"
            android:exported="true" android:permission="android.permission.DUMP" />
        <receiver android:name="androidx.profileinstaller.ProfileInstallReceiver"
            android:exported="true" android:permission="android.permission.DUMP" />
        <receiver android:name="androidx.work.impl.background.systemalarm.RescheduleReceiver"
            android:exported="false" />
        <provider android:name="androidx.startup.InitializationProvider"
            android:authorities="$appId.androidx-startup" android:exported="false" />
    """

    private fun manifest(
        permissions: String = goodPermissions,
        applicationAttrs: String = "",
        components: String = launcher + guardedLibraryComponents,
    ) = """<?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$appId">
            $permissions
            <application android:allowBackup="false" $applicationAttrs>
                $components
            </application>
        </manifest>
    """.trimIndent()

    private fun violations(xml: String) = MergedManifestChecker.check(xml.byteInputStream(), appId)

    private fun assertRejected(xml: String, expectedFragment: String) {
        val found = violations(xml)
        assertTrue("expected a violation mentioning '$expectedFragment', got $found",
            found.any { it.contains(expectedFragment) })
    }

    @Test
    fun accepts_hardened_manifest() {
        assertEquals(emptyList<String>(), violations(manifest()))
    }

    @Test
    fun rejects_default_workmanager_initializer() {
        val withInitializer = launcher + guardedLibraryComponents.replace(
            "android:authorities=\"$appId.androidx-startup\" android:exported=\"false\" />",
            "android:authorities=\"$appId.androidx-startup\" android:exported=\"false\">" +
                "<meta-data android:name=\"androidx.work.WorkManagerInitializer\" android:value=\"androidx.startup\" />" +
                "<meta-data android:name=\"androidx.profileinstaller.ProfileInstallerInitializer\" android:value=\"androidx.startup\" />" +
                "</provider>",
        )
        assertTrue("fixture must contain the initializer", withInitializer.contains("WorkManagerInitializer"))
        assertRejected(manifest(components = withInitializer), "forbidden startup initializer present: androidx.work.WorkManagerInitializer")
        assertEquals(1, violations(manifest(components = withInitializer)).size)
    }

    @Test
    fun accepts_explicit_non_debuggable_application() {
        assertEquals(emptyList<String>(), violations(manifest(applicationAttrs = """android:debuggable="false"""")))
    }

    @Test
    fun rejects_extra_foreground_service_permission() {
        assertRejected(
            manifest(permissions = goodPermissions +
                """<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />"""),
            "android.permission.FOREGROUND_SERVICE",
        )
    }

    @Test
    fun rejects_extra_permission_via_uses_permission_sdk_23() {
        assertRejected(
            manifest(permissions = goodPermissions +
                """<uses-permission-sdk-23 android:name="android.permission.CAMERA" />"""),
            "android.permission.CAMERA",
        )
    }

    @Test
    fun rejects_missing_allowlisted_permission() {
        assertRejected(
            manifest(permissions = goodPermissions.replace(
                """<uses-permission android:name="android.permission.WAKE_LOCK" />""", "")),
            "android.permission.WAKE_LOCK",
        )
    }

    @Test
    fun rejects_dynamic_receiver_permission_of_another_application_id() {
        assertRejected(
            manifest(permissions = goodPermissions.replace("$appId.DYNAMIC", "com.example.other.DYNAMIC")),
            "com.example.other.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
    }

    @Test
    fun rejects_exported_service_without_permission() {
        assertRejected(
            manifest(components = launcher + guardedLibraryComponents +
                """<service android:name="com.example.LeakyService" android:exported="true" />"""),
            "com.example.LeakyService",
        )
    }

    @Test
    fun rejects_allowlisted_component_with_wrong_permission() {
        assertRejected(
            manifest(components = launcher + guardedLibraryComponents.replace(
                "android.permission.BIND_JOB_SERVICE", "android.permission.INTERNET")),
            "androidx.work.impl.background.systemjob.SystemJobService",
        )
    }

    @Test
    fun rejects_allowlisted_component_without_permission() {
        assertRejected(
            manifest(components = launcher + guardedLibraryComponents.replace(
                """android:exported="true" android:permission="android.permission.DUMP" />""",
                """android:exported="true" />""")),
            "androidx.work.impl.diagnostics.DiagnosticsReceiver",
        )
    }

    @Test
    fun rejects_second_exported_activity() {
        assertRejected(
            manifest(components = launcher + guardedLibraryComponents +
                """<activity android:name="com.mbols2510.mangamanager.Other" android:exported="true" />"""),
            "com.mbols2510.mangamanager.Other",
        )
    }

    @Test
    fun rejects_debuggable_application() {
        assertRejected(manifest(applicationAttrs = """android:debuggable="true""""), "debuggable")
    }

    @Test
    fun rejects_preview_activity() {
        assertRejected(
            manifest(components = launcher + guardedLibraryComponents +
                """<activity android:name="androidx.compose.ui.tooling.PreviewActivity" android:exported="false" />"""),
            "androidx.compose.ui.tooling.PreviewActivity",
        )
    }

    @Test
    fun rejects_test_component_activity() {
        assertRejected(
            manifest(components = launcher + guardedLibraryComponents +
                """<activity android:name="androidx.activity.ComponentActivity" android:exported="false" />"""),
            "androidx.activity.ComponentActivity",
        )
    }

    @Test
    fun rejects_system_foreground_service() {
        assertRejected(
            manifest(components = launcher + guardedLibraryComponents +
                """<service android:name="androidx.work.impl.foreground.SystemForegroundService" android:exported="false" />"""),
            "androidx.work.impl.foreground.SystemForegroundService",
        )
    }

    @Test
    fun rejects_doctype() {
        val xml = manifest().replaceFirst("<manifest", "<!DOCTYPE manifest [<!ENTITY x \"y\">]><manifest")
        assertRejected(xml, "unparseable")
    }
}
