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
 *
 * #213 icon wave: the "no own background" rule became "the borderless
 * WorkbenchIconButton style" (the ImageButton default still painted a box),
 * the icons draw 1.5x on a 48dp target, every icon button carries a tooltip,
 * and the header spreads its slots evenly.
 */
class WorkbenchLayoutPolicyTest {

    private data class Element(
        val line: Int,
        val name: String,
        val attributes: Map<String, String>,
        val parent: Int,
        /** The characters directly inside this element (trimmed). */
        var text: String = "",
    )

    private val layout: File = File(moduleDir(), "src/main/res/layout/activity_workbench.xml")

    /** #213 icon wave: the WorkbenchIconButton style lives next to AppButton. */
    private val themes: File = File(moduleDir(), "src/main/res/values/themes.xml")

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
        private val texts = HashMap<Int, StringBuilder>()

        override fun setDocumentLocator(locator: Locator) {
            this.locator = locator
        }

        override fun startElement(uri: String?, localName: String?, qName: String, atts: Attributes) {
            val attributes = LinkedHashMap<String, String>(atts.length)
            for (i in 0 until atts.length) attributes[atts.getQName(i)] = atts.getValue(i)
            elements += Element(locator?.lineNumber ?: -1, qName, attributes, open.lastOrNull() ?: -1)
            open.addLast(elements.lastIndex)
            texts[elements.lastIndex] = StringBuilder()
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val index = open.removeLast()
            elements[index].text = texts.remove(index)?.toString()?.trim().orEmpty()
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            open.lastOrNull()?.let { texts.getValue(it).append(ch, start, length) }
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
     * #178/#189/#213: the zones. Zone A sits above Raw, zone B between Raw and
     * Polished — its actions moved INTO the Polished summary row (#213), so
     * promote/trash still precede the field — and zone C below Polished
     * mirrors the summary row's three actions (#213 revised).
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
            "workbench_clear_polished_c",
            "workbench_promote_c",
            "workbench_copy_polished_bottom",
        )
        fun at(id: String) = order.getValue(id)

        val violations = mutableListOf<String>()
        // Zone A — above Raw.
        for (id in listOf("workbench_language_a", "workbench_polish_a")) {
            if (at(id) > at("workbench_raw")) {
                violations += "$id must precede workbench_raw (zone A)"
            }
        }
        // Zone B — below Raw, above Polished; its actions ride the summary row.
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
        // Zone C — below Polished: the summary row's three actions, in order.
        for (id in listOf(
            "workbench_clear_polished_c",
            "workbench_promote_c",
            "workbench_copy_polished_bottom",
        )) {
            if (at(id) < at("workbench_polished")) {
                violations += "$id must follow workbench_polished (zone C)"
            }
        }

        assertWithMessage("the #178/#213 action zones are out of order:\n" + violations.joinToString("\n"))
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
     * #213: the Polished summary row carries the actions — chevron · label ·
     * trash · promote · copy (exactly that order, the owner's enumeration).
     * The former separate zone-B actions row is gone; the bottom bar under the
     * field mirrors the three actions (#213 revised, same order, right end).
     */
    @Test
    fun `the polished summary row carries trash, promote and copy in that order`() {
        val summary = childrenOf("workbench_polished_toggle")

        assertWithMessage(
            "the summary row must read chevron · label · trash · promote · copy (#213):\n$summary"
        )
            .that(summary)
            .containsExactly(
                "workbench_polished_chevron",
                "<TextView>",
                "workbench_clear_polished_b",
                "workbench_promote_b",
                "workbench_copy_polished",
            )
            .inOrder()

        val bottom = childrenOf("workbench_actions_c")
        assertWithMessage(
            "the bottom bar must mirror the summary row's actions — trash · promote · copy (#213 revised):\n$bottom"
        )
            .that(bottom)
            .containsExactly(
                "workbench_clear_polished_c",
                "workbench_promote_c",
                "workbench_copy_polished_bottom",
            )
            .inOrder()

        val row = elementById("workbench_actions_c")
        assertWithMessage("line ${row.line} workbench_actions_c: the bottom bar must stay horizontal")
            .that(row.attributes["android:orientation"]).isEqualTo("horizontal")
        assertWithMessage("line ${row.line} workbench_actions_c: the bottom bar must align right")
            .that(row.attributes["android:gravity"]).isEqualTo("center_vertical|end")
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
            // chevron is a real drawable now — 32dp after the owner asked for
            // one step larger than the first 24dp cut. Pin the ImageView shape.
            if (chevron.name != "ImageView") {
                violations += "line ${chevron.line} $section: the chevron must be a 32dp " +
                    "ic_chevron_down ImageView, not a text glyph (was ${chevron.name}) — #209"
            } else {
                val chevronWidth = chevron.attributes["android:layout_width"]
                    ?.removeSuffix("dp")?.toIntOrNull() ?: 0
                val chevronHeight = chevron.attributes["android:layout_height"]
                    ?.removeSuffix("dp")?.toIntOrNull() ?: 0
                if (chevron.attributes["android:src"] != "@drawable/ic_chevron_down" ||
                    chevronWidth < 32 ||
                    chevronHeight < 32
                ) {
                    violations += "line ${chevron.line} $section: the chevron must stay a >= 32dp " +
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
     * #213 icon wave: every icon button wears `WorkbenchIconButton` — the
     * borderless ripple stands in for the platform ImageButton default (which
     * painted the bordered sheet the owner read as a box), the touch target is
     * 48dp, the icon draws 1.5x, and each button answers a long press with a
     * native tooltip. The layout only references the style, so the style's
     * own definition in themes.xml is pinned too.
     */
    @Test
    fun `every icon button wears the borderless icon-button style with a tooltip`() {
        val iconButtons = parse(layout).filter { it.name == "ImageButton" }
        assertWithMessage("the layout declares no ImageButton at all — the scan is vacuous")
            .that(iconButtons).isNotEmpty()

        val violations = mutableListOf<String>()
        for (button in iconButtons) {
            val id = button.attributes["android:id"] ?: "<no id>"
            if (button.attributes["android:contentDescription"].isNullOrBlank()) {
                violations += "line ${button.line} $id: no contentDescription"
            }
            if (button.attributes["android:tooltipText"].isNullOrBlank()) {
                violations += "line ${button.line} $id: no tooltipText (the long-press help)"
            }
            if (button.attributes["style"] != "@style/WorkbenchIconButton") {
                violations += "line ${button.line} $id: not the WorkbenchIconButton style"
            }
        }

        val parsed = parse(themes)
        val styleIndex = parsed.indexOfFirst {
            it.name == "style" && it.attributes["name"] == "WorkbenchIconButton"
        }
        if (styleIndex < 0) {
            violations += "themes.xml declares no WorkbenchIconButton style"
        } else {
            val items = parsed
                .filter { it.name == "item" && it.parent == styleIndex }
                .associate { it.attributes["name"] to it.text }
            val expected = mapOf(
                "android:background" to "?android:attr/selectableItemBackgroundBorderless",
                "android:layout_width" to "48dp",
                "android:layout_height" to "48dp",
                "android:scaleX" to "1.5",
                "android:scaleY" to "1.5",
                "android:scaleType" to "centerInside",
            )
            for ((name, value) in expected) {
                if (items[name] != value) {
                    violations += "themes.xml WorkbenchIconButton: $name = ${items[name]} " +
                        "(expected $value)"
                }
            }
        }

        assertWithMessage(
            "icon buttons need a label, the borderless style and the long-press " +
                "tooltip (#213):\n" + violations.joinToString("\n")
        ).that(violations).isEmpty()
    }

    /**
     * #213 icon wave: the header spreads its five children with equal space
     * around each one (owner: "space-around") — every slot divides the row by
     * weight, so no child bunches up at the centre.
     */
    @Test
    fun `the header spreads its five slots evenly`() {
        val headerIds = listOf(
            "workbench_prev",
            "workbench_position",
            "workbench_settings",
            "workbench_new",
            "workbench_next",
        )
        val slots = parse(layout).filter {
            it.attributes["android:id"] in headerIds.map { id -> "@+id/$id" }
        }
        assertWithMessage("the header declares fewer than its five slots — the scan is vacuous")
            .that(slots.map { it.attributes["android:id"] }).hasSize(5)

        val violations = mutableListOf<String>()
        for (slot in slots) {
            val id = slot.attributes["android:id"] ?: "<no id>"
            if (slot.attributes["android:layout_width"] != "0dp" ||
                slot.attributes["android:layout_weight"] != "1"
            ) {
                violations += "line ${slot.line} $id: layout_width=" +
                    "${slot.attributes["android:layout_width"]} weight=" +
                    "${slot.attributes["android:layout_weight"]} — every header slot " +
                    "must divide the row equally (space-around)"
            }
        }

        assertWithMessage("the #213 header is not spread evenly:\n" +
            violations.joinToString("\n")).that(violations).isEmpty()
    }
}
