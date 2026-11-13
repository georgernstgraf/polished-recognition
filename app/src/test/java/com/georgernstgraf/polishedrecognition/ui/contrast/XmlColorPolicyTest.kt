package com.georgernstgraf.polishedrecognition.ui.contrast

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.xml.sax.Attributes
import org.xml.sax.Locator
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import javax.xml.parsers.SAXParserFactory

/**
 * #163 — the STATIC colour policy for the resource XML.
 *
 * Pure JUnit: no Robolectric, no `android.jar`, no resource compaction. The files are read
 * from disk as text, so the test also sees declarations that the platform would merge away
 * and it runs in milliseconds.
 *
 * **Why SAX and not `XmlPullParser`.** `org.xmlpull.v1` ships no implementation on a plain
 * JVM test classpath (`android.jar` provides interfaces only, and kxml2 would be a new
 * dependency), so the JDK's own SAX parser — the same event-based model, with a locator for
 * line numbers — reads the files. It is `javax.xml.parsers`, i.e. still dependency-free.
 *
 * Rules, in the order of the issue:
 *  1. **No raw colour literals** (`#RGB`, `#ARGB`, `#RRGGBB`, `#AARRGGBB`) in any attribute
 *     value of every layout or xml resource. One allow-listed exception, with the
 *     reason spelled out at [RAW_LITERAL_ALLOWED].
 *  2. **No bare theme attribute** (`?attr/…`): only `?android:attr/…` resolves in this
 *     platform-only app — the AppCompat/Material attrs are not on the classpath.
 *  3. **Every `@color/NAME` exists in BOTH `values/colors.xml` and `values-night/colors.xml`**
 *     (`@android:color/…` is the platform's, not ours).
 *  4. **Palette parity both ways** between those two files (PITFALLS #59: a colour added to
 *     only one mode is invisible in the other).
 *  5. **The `recording_*` pair reaches 4.5:1 in both modes** — the IME overlay is translucent,
 *     so the text is measured against the COMPOSITED overlay, not against the raw colour.
 *  6. **The lint baseline never carries a `TextContrast` entry** — the contrast gate must stay
 *     a build failure, never a backlog item.
 *
 * Scope notes:
 *  - Palette parity is checked for `values/colors.xml` ↔ `values-night/colors.xml` only.
 *    `values/ic_launcher_background.xml` (launcher icon, single mode by design) and colour
 *    literals inside drawables are OUT of scope: neither is app UI text contrast.
 *  - `themes.xml` is out of scope too: it references the `@color/text_*` SELECTORS in
 *    `res/color/`, which are not `<color>` entries in `colors.xml`. The #161 audit and
 *    [TextPaletteContrastTest] cover those.
 *  - Programmatic colours (`Color.parseColor`, `setTextColor(0x…)` in Kotlin) are invisible to
 *    a resource scan; #161/#162 measure what those views actually paint.
 */
class XmlColorPolicyTest {

    // ------------------------------------------------------------------ rule 1

    @Test
    fun `no layout or xml attribute carries a raw colour literal`() {
        val violations = mutableListOf<String>()
        var attributes = 0

        for (file in scannedFiles) {
            if (file.name in RAW_LITERAL_ALLOWED) continue
            for (element in parse(file)) {
                for ((name, value) in element.attributes) {
                    attributes++
                    if (RAW_LITERAL.containsMatchIn(value)) {
                        violations += "${element.where()} $name=\"$value\" is a raw colour " +
                            "literal; declare it as @color/NAME in values/colors.xml AND " +
                            "values-night/colors.xml"
                    }
                }
            }
        }

        assertWithMessage(
            "raw colour literals bypass the theme and the night palette:\n" +
                violations.joinToString("\n")
        ).that(violations).isEmpty()

        // A rule that scans nothing passes vacuously — the layouts alone carry > 100
        // attributes, so a much lower number means the scan broke, not that the tree is clean.
        assertWithMessage("the attribute scan collapsed — only $attributes attributes read")
            .that(attributes).isGreaterThan(100)
    }

    // ------------------------------------------------------------------ rule 2

