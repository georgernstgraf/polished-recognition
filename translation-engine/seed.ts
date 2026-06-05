import { getPrisma } from "./lib/prisma.ts";

async function seedLanguages(prisma: any) {
    const languages = JSON.parse(
        await Deno.readTextFile(
            new URL("resources/supported_languages.json", import.meta.url)
        )
    );

    let langCount = 0;
    for (const lang of languages) {
        await prisma.languages.upsert({
            where: { bcp_47: lang.bcp_47 },
            update: lang,
            create: lang,
        });
        langCount++;
    }

    const currentLangs = await prisma.languages.findMany();
    const bcp47List = languages.map((l: any) => l.bcp_47);
    let removedCount = 0;
    for (const cl of currentLangs) {
        if (!bcp47List.includes(cl.bcp_47)) {
            await prisma.languages.delete({ where: { id: cl.id } });
            removedCount++;
        }
    }

    if (removedCount > 0) {
        console.log(`✔ Removed ${removedCount} obsolete languages`);
    }
    console.log(`✔ Seeded ${langCount} languages`);
}

async function seedLlmModels(prisma: any) {
    const models = JSON.parse(
        await Deno.readTextFile(
            new URL("resources/llmmodels_master.json", import.meta.url)
        )
    );

    let count = 0;
    for (const m of models) {
        await prisma.llm_models.upsert({
            where: { name: m.name },
            update: {},
            create: { name: m.name },
        });
        count++;
    }
    
    const currentModels = await prisma.llm_models.findMany();
    const modelNames = models.map((m: any) => m.name);
    let removedCount = 0;
    for (const cm of currentModels) {
        if (!modelNames.includes(cm.name)) {
            await prisma.llm_models.delete({ where: { id: cm.id } });
            removedCount++;
        }
    }

    if (removedCount > 0) {
        console.log(`✔ Removed ${removedCount} obsolete LLM models`);
    }
    console.log(`✔ Seeded ${count} LLM models`);
}

async function seedMasterStrings(prisma: any) {
    const xml = await Deno.readTextFile(
        new URL("../app/src/main/res/values/strings.xml", import.meta.url)
    );

    let count = 0;
    let skipped = 0;
    const currentTexts: string[] = [];
    const stringRegex = /<string\s+name="([^"]*)"([^>]*)>([\s\S]*?)<\/string>/g;
    
    let match: RegExpExecArray | null;
    while ((match = stringRegex.exec(xml)) !== null) {
        const key = match[1];
        const attrs = match[2];
        const rawText = match[3].trim();
        
        if (!rawText) continue;
        if (/translatable\s*=\s*"false"/.test(attrs)) {
            skipped++;
            continue;
        }

        const text = rawText
            .replace(/&amp;/g, "&")
            .replace(/&lt;/g, "<")
            .replace(/&gt;/g, ">")
            .replace(/&quot;/g, "\"")
            .replace(/&apos;/g, "'");

        currentTexts.push(text);

        await prisma.master_strings.upsert({
            where: { text: text },
            update: {},
            create: { text: text }
        });
        count++;
    }

    const currentStrings = await prisma.master_strings.findMany();
    let removedCount = 0;
    for (const cs of currentStrings) {
        if (!currentTexts.includes(cs.text)) {
            await prisma.master_strings.delete({ where: { id: cs.id } });
            removedCount++;
        }
    }

    if (removedCount > 0) {
        console.log(`✔ Removed ${removedCount} obsolete master strings`);
    }
    console.log(`✔ Seeded ${count} master strings (skipped ${skipped} non-translatable)`);
}

async function main() {
    const prisma = await getPrisma();
    try {
        await seedLanguages(prisma);
        await seedLlmModels(prisma);
        await seedMasterStrings(prisma);
    } finally {
        await prisma.$disconnect();
    }
}

if (import.meta.main) {
    await main();
}
