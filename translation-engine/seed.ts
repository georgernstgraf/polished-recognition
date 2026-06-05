import { getPrisma } from "./lib/prisma.ts";

const prisma = await getPrisma();

try {
    // ── 1. Seed languages from filesystem values-* dirs ────────────────
    const iso639: Record<string, { name: string | null; alpha_3: string | null }> =
        JSON.parse(
            await Deno.readTextFile(
                new URL("resources/iso639.json", import.meta.url),
            ),
        );
    const whisperMap: Record<string, string> = JSON.parse(
        await Deno.readTextFile(
            new URL("resources/whisper_languages.json", import.meta.url),
        ),
    );

    const resPath = new URL(
        "../app/src/main/res/",
        import.meta.url,
    );
    const foundDirs: string[] = [];
    let langCount = 0;

    for await (const entry of Deno.readDir(resPath.pathname)) {
        if (!entry.name.startsWith("values-") || !entry.isDirectory) continue;

        const dirName = entry.name;
        foundDirs.push(dirName);

        // Parse BCP 47 from directory name
        const tag = dirName.slice("values-".length);
        let bcp47: string;
        if (tag.startsWith("b+")) {
            const parts = tag.slice(2).split("+");
            bcp47 = parts.join("-");
        } else {
            bcp47 = tag.replace(/-r([A-Z]{2})/g, (_: string, r: string) => `-${r}`);
        }

        // Parse POSIX locale
        const bcpParts = bcp47.split("-");
        const langPrimary = bcpParts[0];
        const remaining = bcpParts.slice(1);
        let region: string | undefined;
        let script: string | undefined;
        for (const part of remaining) {
            if (part.length === 2 && /^[A-Z]{2}$/.test(part)) {
                region = part;
            } else if (
                part.length === 4 && /^[A-Z][a-z]{3}$/.test(part)
            ) {
                script = part;
            }
        }
        let posix = langPrimary;
        if (region) posix += `_${region}`;
        if (script) posix += `@${script.toLowerCase()}`;

        // Look up ISO 639 and Whisper names
        const primaryKey = langPrimary.toLowerCase();
        const isoEntry = iso639[primaryKey];
        const iso3 = isoEntry?.alpha_3 || primaryKey;
        const englishName = isoEntry?.name || null;
        const whisperName = whisperMap[primaryKey] || null;
        const name = englishName || primaryKey;

        await prisma.languages.upsert({
            where: { bcp_47: bcp47 },
            update: {
                posix_code: posix,
                iso_639_3: iso3,
                directory: dirName,
                english_name: name,
                whisper_response: whisperName,
            },
            create: {
                bcp_47: bcp47,
                posix_code: posix,
                iso_639_3: iso3,
                directory: dirName,
                english_name: name,
                whisper_response: whisperName,
            },
        });
        langCount++;
    }

    const langRemoved = await prisma.languages.deleteMany({
        where: { directory: { notIn: foundDirs } },
    });
    if (langRemoved.count > 0) {
        console.log(`✔ Removed ${langRemoved.count} obsolete languages`);
    }
    console.log(`✔ Seeded ${langCount} languages`);

    // ── 2. Seed LLM models ────────────────────────────────────────────
    const models: Array<{ name: string }> = JSON.parse(
        await Deno.readTextFile(
            new URL("resources/llmmodels_master.json", import.meta.url),
        ),
    );

    for (const m of models) {
        await prisma.llm_models.upsert({
            where: { name: m.name },
            update: {},
            create: { name: m.name },
        });
    }
    console.log(`✔ Seeded ${models.length} LLM models`);

    const modelNames = new Set(models.map(m => m.name));
    const removedModels = await prisma.llm_models.deleteMany({
        where: { name: { notIn: [...modelNames] } },
    });
    if (removedModels.count > 0) {
        console.log(`✔ Removed ${removedModels.count} obsolete LLM models`);
    }

    // ── 3. Seed master strings from English strings.xml ──────────────
    const xml = await Deno.readTextFile(
        new URL("../app/src/main/res/values/strings.xml", import.meta.url),
    );

    let count = 0;
    let skipped = 0;
    const currentTexts: string[] = [];
    const stringRegex = /<string\s+name="([^"]*)"([^>]*)>([\s\S]*?)<\/string>/g;
    let match: RegExpExecArray | null;
    while ((match = stringRegex.exec(xml)) !== null) {
        const attrs = match[2];
        const text = match[3].trim();
        if (!text) continue;
        if (/translatable\s*=\s*"false"/.test(attrs)) {
            skipped++;
            continue;
        }
        // Decode common XML entities
        const decoded = text
            .replace(/&lt;/g, "<")
            .replace(/&gt;/g, ">")
            .replace(/&amp;/g, "&")
            .replace(/&quot;/g, '"')
            .replace(/&#39;/g, "'");
        await prisma.master_strings.upsert({
            where: { text: decoded },
            update: {},
            create: { text: decoded },
        });
        currentTexts.push(decoded);
        count++;
    }

    const removed = await prisma.master_strings.deleteMany({
        where: { text: { notIn: currentTexts } },
    });
    if (removed.count > 0) {
        console.log(
            `✔ Removed ${removed.count} obsolete master strings (cascaded to votes)`,
        );
    }

    console.log(
        `✔ Seeded ${count} master strings (skipped ${skipped} non-translatable)`,
    );
} finally {
    await prisma.$disconnect();
}