    @Test
    fun `no theme attribute reference outside the android namespace`() {
        val violations = bareAttributeViolations(scannedFiles.flatMap { parse(it) })

        assertWithMessage(
            "theme attributes outside the android namespace do not resolve here:\n" +
                violations.joinToString("\n")
        ).that(violations).isEmpty()
    }

    /**
     * The detector must detect. A bare `?attr/` normally never reaches this test: aapt2 fails
     * the resource link first ("resource attr/textColorPrimary … not found", proven in #163).
     * The rule is the defence for the case aapt2 would accept — a bare reference to an
     * attribute that some LIBRARY defines — so it is proven on a synthetic element here.
     */
    @Test
    fun `the bare-attribute rule flags a synthetic reference and passes the android namespace`() {
        val bare = Element(
            "probe_layout.xml", 7, "TextView",
            linkedMapOf("android:textColor" to "?attr/textColorPrimary"), "",
        )
        assertWithMessage("a bare ?attr/ reference must be flagged — otherwise the rule is vacuous")
            .that(bareAttributeViolations(listOf(bare))).hasSize(1)

        val platform = Element(
            "probe_layout.xml", 7, "TextView",
            linkedMapOf("android:textColor" to "?android:attr/textColorPrimary"), "",
        )
        assertWithMessage("?android:attr/ is the accepted form and must not be flagged")
            .that(bareAttributeViolations(listOf(platform))).isEmpty()
    }

    private fun bareAttributeViolations(elements: List<Element>): List<String> {
        val violations = mutableListOf<String>()
        for (element in elements) {
            for ((name, value) in element.attributes) {
                if (!value.startsWith("?")) continue
                if (value.startsWith(ANDROID_ATTR)) continue
                violations += "${element.where()} $name=\"$value\" references a non-platform " +
                    "theme attribute; only $ANDROID_ATTR resolves in this platform-only app"
            }
        }
        return violations
    }

    // ------------------------------------------------------------------ rule 3

    @Test
    fun `every colour reference exists in both palette files`() {
        val light = palette(LIGHT_DIR)
        val night = palette(NIGHT_DIR)
        val violations = mutableListOf<String>()
        var references = 0

        for (file in scannedFiles) {
            for (element in parse(file)) {
                for ((name, value) in element.attributes) {
                    val colour = colourReference(value) ?: continue
                    references++
                    if (colour !in light) {
                        violations += "${element.where()} $name=\"$value\" — @color/$colour is " +
                            "missing from $LIGHT_DIR/colors.xml"
                    }
                    if (colour !in night) {
                        violations += "${element.where()} $name=\"$value\" — @color/$colour is " +
                            "missing from $NIGHT_DIR/colors.xml (PITFALLS #59)"
                    }
                }
            }
        }

        assertWithMessage(
            "colour references must resolve in both modes:\n" + violations.joinToString("\n")
        ).that(violations).isEmpty()
        assertWithMessage("no @color reference was found at all — the scan is vacuous")
            .that(references).isGreaterThan(5)
    }

    // ------------------------------------------------------------------ rule 4

    @Test
    fun `the light and night palettes declare the same colour names`() {
        val light = palette(LIGHT_DIR)
        val night = palette(NIGHT_DIR)

        val lightOnly = (light - night).sorted()
        val nightOnly = (night - light).sorted()

        assertWithMessage(
            "$LIGHT_DIR/colors.xml declares ${lightOnly.size} colour(s) that " +
                "$NIGHT_DIR/colors.xml does not: $lightOnly (PITFALLS #59 — the colour is " +
                "undefined in night mode, so the resource resolves to nothing there)"
        ).that(lightOnly).isEmpty()

        assertWithMessage(
            "$NIGHT_DIR/colors.xml declares ${nightOnly.size} colour(s) that " +
                "$LIGHT_DIR/colors.xml does not: $nightOnly (PITFALLS #59 — the colour is " +
                "undefined in light mode, so the resource resolves to nothing there)"
        ).that(nightOnly).isEmpty()
    }

    // ------------------------------------------------------------------ rule 5

    @Test
    fun `recording text reaches 4_5 to 1 on the translucently composited overlay - light`() {
        assertRecordingPair(LIGHT_DIR, LIGHT_WINDOW)
    }

