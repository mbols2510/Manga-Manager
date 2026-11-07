package com.mbols2510.mangamanager.manifest

import java.io.File
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.system.exitProcess
import org.w3c.dom.Element

/**
 * Allowlist check for the MERGED release manifest (Ruling 12b): what ships, including
 * library contributions. Single implementation shared by the `checkReleaseMergedManifest`
 * Gradle task (through [main]) and the fixture tests in MergedManifestCheckerTest.
 */
object MergedManifestChecker {

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    const val LAUNCHER_ACTIVITY = "com.mbols2510.mangamanager.MainActivity"

    private val COMPONENT_TAGS = listOf("activity", "activity-alias", "service", "receiver", "provider")

    /** Exported library components and the exact system permission that must guard each. */
    val GUARDED_EXPORTED_COMPONENTS = mapOf(
        "androidx.work.impl.background.systemjob.SystemJobService" to "android.permission.BIND_JOB_SERVICE",
        "androidx.work.impl.diagnostics.DiagnosticsReceiver" to "android.permission.DUMP",
        "androidx.profileinstaller.ProfileInstallReceiver" to "android.permission.DUMP",
    )

    /** Components that must never be in a release manifest. */
    val FORBIDDEN_COMPONENTS = setOf(
        "androidx.compose.ui.tooling.PreviewActivity",
        "androidx.activity.ComponentActivity",
        "androidx.work.impl.foreground.SystemForegroundService",
    )

    /**
     * Startup initializers that must be removed: WorkManager is initialized on demand from `MangaApp`
     * (Configuration.Provider with the CheckWorker factory); if App Startup also initialized it, the factory
     * would be ignored and the worker could not be created.
     */
    val FORBIDDEN_INITIALIZERS = setOf("androidx.work.WorkManagerInitializer")

    fun allowedPermissions(applicationId: String): Set<String> = setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.WAKE_LOCK",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "$applicationId.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    )

    /** Returns human-readable violations; empty means the manifest passes. */
    fun check(input: InputStream, applicationId: String): List<String> {
        val root = try {
            parse(input)
        } catch (e: Exception) {
            return listOf("unparseable manifest: ${e.message}")
        }
        val problems = mutableListOf<String>()

        // (a) permission set equals the allowlist exactly.
        val requested = (root.elements("uses-permission") + root.elements("uses-permission-sdk-23"))
            .map { it.androidAttr("name").orEmpty() }
            .toSet()
        val allowed = allowedPermissions(applicationId)
        (requested - allowed).sorted().forEach { problems += "permission not in allowlist: $it" }
        (allowed - requested).sorted().forEach { problems += "allowlisted permission missing (allowlist out of date?): $it" }

        val application = root.elements("application").singleOrNull()
            ?: return problems + "expected exactly one <application>"

        // (d) not debuggable.
        val debuggable = application.androidAttr("debuggable")
        if (debuggable != null && debuggable != "false") {
            problems += "application is debuggable (android:debuggable=\"$debuggable\")"
        }

        // (f) initializers that would bypass the app's own WorkManager configuration.
        application.elements("meta-data")
            .map { it.androidAttr("name").orEmpty() }
            .filter { it in FORBIDDEN_INITIALIZERS }
            .forEach { problems += "forbidden startup initializer present: $it" }

        val components = COMPONENT_TAGS.flatMap { tag -> application.elements(tag).map { tag to it } }
        for ((tag, component) in components) {
            val name = component.androidAttr("name").orEmpty()
            // (c) and (e): debug-only and foreground-service components must be absent.
            if (name in FORBIDDEN_COMPONENTS) problems += "forbidden component present: <$tag> $name"

            // (b) exported components: launcher, or allowlisted with its exact guard.
            if (component.androidAttr("exported") != "true") continue
            val permission = component.androidAttr("permission")
            val requiredGuard = GUARDED_EXPORTED_COMPONENTS[name]
            when {
                tag == "activity" && name == LAUNCHER_ACTIVITY -> Unit
                requiredGuard == null ->
                    problems += "exported component not in allowlist: <$tag> $name (permission=$permission)"
                permission != requiredGuard ->
                    problems += "exported component $name must be guarded by $requiredGuard, found permission=$permission"
            }
        }
        return problems
    }

    private fun parse(input: InputStream): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        return factory.newDocumentBuilder().parse(input).documentElement
    }

    /** What the manifest actually declares, for the success log line. */
    fun describe(input: InputStream): String {
        val root = parse(input)
        val permissions = (root.elements("uses-permission") + root.elements("uses-permission-sdk-23"))
            .map { it.androidAttr("name").orEmpty() }.sorted()
        val exported = COMPONENT_TAGS.flatMap { tag -> root.elements(tag) }
            .filter { it.androidAttr("exported") == "true" }
            .map { "${it.androidAttr("name")} (permission=${it.androidAttr("permission")})" }
        return "permissions=$permissions exported=$exported"
    }

    private fun Element.elements(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.androidAttr(name: String): String? =
        if (hasAttributeNS(ANDROID_NS, name)) getAttributeNS(ANDROID_NS, name) else null

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "usage: MergedManifestChecker <merged AndroidManifest.xml> <applicationId>" }
        val problems = File(args[0]).inputStream().use { check(it, args[1]) }
        if (problems.isNotEmpty()) {
            System.err.println("Merged release manifest check FAILED (${args[0]}):")
            problems.forEach { System.err.println("  - $it") }
            exitProcess(1)
        }
        println("Merged release manifest check OK: ${args[0]}")
        println("  " + File(args[0]).inputStream().use { describe(it) })
    }
}
