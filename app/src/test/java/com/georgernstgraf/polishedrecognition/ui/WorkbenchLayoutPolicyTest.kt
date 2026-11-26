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
 *
 * #189 brings the `<summary>` accordion back for BOTH sections and makes the
 * zone-B and zone-C action rows identical (promote + trash), so the old
 * "the accordion is gone" rule is inverted here.
 */
class WorkbenchLayoutPolicyTest {

    private data class Element(
        val line: Int,
        val name: String,
        val attributes: Map<String, String>,
        val parent: Int,
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
        private val open = ArrayDeque<Int>()

        override fun setDocumentLocator(locator: Locator) {
            this.locator = locator
        }

        override fun startElement(uri: String?, localName: String?, qName: String, atts: Attributes) {
            val attributes = LinkedHashMap<String, String>(atts.length)
            for (i in 0 until atts.length) attributes[atts.getQName(i)] = atts.getValue(i)
            elements += Element(locator?.lineNumber ?: -1, qName, attributes, open.lastOrNull() ?: -1)
            open.addLast(elements.lastIndex)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            open.removeLast()
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
     * #178/#189: the three action zones. Zone A sits above Raw, zone B between
     * Raw and Polished, zone C below Polished — the order in the file is the
     * order on the screen, so it is the assertion. Since #189 both action rows
     * carry the same promote + empty pair, so the trash can sits in the bottom
     * bar under Polished too, not only under Raw.
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
            "workbench_clear_polished_b",
            "workbench_polished",
            "workbench_promote_c",
            "workbench_clear_polished_c",
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
            "workbench_clear_polished_b",
        )) {
            if (at(id) < at("workbench_raw")) {
                violations += "$id must follow workbench_raw (zone B)"
            }
            if (at(id) > at("workbench_polished")) {
                violations += "$id must precede workbench_polished (zone B)"
            }
        }
        // Zone C — below Polished.
        for (id in listOf("workbench_promote_c", "workbench_clear_polished_c")) {
            if (at(id) < at("workbench_polished")) {
                violations += "$id must follow workbench_polished (zone C)"
            }
        }