    @Test
    fun `recording text reaches 4_5 to 1 on the translucently composited overlay - night`() {
        assertRecordingPair(NIGHT_DIR, NIGHT_WINDOW)
    }

    /**
     * The IME overlay is `#E6` translucent, so the pixels the user sees are the overlay
     * COMPOSITED over the window background — the raw `recording_overlay` value alone is not a
     * colour anybody looks at. Both the text and the composited background go through
     * [Contrast], the shared #161 arithmetic; nothing here re-implements WCAG.
     */
    private fun assertRecordingPair(paletteDir: String, window: Int) {
        val text = colour(paletteDir, "recording_text_primary")
        val overlay = colour(paletteDir, "recording_overlay")
        val painted = Contrast.compositeOver(overlay, window)
        val ratio = Contrast.contrastRatio(text, painted)
        val message = "recording_text_primary ${Contrast.hex(text)} on " +
            "recording_overlay ${Contrast.hex(overlay)} composited over " +
            "the window ${Contrast.hex(window)} = ${Contrast.formatRatio(ratio)}:1"

        assertWithMessage("$message — below the 4.5:1 WCAG floor for normal text")
            .that(ratio).isAtLeast(4.5)
        assertWithMessage("$message — must be opaque after compositing")
            .that(Contrast.alpha(painted)).isEqualTo(0xFF)
    }

    // ------------------------------------------------------------------ rule 6

    @Test
    fun `the lint baseline never carries a contrast finding`() {
        val module = moduleDir()
        val lintConfig = File(module, "lint.xml")
        val baseline = File(module, "lint-baseline.xml")

        assertWithMessage("app/lint.xml is missing").that(lintConfig.isFile).isTrue()
        assertWithMessage(
            "app/lint.xml must declare TextContrast as an error — it is the one check that " +
                "may never be downgraded to a backlog item"
        ).that(lintConfig.readText()).contains("id=\"TextContrast\" severity=\"error\"")

        assertWithMessage(
            "app/lint-baseline.xml is missing — regenerate it with " +
                "./gradlew :app:updateLintBaseline"
        ).that(baseline.isFile).isTrue()
        assertWithMessage(
            "the lint baseline contains a TextContrast entry — contrast must fail the build, " +
                "never be baselined"
        ).that(baseline.readText().contains("TextContrast")).isFalse()
    }

    // ------------------------------------------------------------------ scanning

    /**
     * Every layout and xml resource must have been read, so a renamed directory
     * or a swallowed parse error cannot silently shrink the policy's reach.
     */
    @Test
    fun `the scan covers every layout and xml resource`() {
        val res = File(moduleDir(), "src/main/res")
        listOf("layout", "xml").forEach { kind ->
            val expected = File(res, kind).listFiles { f -> f.isFile && f.extension == "xml" }
                .orEmpty().map { it.name }.sorted()
            val scanned = scannedFiles.filter { it.parentFile?.name == kind }.map { it.name }
            assertWithMessage("res/$kind is not fully covered by the policy test")
                .that(scanned).containsExactlyElementsIn(expected)
            assertWithMessage("res/$kind holds no XML — the policy test is vacuous")
                .that(expected).isNotEmpty()
        }
        assertWithMessage("res/layout must hold the eight known layouts")
            .that(scannedFiles.count { it.parentFile?.name == "layout" }).isAtLeast(8)
    }

    // ------------------------------------------------------------------ plumbing

    private data class Element(
        val file: String,
        val line: Int,
        val name: String,
        val attributes: Map<String, String>,
        var text: String,
    ) {
        fun where(): String = "$file:$line"
    }

    private fun parse(file: File): List<Element> {
        val collector = Collector(file.name)
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        file.inputStream().use { factory.newSAXParser().parse(it, collector) }
        return collector.elements
    }

    private class Collector(private val fileName: String) : DefaultHandler() {
        val elements = mutableListOf<Element>()
        private val open = mutableListOf<Pair<Element, StringBuilder>>()
        private var locator: Locator? = null

        override fun setDocumentLocator(locator: Locator) {
            this.locator = locator
        }

