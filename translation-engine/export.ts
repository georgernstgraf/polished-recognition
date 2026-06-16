import {
    getEvaluation,
    getLanguagesWithVotes,
    getMasterStringByText,
    TRANSLATION_SCORE_THRESHOLD,
} from "./lib/db.ts";

// ── XML Helpers ────────────────────────────────────────────────────────────

function escapeXml(text: string): string {
    return text
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/(?<!\\)'/g, "\\'")
        .replace(/(?<!\\)"/g, '\\"')
        .replace(/\\&amp;/g, "&amp;");
}

function decodeXml(text: string): string {
    return text
        .replace(/&lt;/g, "<")
        .replace(/&gt;/g, ">")
        .replace(/&amp;/g, "&")
        .replace(/&quot;/g, '"')
        .replace(/&#39;/g, "'");
}

// ── CLI Args ───────────────────────────────────────────────────────────────

function parseArgs(): { targetDir: string } {
    let targetDir = "";
    for (const arg of Deno.args) {
        if (arg.startsWith("--target=")) {
            targetDir = arg.substring("--target=".length);
        }
    }
    if (!targetDir) {
        console.error("Error: --target=<directory> is required.");
        console.error(
            "Usage: deno run -A export.ts -- --target=<directory>",
        );
        Deno.exit(1);
    }
    return { targetDir };
}

// ── Data Types ──────────────────────────────────────────────────────────────

interface SourceString {
    key: string;
    id: number; // master_stringsId
    text: string; // decoded English text
}

// ── Main ────────────────────────────────────────────────────────────────────

async function main() {
    const { targetDir } = parseArgs();

    // ── 1. Load keep_english keys ─────────────────────────────────────
    const keepEnglish = new Set<string>();
    try {
        const keepJson = JSON.parse(
            await Deno.readTextFile(
                new URL("../scripts/keep_english.json", import.meta.url),
            ),
        );
        if (Array.isArray(keepJson)) {
            for (const k of keepJson) keepEnglish.add(k);
        }
        console.log(
            `keep_english.json: ${keepEnglish.size} keys excluded`,
        );
    } catch {
        console.warn("No keep_english.json found — exporting all keys");
    }

    // ── 2. Read English master strings.xml ────────────────────────────
    const masterPath = new URL(
        "../app/src/main/res/values/strings.xml",
        import.meta.url,
    );
    const masterXml = await Deno.readTextFile(masterPath);

    // Parse translatable keys from master (same logic as seed.ts)
    const sourceStrings: SourceString[] = [];
    const stringRegex =
        /<string\s+name="([^"]*)"([^>]*)>([\s\S]*?)<\/string>/g;
    let match: RegExpExecArray | null;
    while ((match = stringRegex.exec(masterXml)) !== null) {
        const attrs = match[2];
        const text = match[3].trim();
        if (!text) continue;
        if (/translatable\s*=\s*"false"/.test(attrs)) continue;
        if (keepEnglish.has(match[1])) {
            console.log(`  keep_english: skipping "${match[1]}"`);
            continue;
        }

        const decoded = decodeXml(text);
        const ms = await getMasterStringByText(decoded);
        if (!ms) {
            console.warn(`Warning: No DB entry for string "${match[1]}"`);
            continue;
        }
        sourceStrings.push({ key: match[1], id: ms.id, text: decoded });
    }
    console.log(
        `Parsed ${sourceStrings.length} translatable keys from master`,
    );

    // ── 3. Get all languages with votes ──────────────────────────────
    const languages = await getLanguagesWithVotes();
    console.log(`Found ${languages.length} languages with votes`);

    // ── 4. Delete existing values-* directories in target ─────────────
    for await (const entry of Deno.readDir(targetDir)) {
        if (entry.isDirectory && entry.name.startsWith("values-")) {
            await Deno.remove(`${targetDir}/${entry.name}`, {
                recursive: true,
            });
        }
    }

    // ── 5. Per language: pick tiebreak winner, write XML ─────────────
    let totalWritten = 0;

    for (const lang of languages) {
        const evaluation = await getEvaluation(lang.id);

        // First entry per master_stringsId is the tiebreak winner
        const winners = new Map<number, string>();
        for (const row of evaluation) {
            if (!winners.has(row.master_stringsId) && row.score >= TRANSLATION_SCORE_THRESHOLD) {
                winners.set(row.master_stringsId, row.translation);
            }
        }

        // Collect translations, sort alphabetically by key
        const entries: { key: string; text: string }[] = [];
        for (const src of sourceStrings) {
            const translation = winners.get(src.id);
            if (!translation) continue;
            // Skip translations that broke \'%s\' quote structure
            if (src.text.includes("\\'%s\\'") && !translation.includes("\\'%s\\'")) {
                continue;
            }
            entries.push({ key: src.key, text: translation });
        }
        entries.sort((a, b) => a.key.localeCompare(b.key));

        if (entries.length === 0) continue;

        const lines: string[] = [];
        lines.push('<?xml version="1.0" encoding="utf-8"?>');
        lines.push("<resources>");

        for (const e of entries) {
            lines.push(
                `    <string name="${e.key}">${escapeXml(e.text)}</string>`,
            );
        }

        lines.push("</resources>");

        const langDir = `${targetDir}/${lang.directory}`;
        await Deno.mkdir(langDir, { recursive: true });
        await Deno.writeTextFile(
            `${langDir}/strings.xml`,
            lines.join("\n") + "\n",
        );

        console.log(
            `  ${lang.directory}: ${entries.length}/${sourceStrings.length} strings`,
        );
        totalWritten += entries.length;
    }

    console.log(
        `\nDone: ${totalWritten} strings written across ${languages.length} languages.`,
    );
    console.log(`Target: ${targetDir}/`);
}

if (import.meta.main) {
    await main();
}
