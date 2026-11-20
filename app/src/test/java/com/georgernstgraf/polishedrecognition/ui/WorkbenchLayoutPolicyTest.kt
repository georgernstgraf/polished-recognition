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
 *
 * #178 replaces the accordion rule: the actions live in **three zones** (A
 * above Raw, B below Raw, C below Polished), Polished is a **read-only
 * TextView**, and every icon button carries a content description and no
 * own background.
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

    /** Document order of the declared ids, so "above/below the field" is checkable. */
    private fun orderOf(vararg ids: String): Map<String, Int> {
        val declared = parse(layout).mapNotNull { it.attributes["android:id"] }
        return ids.associateWith { id ->
            declared.indexOf("@+id/$id").also {
                if (it < 0) throw AssertionError("activity_workbench.xml declares no id $id")
            }
        }
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

    /**
     * #178: the three action zones. Zone A sits above Raw, zone B between Raw
     * and Polished, zone C below Polished — the order in the file is the order
     * on the screen, so it is the assertion.
     */
    @Test
    fun `the three action zones exist above, below and beneath the polished field`() {
        val order = orderOf(
            "workbench_language_a",
            "workbench_polish_a",
            "workbench_raw",
            "workbench_language_b",
            "workbench_polish_b",
            "workbench_promote_b",
            "workbench_clear_polished",
            "workbench_polished",
            "workbench_promote_c",
        )
        fun at(id: String) = order.getValue(id)

        val violations = mutableListOf<String>()
        // Zone A — above Raw.
        for (id in listOf("workbench_language_a", "workbench_polish_a")) {
            if (at(id) > at("workbench_raw")) {
                violations += "$id must precede workbench_raw (zone A)"
            }
        }
        // Zone B — below Raw, above Polished.
        for (id in listOf(
            "workbench_language_b",
            "workbench_polish_b",
            "workbench_promote_b",
            "workbench_clear_polished",
        )) {
            if (at(id) < at("workbench_raw")) {
                violations += "$id must follow workbench_raw (zone B)"
            }
            if (at(id) > at("workbench_polished")) {
                violations += "$id must precede workbench_polished (zone B)"
            }
        }
        // Zone C — below Polished.
        if (at("workbench_promote_c") < at("workbench_polished")) {
            violations += "workbench_promote_c must follow workbench_polished (zone C)"
        }

        assertWithMessage("the #178 action zones are out of order:\n" + violations.joinToString("\n"))
            .that(violations).isEmpty()
    }

    /** #178: Polished is produced by the pipeline, never typed into. */
    @Test
    fun `polished is a read-only text view while raw stays editable`() {
        val polished = elementById("workbench_polished")
        assertWithMessage("line ${polished.line}: Polished must be a TextView, not an EditText")
            .that(polished.name).isEqualTo("TextView")
        assertWithMessage("line ${polished.line}: Polished must not declare an inputType")
            .that(polished.attributes).doesNotContainKey("android:inputType")
        assertWithMessage("line ${polished.line}: Polished must not be focusable")
            .that(polished.attributes["android:focusable"]).isEqualTo("false")

        val raw = elementById("workbench_raw")
        assertWithMessage("line ${raw.line}: Raw must stay an EditText")
            .that(raw.name).isEqualTo("EditText")
    }

    /** #178: the #173 accordion and the single spinner/repolish row are gone. */
    @Test
    fun `the accordion views and the old polish row are gone`() {
        val declared = parse(layout).mapNotNull { it.attributes["android:id"] }.toSet()
        val removed = listOf(
            "workbench_raw_toggle",
            "workbench_raw_chevron",
            "workbench_raw_preview",
            "workbench_polished_toggle",
            "workbench_polished_chevron",
            "workbench_polished_preview",
            "workbench_repolish",
            "workbench_language",
        )

        val stillThere = removed.filter { "@+id/$it" in declared }
        assertWithMessage("#173 units that #178 replaced are still declared:\n" +
            stillThere.joinToString("\n")).that(stillThere).isEmpty()

        assertWithMessage("the Workbench title must not be a header text any more (#178)")
            .that(layout.readText().contains("@string/workbench_title")).isFalse()
    }

    /**
     * #178: every icon button is tappable without a visible label, so it needs
     * a content description; and no icon button carries a background — the
     * night-mode `?android:attr/selectableItemBackground` sheet is the #173
     * contrast defect.
     */
    @Test
    fun `every icon button has a content description and no background`() {
        val iconButtons = parse(layout).filter { it.name == "ImageButton" }
        assertWithMessage("the layout declares no ImageButton at all — the scan is vacuous")
            .that(iconButtons).isNotEmpty()

        val violations = mutableListOf<String>()
        for (button in iconButtons) {
            if (button.attributes["android:contentDescription"].isNullOrBlank()) {
                violations += "line ${button.line} <ImageButton>: no contentDescription"
            }
            if (button.attributes.containsKey("android:background")) {
                violations += "line ${button.line} <ImageButton>: declares android:background"
            }
        }
        assertWithMessage(
            "icon buttons need a label for TalkBack and no background (#173/#178):\n" +
                violations.joinToString("\n")
        ).that(violations).isEmpty()
    }
}