        assertWithMessage("the #178 action zones are out of order:\n" + violations.joinToString("\n"))
            .that(violations).isEmpty()
    }

    /** The ids of the views directly inside the element with [id]. */
    private fun childrenOf(id: String): List<String> {
        val elements = parse(layout)
        val index = elements.indexOfFirst { it.attributes["android:id"] == "@+id/$id" }
            .also { if (it < 0) throw AssertionError("activity_workbench.xml declares no element with id $id") }
        return elements
            .filter { it.parent == index }
            .map { it.attributes["android:id"]?.removePrefix("@+id/") ?: "<${it.name}>" }
    }

    /**
     * #189: the two action rows are IDENTICAL — same buttons, same order, same
     * alignment. A layout where one site grows an extra control is exactly the
     * drift this pins.
     */
    @Test
    fun `the action rows in zones B and C are identical`() {
        val zoneB = childrenOf("workbench_actions_b")
        val zoneC = childrenOf("workbench_actions_c")

        assertWithMessage("zone B must hold promote + the trash can in that order:\n$zoneB")
            .that(zoneB)
            .containsExactly("workbench_promote_b", "workbench_clear_polished_b")
            .inOrder()
        assertWithMessage("zone C must mirror zone B:\n$zoneC")
            .that(zoneC)
            .containsExactly("workbench_promote_c", "workbench_clear_polished_c")
            .inOrder()

        for (id in listOf("workbench_actions_b", "workbench_actions_c")) {
            val row = elementById(id)
            assertWithMessage("line ${row.line} $id: the action row must stay horizontal")
                .that(row.attributes["android:orientation"]).isEqualTo("horizontal")
            assertWithMessage("line ${row.line} $id: the action row must align its icons the same way")
                .that(row.attributes["android:gravity"]).isEqualTo("center_vertical|end")
        }
    }

    /**
     * #191: the actions sit DIRECTLY under the when-line and above the
     * language/Polish zone — the reason and the way out stay together at the
     * top of an incomplete page (the device check paged through the whole ring
     * before finding Retry STT at the bottom). The file order is the screen
     * order, so it is the assertion.
     */
    @Test
    fun `the leftover actions sit directly under the when-line`() {
        val order = orderOf(
            "workbench_recovery",
            "workbench_stamp",
            "workbench_leftover_actions",
            "workbench_language_a",
        )
        fun at(id: String) = order.getValue(id)

        val violations = mutableListOf<String>()
        if (at("workbench_recovery") > at("workbench_stamp")) {
            violations += "the banner must precede the when-line (#191: directly under the header)"
        }
        if (at("workbench_leftover_actions") < at("workbench_stamp")) {
            violations += "workbench_leftover_actions must follow workbench_stamp (#191)"
        }
        if (at("workbench_leftover_actions") > at("workbench_language_a")) {
            violations += "workbench_leftover_actions must precede the language/Polish zone (#191)"
        }

        assertWithMessage("the #191 order on an incomplete page is out of order:\n" +
            violations.joinToString("\n")).that(violations).isEmpty()
    }

    /**
     * #191: the row must never scroll on its own and must never have a fixed
     * width — the long labels wrap inside equal-weight buttons instead.
     */
    @Test
    fun `the leftover actions are a wrapping-safe horizontal row`() {
        val row = elementById("workbench_leftover_actions")
        assertWithMessage("line ${row.line}: the leftover actions must stay horizontal")
            .that(row.attributes["android:orientation"]).isEqualTo("horizontal")
        assertWithMessage("line ${row.line}: the row must grow with its buttons")
            .that(row.attributes["android:layout_height"]).isEqualTo("wrap_content")

        val buttons = parse(layout).filter {
            it.attributes["android:id"] in setOf(
                "@+id/workbench_retry_stt",
                "@+id/workbench_restore_text",
                "@+id/workbench_share_audio",
                "@+id/workbench_dismiss_leftover",
            )
        }
        assertWithMessage("the leftover row declares no Button at all — the scan is vacuous")
            .that(buttons).hasSize(4)
        val fixed = buttons.filter {
            it.attributes["android:layout_width"] != "0dp" ||
                it.attributes["android:layout_weight"] != "1"
        }.map { "line ${it.line} ${it.attributes["android:id"]}: " +
            "layout_width=\"${it.attributes["android:layout_width"]}\" " +
            "layout_weight=\"${it.attributes["android:layout_weight"]}\"" }
        assertWithMessage("each leftover button must divide the row by weight, not by a " +
            "fixed width (#191):\n" + fixed.joinToString("\n")).that(fixed).isEmpty()
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

    /**
     * #189: both sections are `<summary>`-style accordions again — the #173
     * shape #178 had dropped. The label row is the toggle, the chevron states
     * which way a tap goes, and a collapsed section keeps a one-line,
     * ellipsised preview between the row and its field. Only the old single
     * Polish row and its language field stay gone.
     */
    @Test
    fun `both sections are summary-style accordions above their fields`() {
        val order = orderOf(
            "workbench_raw_toggle",
            "workbench_raw_chevron",
            "workbench_copy_raw",
            "workbench_raw_preview",
            "workbench_raw",
            "workbench_polished_toggle",
            "workbench_polished_chevron",
            "workbench_copy_polished",
            "workbench_polished_preview",
            "workbench_polished",
        )
        fun at(id: String) = order.getValue(id)

        val violations = mutableListOf<String>()
        val sections = mapOf(
            "raw" to "workbench_raw",
            "polished" to "workbench_polished",
        )
        for ((section, field) in sections) {
            val walk = listOf(
                "workbench_${section}_toggle",
                "workbench_${section}_chevron",
                "workbench_copy_$section",
                "workbench_${section}_preview",
            )
            if (walk.map { at(it) } != walk.map { at(it) }.sorted()) {
                violations += "$section: the row must read toggle → chevron → copy → preview"
            }
            if (at("workbench_${section}_preview") > at(field)) {
                violations += "$section: preview must precede its field"
            }
        }

        for ((section, field) in sections) {
            val toggle = elementById("workbench_${section}_toggle")
            if (toggle.attributes["android:clickable"] != "true") {
                violations += "line ${toggle.line} ${section}: the label row must be clickable"
            }
            if (toggle.attributes["android:focusable"] != "true") {
                violations += "line ${toggle.line} ${section}: the label row must be focusable (TalkBack)"
            }
            if (toggle.attributes["android:contentDescription"].isNullOrBlank()) {
                violations += "line ${toggle.line} ${section}: the toggle needs a content description"
            }
            val chevron = elementById("workbench_${section}_chevron")
            if (chevron.attributes["android:importantForAccessibility"] != "no") {
                violations += "line ${chevron.line} ${section}: the chevron must not be announced"
            }
            // #209 follow-up: the glyph was still read as too small, so the
            // chevron is a real drawable now. Pin the ImageView shape.
            if (chevron.name != "ImageView") {
                violations += "line ${chevron.line} $section: the chevron must be a 24dp " +
                    "ic_chevron_down ImageView, not a text glyph (was ${chevron.name}) — #209"
            } else {
                val chevronWidth = chevron.attributes["android:layout_width"]
                    ?.removeSuffix("dp")?.toIntOrNull() ?: 0
                val chevronHeight = chevron.attributes["android:layout_height"]
                    ?.removeSuffix("dp")?.toIntOrNull() ?: 0
                if (chevron.attributes["android:src"] != "@drawable/ic_chevron_down" ||
                    chevronWidth < 24 ||
                    chevronHeight < 24
                ) {
                    violations += "line ${chevron.line} $section: the chevron must stay a >= 24dp " +
                        "ic_chevron_down ImageView (was ${chevronWidth}x$chevronHeight) — #209"
                }
            }
            val preview = elementById("workbench_${section}_preview")
            if (preview.attributes["android:maxLines"] != "1" ||
                preview.attributes["android:ellipsize"] != "end"
            ) {
                violations += "line ${preview.line} ${section}: the collapsed preview must stay one " +
                    "ellipsised line"
            }
            if (preview.attributes["android:visibility"] != "gone") {
                violations += "line ${preview.line} ${section}: the preview only exists while collapsed"
            }
            if (elementById(field).attributes["android:layout_width"] != "match_parent") {
                violations += "$section: the field must span the page"
            }
        }

        assertWithMessage("the #189 accordions are not a summary/details pair:\n" +
            violations.joinToString("\n")).that(violations).isEmpty()

        val declared = parse(layout).mapNotNull { it.attributes["android:id"] }.toSet()
        val removed = listOf("workbench_repolish", "workbench_language")
        val stillThere = removed.filter { "@+id/$it" in declared }
        assertWithMessage("the single #171 polish row must stay gone:\n" +
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
