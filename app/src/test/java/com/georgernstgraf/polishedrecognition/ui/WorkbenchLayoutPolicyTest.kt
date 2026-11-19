package com.georgernstgraf.polishedrecognition.ui

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.xml.sax.Attributes
import org.xml.sax.Locator
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import javax.xml.parsers.SAXParserFactory

/**
 * #173 decision 8 — the two Workbench fields are **content-sized**: the page
 * scrolls, the fields never do.
 *
 * Static XML guard in the [XmlColorPolicyTest] pattern: pure JUnit, no
 * Robolectric, read from disk so the assertion is about the resource that
 * ships. A field with a fixed `dp` height or an inner `scrollbars` recreates
 * the very defect this decision removed (a long transcript scrolling inside a
 * 140-dp box instead of scrolling the screen).
 */
class WorkbenchLayoutPolicyTest {

    private data class Element(
        val line: Int,
        val name: String,
        val attributes: Map<String, String>,
    )

    private val layout: File = File(moduleDir(), "src/main/res/layout/activity_workbench.xml")

    private fun moduleDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/res/layout").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError(
            "cannot locate the app module (src/main/res/layout) from " +
                File(System.getProperty("user.dir") ?: ".").absolutePath
        )
    }

    private fun parse(file: File): List<Element> {
        val collector = Collector()
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        file.inputStream().use { factory.newSAXParser().parse(it, collector) }
        return collector.elements
    }

    private class Collector : DefaultHandler() {
        val elements = mutableListOf<Element>()
        private var locator: Locator? = null

        override fun setDocumentLocator(locator: Locator) {
            this.locator = locator
        }

        override fun startElement(uri: String?, localName: String?, qName: String, atts: Attributes) {
            val attributes = LinkedHashMap<String, String>(atts.length)
            for (i in 0 until atts.length) attributes[atts.getQName(i)] = atts.getValue(i)
            elements += Element(locator?.lineNumber ?: -1, qName, attributes)
        }
    }

    private fun elementById(id: String): Element {
        val idAttribute = "@+id/$id"
        return parse(layout).firstOrNull { it.attributes["android:id"] == idAttribute }
            ?: throw AssertionError("activity_workbench.xml declares no element with id $id")
    }

    @Test
    fun `the two workbench fields are wrap_content without an inner scrollbar`() {
        val fields = listOf("workbench_raw", "workbench_polished")
        val violations = mutableListOf<String>()

        for (id in fields) {
            val element = elementById(id)
            val height = element.attributes["android:layout_height"]
            if (height != "wrap_content") {
                violations += "line ${element.line} $id: layout_height=\"$height\" — " +
                    "the field must grow with its content (wrap_content)"
            }
            if (element.attributes.containsKey("android:scrollbars")) {
                violations += "line ${element.line} $id: declares android:scrollbars — " +
                    "only the outer ScrollView may scroll"
            }
        }

        assertWithMessage(
            "a fixed-height field with an inner scrollbar is the #173 defect:\n" +
                violations.joinToString("\n")
        ).that(violations).isEmpty()
    }

    @Test
    fun `the two workbench fields keep at least two visible lines`() {
        for (id in listOf("workbench_raw", "workbench_polished")) {
            val element = elementById(id)
            val minLines = element.attributes["android:minLines"]?.toIntOrNull()
            assertWithMessage("line ${element.line} $id: minLines must be at least 2")
                .that(minLines ?: 0).isAtLeast(2)
        }
    }

    @Test
    fun `no view in the workbench layout scrolls on its own`() {
        val offenders = parse(layout)
            .filter { it.attributes.containsKey("android:scrollbars") }
            .map { "line ${it.line} <${it.name}>" }

        assertWithMessage(
            "only the outer ScrollView scrolls (#173 decision 8); inner scrollers:\n" +
                offenders.joinToString("\n")
        ).that(offenders).isEmpty()
    }

    @Test
    fun `each label row is an accordion with a chevron and a preview line`() {
        listOf("raw", "polished").forEach { which ->
            assertWithMessage("$which toggle row is missing")
                .that(parse(layout).any { it.attributes["android:id"] == "@+id/workbench_${which}_toggle" })
                .isTrue()
            assertWithMessage("$which chevron is missing")
                .that(parse(layout).any { it.attributes["android:id"] == "@+id/workbench_${which}_chevron" })
                .isTrue()
            assertWithMessage("$which collapsed preview is missing")
                .that(parse(layout).any { it.attributes["android:id"] == "@+id/workbench_${which}_preview" })
                .isTrue()
        }
    }
}
