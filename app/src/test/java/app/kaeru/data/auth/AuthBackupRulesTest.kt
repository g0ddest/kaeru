package app.kaeru.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Checks the merged manifest's resource wiring and the rules protecting the token file. */
class AuthBackupRulesTest {
    @Test
    fun `legacy backup rules exclude private token DataStore`() {
        assertExcluded(rules("fullBackupContent"), "full-backup-content")
    }

    @Test
    fun `cloud extraction rules exclude private token DataStore`() {
        assertExcluded(rules("dataExtractionRules"), "cloud-backup")
    }

    @Test
    fun `device transfer rules exclude private token DataStore even when allowBackup is ignored`() {
        assertExcluded(rules("dataExtractionRules"), "device-transfer")
    }

    private fun rules(attribute: String): Element {
        val parser = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        // The unit-test APK has its own manifest; inspect the production merged artifact instead.
        val application = parser.parse(File("build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"))
            .getElementsByTagName("application").item(0) as Element
        val namespace = "http://schemas.android.com/apk/res/android"
        assertEquals("false", application.getAttributeNS(namespace, "allowBackup"))
        val resource = application.getAttributeNS(namespace, attribute)
        assertTrue("merged manifest must declare $attribute", resource.startsWith("@xml/"))
        return parser.parse(File("src/main/res/xml/${resource.removePrefix("@xml/")}.xml")).documentElement
    }

    private fun assertExcluded(root: Element, section: String) {
        val parent = if (root.tagName == section) root else root.getElementsByTagName(section).item(0) as? Element
        assertTrue("missing $section rules", parent != null)
        val excludes = parent!!.getElementsByTagName("exclude")
        val protectsTokens = (0 until excludes.length).any {
            val rule = excludes.item(it) as Element
            rule.getAttribute("domain") == "file" &&
                rule.getAttribute("path") in setOf(".", "datastore", "datastore/auth.preferences_pb")
        }
        assertTrue("$section must exclude files/datastore/auth.preferences_pb", protectsTokens)
    }
}