        override fun startElement(uri: String?, localName: String?, qName: String, atts: Attributes) {
            val attributes = LinkedHashMap<String, String>(atts.length)
            for (i in 0 until atts.length) attributes[atts.getQName(i)] = atts.getValue(i)
            val element = Element(fileName, locator?.lineNumber ?: -1, qName, attributes, "")
            elements += element
            open += element to StringBuilder()
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (open.isEmpty()) return
            open.last().second.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (open.isEmpty()) return
            val (element, text) = open.removeAt(open.lastIndex)
            element.text = text.toString().trim()
        }
    }

    /** `<color name>` entries of one palette file. */
    private fun palette(dir: String): Set<String> =
        parse(File(resDir(), "$dir/colors.xml"))
            .filter { it.name == "color" }
            .mapNotNull { it.attributes["name"] }
            .toSet()

    private fun colour(dir: String, name: String): Int {
        val element = parse(File(resDir(), "$dir/colors.xml"))
            .firstOrNull { it.name == "color" && it.attributes["name"] == name }
            ?: throw AssertionError("$dir/colors.xml declares no <color name=\"$name\">")
        return parseArgb(element.text)
    }

    /** `@color/NAME` → `NAME`; `@android:color/…`, `?android:attr/…` and plain values → null. */
    private fun colourReference(value: String): String? =
        if (value.startsWith(OUR_COLOR_PREFIX)) value.removePrefix(OUR_COLOR_PREFIX) else null

    private fun parseArgb(raw: String): Int {
        val hex = raw.removePrefix("#")
        val digits = hex.filter { it in "0123456789abcdefABCDEF" }
        require(digits.length == hex.length) { "\"$raw\" is not a #RRGGBB colour" }
        val value = digits.toLong(16)
        return when (digits.length) {
            3 -> Contrast.argb(
                0xFF,
                ((value shr 8) and 0xF).toInt() * 17,
                ((value shr 4) and 0xF).toInt() * 17,
                (value and 0xF).toInt() * 17,
            )
            4 -> Contrast.argb(
                ((value shr 12) and 0xF).toInt() * 17,
                ((value shr 8) and 0xF).toInt() * 17,
                ((value shr 4) and 0xF).toInt() * 17,
                (value and 0xF).toInt() * 17,
            )
            6 -> (value or 0xFF000000L).toInt()
            8 -> value.toInt()
            else -> throw AssertionError("\"$raw\" is not a #RGB/#ARGB/#RRGGBB/#AARRGGBB colour")
        }
    }

    private fun resDir(): File = File(moduleDir(), "src/main/res")

    /**
     * The `app` module directory. Unit tests run with the module as working directory, but the
     * walk up keeps the test correct when the host changes that (it looks for `src/main/res`).
     */
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

    private companion object {
        const val LIGHT_DIR = "values"
        const val NIGHT_DIR = "values-night"

        /** The app window backgrounds the overlays are composited over (#161). */
        const val LIGHT_WINDOW = 0xFFF0F0F3.toInt()
        const val NIGHT_WINDOW = 0xFF191C1E.toInt()

        const val ANDROID_ATTR = "?android:attr/"
        const val OUR_COLOR_PREFIX = "@color/"

        /** `#RGB`, `#ARGB`, `#RRGGBB`, `#AARRGGBB` — longest first so the match is greedy. */
        val RAW_LITERAL = Regex("#(?:[0-9A-Fa-f]{8}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{4}|[0-9A-Fa-f]{3})(?![0-9A-Fa-f])")

        /**
         * The ONE deliberate raw-literal file: the language dropdown must keep black-on-white
         * regardless of the theme (#44) — a themed dropdown row renders unreadable on the
         * dark OxygenOS spinner. Pinned here instead of silently tolerated.
         */
        val RAW_LITERAL_ALLOWED = setOf("ime_spinner_dropdown_item.xml")
    }

    /** Every layout and xml resource, sorted for a stable failure message. */
    private val scannedFiles: List<File> by lazy {
        val res = resDir()
        listOf("layout", "xml").flatMap { kind ->
            File(res, kind).listFiles { f -> f.isFile && f.extension == "xml" }.orEmpty().toList()
        }.sortedBy { it.path }
    }
}
