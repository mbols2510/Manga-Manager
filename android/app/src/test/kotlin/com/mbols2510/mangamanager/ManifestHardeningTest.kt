package com.mbols2510.mangamanager

import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Static checks on the SOURCE manifest and backup rules (spec 7.4, 7.8).
 * Plain JVM test: parses the XML files directly, no Android runtime needed.
 */
class ManifestHardeningTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private val componentTags = listOf("activity", "activity-alias", "service", "receiver", "provider")

    private val allowedPermissions = setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.POST_NOTIFICATIONS",
    )

    /** Every domain the backup rule formats know about; all of them must be excluded. */
    private val backupDomains = setOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref",
    )

    // Gradle runs unit tests with the module directory (android/app) as working directory.
    private val mainDir = File("src/main")

    private fun parse(file: File): Document {
        assertTrue("missing ${file.absolutePath}", file.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        return factory.newDocumentBuilder().parse(file)
    }

    private val manifest: Document by lazy { parse(File(mainDir, "AndroidManifest.xml")) }

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.androidAttr(name: String): String? =
        if (hasAttributeNS(androidNs, name)) getAttributeNS(androidNs, name) else null

    private val application: Element
        get() = manifest.documentElement.children("application").single()

    private val components: List<Element>
        get() = componentTags.flatMap { application.children(it) }

    private fun Element.isLauncher(): Boolean = children("intent-filter").any { filter ->
        filter.children("action").any { it.androidAttr("name") == "android.intent.action.MAIN" } &&
            filter.children("category").any { it.androidAttr("name") == "android.intent.category.LAUNCHER" }
    }

    @Test
    fun allowBackup_is_false() {
        assertEquals("false", application.androidAttr("allowBackup"))
        assertEquals("@xml/data_extraction_rules", application.androidAttr("dataExtractionRules"))
        assertEquals("@xml/backup_rules", application.androidAttr("fullBackupContent"))
    }

    @Test
    fun only_launcher_activity_is_exported() {
        val launchers = application.children("activity").filter { it.isLauncher() }
        assertEquals("exactly one launcher activity", 1, launchers.size)
        assertEquals("true", launchers.single().androidAttr("exported"))

        for (component in components) {
            val name = component.androidAttr("name")
            if (component.children("intent-filter").isNotEmpty()) {
                assertTrue("$name has an intent-filter but no explicit android:exported",
                    component.androidAttr("exported") != null)
            }
            if (component.androidAttr("exported") == "true") {
                assertTrue("$name is exported but is not the launcher activity",
                    component.tagName == "activity" && component.isLauncher())
            }
        }
    }

    @Test
    fun permissions_are_limited_to_allowed_set() {
        val requested = (manifest.documentElement.children("uses-permission") +
            manifest.documentElement.children("uses-permission-sdk-23"))
            .map { it.androidAttr("name") }
            .toSet()
        assertEquals(allowedPermissions, requested)
    }

    @Test
    fun no_custom_scheme_intent_filter() {
        val dataTags = manifest.documentElement.children("data")
        val withScheme = dataTags.filter { it.androidAttr("scheme") != null }
        assertTrue("no <data android:scheme> allowed, found ${withScheme.size}", withScheme.isEmpty())
        val filters = components.flatMap { it.children("intent-filter") }
        assertEquals("only the launcher intent-filter is allowed", 1, filters.size)
    }

    @Test
    fun data_extraction_rules_exclude_everything() {
        val rules = parse(File(mainDir, "res/xml/data_extraction_rules.xml")).documentElement
        assertEquals("data-extraction-rules", rules.tagName)
        for (section in listOf("cloud-backup", "device-transfer")) {
            val element = rules.children(section).singleOrNull()
            assertTrue("missing <$section>", element != null)
            assertExcludesEverything(section, element!!)
        }

        val legacy = parse(File(mainDir, "res/xml/backup_rules.xml")).documentElement
        assertEquals("full-backup-content", legacy.tagName)
        assertExcludesEverything("full-backup-content", legacy)
    }

    private fun assertExcludesEverything(where: String, section: Element) {
        assertTrue("<$where> must not include anything", section.children("include").isEmpty())
        val excluded = section.children("exclude")
            .filter { it.getAttribute("path") == "." }
            .map { it.getAttribute("domain") }
            .toSet()
        assertEquals("<$where> must exclude every domain", backupDomains, excluded)
    }
}
