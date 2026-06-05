import type { languages, llm_models, master_strings } from "prismaclient";
import { getPrisma } from "./prisma.ts";
let prisma = await getPrisma();

const NOT_EMPTY = { translation: { not: "" as const } };

// ── Models ──────────────────────────────────────────────────────────────────

export async function getAllModels(): Promise<llm_models[]> {
    return await prisma.llm_models.findMany({ orderBy: { name: "asc" } });
}

export async function getModels() {
    return await getAllModels();
}

export async function getOrCreateModel(name: string): Promise<llm_models> {
    let model = await prisma.llm_models.findUnique({ where: { name } });
    if (!model) {
        model = await prisma.llm_models.create({ data: { name } });
    }
    return model;
}

export async function getModelById(id: number): Promise<llm_models | null> {
    return await prisma.llm_models.findUnique({ where: { id } });
}

export async function getModelByName(name: string): Promise<llm_models | null> {
    return await prisma.llm_models.findUnique({ where: { name } });
}

export async function getModelsWithStats() {
    const models = await prisma.llm_models.findMany({
        orderBy: { name: "asc" },
        include: { language_proficiencies: true },
    });
    const voteCounts = await prisma.votes.groupBy({
        by: ["llm_modelsId"],
        where: { translation: { not: "" } },
        _count: { id: true },
    });
    const voteMap = new Map(
        voteCounts.map((v) => [v.llm_modelsId, v._count.id]),
    );

    return models.map((m) => ({
        id: m.id,
        name: m.name,
        avgProficiency: m.language_proficiencies.length > 0
            ? Math.round(
                m.language_proficiencies.reduce((s, p) => s + p.level, 0) /
                    m.language_proficiencies.length * 10,
            ) / 10
            : null,
        languageCount: m.language_proficiencies.length,
        voteCount: voteMap.get(m.id) ?? 0,
    }));
}

// ── Proficiencies ───────────────────────────────────────────────────────────

export async function getProficiencies(modelId: number) {
    return await prisma.language_proficiencies.findMany({
        where: { modelId },
        include: { language: true },
        orderBy: { level: "desc" },
    });
}

export async function upsertProficiency(
    languageId: number,
    modelId: number,
    level: number,
) {
    return await prisma.language_proficiencies.create({
        data: { level, modelId, languageId },
    });
}

export async function hasProficiency(
    modelId: number,
    languageId: number,
): Promise<boolean> {
    const rows = await prisma.language_proficiencies.findMany({
        where: { modelId, languageId },
        take: 1,
        select: { id: true },
    });
    return rows.length > 0;
}

export async function getProficiencyLevel(
    modelId: number,
    languageId: number,
): Promise<number | null> {
    const row = await prisma.language_proficiencies.findFirst({
        where: { modelId, languageId },
        select: { level: true },
    });
    return row?.level ?? null;
}

// ── Languages ───────────────────────────────────────────────────────────────

export async function getAllLanguages(): Promise<languages[]> {
    return await prisma.languages.findMany({ orderBy: { bcp_47: "asc" } });
}

export async function getLanguages() {
    return await getAllLanguages();
}

export async function getOrCreateLanguage(bcp47: string): Promise<languages> {
    const lang = await prisma.languages.findUnique({
        where: { bcp_47: bcp47 },
    });
    if (!lang) {
        throw new Error(`Language '${bcp47}' not found in DB. Run seed first.`);
    }
    return lang;
}

export async function getLanguageById(id: number): Promise<languages | null> {
    prisma = await getPrisma();
    return await prisma.languages.findUnique({ where: { id } });
}

export async function getLanguageByBcp47(
    bcp47: string,
): Promise<languages | null> {
    return await prisma.languages.findUnique({ where: { bcp_47: bcp47 } });
}

export async function getLanguagesWithVotes(): Promise<languages[]> {
    const langIds = await prisma.votes.findMany({
        select: { languagesId: true },
        distinct: ["languagesId"],
    });
    return await prisma.languages.findMany({
        where: { id: { in: langIds.map((l) => l.languagesId) } },
        orderBy: { bcp_47: "asc" },
    });
}

export async function getLanguagesWithVotesForString(
    stringId: number,
): Promise<languages[]> {
    const langIds = await prisma.votes.findMany({
        where: { master_stringsId: stringId },
        select: { languagesId: true },
        distinct: ["languagesId"],
    });
    return await prisma.languages.findMany({
        where: { id: { in: langIds.map((l) => l.languagesId) } },
        orderBy: { bcp_47: "asc" },
    });
}

export async function getLanguagesWithStats(search: string) {
    const where = search
        ? {
            OR: [
                { english_name: { contains: search } },
                { bcp_47: { contains: search } },
            ],
        }
        : {};
    const languages = await prisma.languages.findMany({
        where,
        orderBy: { bcp_47: "asc" },
    });

    return await Promise.all(languages.map(async (lang) => {
        const [modelCount, voteCount, translatedCount, settledCount] = await Promise.all([
            prisma.language_proficiencies.count({
                where: { languageId: lang.id },
            }),
            prisma.votes.count({
                where: { languagesId: lang.id, ...NOT_EMPTY },
            }),
            prisma.votes.groupBy({
                by: ["master_stringsId"],
                where: { languagesId: lang.id, ...NOT_EMPTY },
            }).then((r) => r.length),
            getSettledStrings(lang.id).then((s) => s.size),
        ]);
        return { ...lang, modelCount, voteCount, translatedCount, settledCount };
    }));
}

// ── Master Strings ──────────────────────────────────────────────────────────

export async function getMasterStringCount(): Promise<number> {
    return await prisma.master_strings.count();
}

export async function getAllMasterStrings(): Promise<master_strings[]> {
    return await prisma.master_strings.findMany({ orderBy: { id: "asc" } });
}

export async function getOrCreateMasterString(
    text: string,
): Promise<master_strings> {
    let ms = await prisma.master_strings.findUnique({ where: { text } });
    if (!ms) {
        ms = await prisma.master_strings.create({ data: { text } });
    }
    return ms;
}

export async function getMasterStringById(
    id: number,
): Promise<master_strings | null> {
    prisma = await getPrisma();
    return await prisma.master_strings.findUnique({ where: { id } });
}

export async function getMasterStringByText(
    text: string,
): Promise<master_strings | null> {
    return await prisma.master_strings.findUnique({ where: { text } });
}

export async function getStrings(search: string) {
    const where = search ? { text: { contains: search } } : {};
    const strings = await prisma.master_strings.findMany({
        where,
        orderBy: { text: "asc" },
    });

    const counts = await prisma.votes.groupBy({
        by: ["master_stringsId"],
        where: NOT_EMPTY,
        _count: { id: true },
    });
    const countMap = new Map(
        counts.map((c) => [c.master_stringsId, c._count.id]),
    );

    return strings.map((s) => ({ ...s, voteCount: countMap.get(s.id) || 0 }));
}

// ── Votes / Translations ────────────────────────────────────────────────────

export async function getExistingVotes(
    modelId: number,
    languageId: number,
): Promise<Set<number>> {
    const rows = await prisma.votes.findMany({
        where: { llm_modelsId: modelId, languagesId: languageId, ...NOT_EMPTY },
        select: { master_stringsId: true },
        distinct: ["master_stringsId"],
    });
    return new Set(rows.map((r) => r.master_stringsId));
}

export async function getNullExistingVotes(
    modelId: number,
    languageId: number,
): Promise<Set<number>> {
    const rows = await prisma.votes.findMany({
        where: {
            llm_modelsId: modelId,
            languagesId: languageId,
            translation: "",
        },
        select: { master_stringsId: true },
        distinct: ["master_stringsId"],
    });
    return new Set(rows.map((r) => r.master_stringsId));
}

export async function upsertVote(
    languageId: number,
    modelId: number,
    masterStringId: number,
    translation: string,
) {
    return await prisma.votes.upsert({
        where: {
            languagesId_llm_modelsId_master_stringsId_translation: {
                languagesId: languageId,
                llm_modelsId: modelId,
                master_stringsId: masterStringId,
                translation,
            },
        },
        update: {},
        create: {
            languagesId: languageId,
            llm_modelsId: modelId,
            master_stringsId: masterStringId,
            translation,
        },
    });
}

export async function getVotesGrouped(modelId: number, langId: number) {
    const votes = await prisma.votes.findMany({
        where: { languagesId: langId, llm_modelsId: modelId, ...NOT_EMPTY },
        include: { master_string: true },
        orderBy: [{ master_stringsId: "asc" }, { created_at: "desc" }],
    });

    const grouped: Record<
        number,
        { master_stringsId: number; master_string: string; translations: string[] }
    > = {};
    for (const v of votes) {
        if (!grouped[v.master_stringsId]) {
            grouped[v.master_stringsId] = {
                master_stringsId: v.master_stringsId,
                master_string: v.master_string.text,
                translations: [],
            };
        }
        if (!grouped[v.master_stringsId].translations.includes(v.translation)) {
            grouped[v.master_stringsId].translations.push(v.translation);
        }
    }
    return Object.values(grouped);
}

export async function getCoverage(modelId: number, langId: number) {
    const [translated, total] = await Promise.all([
        prisma.votes.findMany({
            where: { languagesId: langId, llm_modelsId: modelId, ...NOT_EMPTY },
            select: { master_stringsId: true },
            distinct: ["master_stringsId"],
        }),
        prisma.master_strings.count(),
    ]);
    return {
        translated: translated.length,
        total,
        coverage_pct: total > 0
            ? Math.round((translated.length / total) * 100 * 10) / 10
            : 0,
    };
}

export async function getComparison(
    stringId: number,
    langId: number,
    masterStringText?: string,
) {
    if (!masterStringText) {
        const masterString = await prisma.master_strings.findUnique({
            where: { id: stringId },
        });
        if (!masterString) throw new Error("Master string not found");
        masterStringText = masterString.text;
    }

    const where: Record<string, unknown> = {
        master_stringsId: stringId,
        ...NOT_EMPTY,
    };
    if (langId) where.languagesId = langId;

    const votes = await prisma.votes.findMany({
        where,
        include: { llm_model: true, language: true },
        orderBy: [{ llm_modelsId: "asc" }, { created_at: "desc" }],
    });

    const byModel: Record<
        number,
        { model: string; translations: string[]; modelId: number }
    > = {};
    for (const v of votes) {
        if (!byModel[v.llm_modelsId]) {
            byModel[v.llm_modelsId] = {
                model: v.llm_model.name,
                modelId: v.llm_modelsId,
                translations: [],
            };
        }
        if (!byModel[v.llm_modelsId].translations.includes(v.translation)) {
            byModel[v.llm_modelsId].translations.push(v.translation);
        }
    }
    return {
        master_string: masterStringText,
        comparisons: Object.values(byModel),
    };
}

// ── Evaluation ───────────────────────────────────────────────────────────────

export async function getEvaluation(langId: number) {
    const votes = await prisma.votes.findMany({
        where: { languagesId: langId, ...NOT_EMPTY },
        include: { master_string: true, llm_model: true },
    });

    const profs = await prisma.language_proficiencies.findMany({
        where: { languageId: langId, level: { gte: 2 } },
        include: { llm_model: true },
    });

    const modelLevels = new Map<number, number>();
    for (const p of profs) {
        modelLevels.set(p.llm_model.id, p.level);
    }

    const groups = new Map<
        number,
        Map<string, {
            master_stringsId: number;
            master_string: string;
            translation: string;
            score: number;
            modelCount: number;
            modelNames: string[];
            modelDetails: { name: string; level: number }[];
        }>
    >();

    for (const v of votes) {
        const level = modelLevels.get(v.llm_modelsId);
        if (!level) continue;
        const msId = v.master_stringsId;
        if (!groups.has(msId)) groups.set(msId, new Map());
        const tmap = groups.get(msId)!;
        if (!tmap.has(v.translation)) {
            tmap.set(v.translation, {
                master_stringsId: msId,
                master_string: v.master_string.text,
                translation: v.translation,
                score: 0,
                modelCount: 0,
                modelNames: [],
                modelDetails: [],
            });
        }
        const entry = tmap.get(v.translation)!;
        entry.score += level;
        entry.modelCount++;
        entry.modelNames.push(v.llm_model.name);
        entry.modelDetails.push({ name: v.llm_model.name, level });
    }

    const result = [];
    for (const [, tmap] of groups) {
        const values = [...tmap.values()];
        // Fisher-Yates shuffle as random tiebreak (order preserved on match)
        for (let i = values.length - 1; i > 0; i--) {
            const j = Math.floor(Math.random() * (i + 1));
            [values[i], values[j]] = [values[j], values[i]];
        }
        const ranked = values.sort((a, b) => {
            const dc = b.modelCount - a.modelCount;
            if (dc !== 0) return dc;
            return b.score - a.score;
        });
        result.push(...ranked);
    }
    return result.map((r) => ({
        ...r,
        modelNames: r.modelNames.join(", "),
    }));
}

export async function getBestTranslation(
    text: string,
    bcp47: string,
): Promise<{ translation: string; modelCount: number; score: number } | null> {
    const ms = await prisma.master_strings.findUnique({ where: { text } });
    if (!ms) return null;
    const lang = await prisma.languages.findUnique({
        where: { bcp_47: bcp47 },
    });
    if (!lang) return null;

    const votes = await prisma.votes.findMany({
        where: {
            master_stringsId: ms.id,
            languagesId: lang.id,
            ...NOT_EMPTY,
        },
        include: { llm_model: true },
    });

    if (votes.length === 0) return null;

    const modelIds = [...new Set(votes.map((v) => v.llm_modelsId))];
    const profs = await prisma.language_proficiencies.findMany({
        where: {
            languageId: lang.id,
            llm_modelsId: { in: modelIds },
            level: { gte: 2 },
        },
        include: { llm_model: true },
    });
    const modelLevels = new Map(profs.map((p) => [p.llm_model.id, p.level]));

    const groups = new Map<string, { modelCount: number; score: number }>();
    for (const v of votes) {
        const level = modelLevels.get(v.llm_modelsId);
        if (!level) continue;
        const entry = groups.get(v.translation) || {
            modelCount: 0,
            score: 0,
        };
        entry.modelCount++;
        entry.score += level;
        groups.set(v.translation, entry);
    }

    const entries = [...groups.entries()].map(([t, g]) => ({
        translation: t,
        modelCount: g.modelCount,
        score: g.score,
    }));

    // Fisher-Yates shuffle + sort
    for (let i = entries.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [entries[i], entries[j]] = [entries[j], entries[i]];
    }
    entries.sort((a, b) => {
        const dc = b.modelCount - a.modelCount;
        if (dc !== 0) return dc;
        return b.score - a.score;
    });

    return entries[0];
}

export async function getSettledStrings(langId: number): Promise<Set<number>> {
    const evaluation = await getEvaluation(langId);
    const best = new Map<number, number>();
    for (const e of evaluation) {
        const current = best.get(e.master_stringsId) || 0;
        if (e.modelCount > current) best.set(e.master_stringsId, e.modelCount);
    }
    return new Set(
        [...best.entries()]
            .filter(([_, count]) => count >= 3)
            .map(([id]) => id),
    );
}

export async function getStringSettlement(stringId: number) {
    const votes = await prisma.votes.findMany({
        where: { master_stringsId: stringId, ...NOT_EMPTY },
        include: { language: true, llm_model: true },
    });

    const modelIds = [...new Set(votes.map((v) => v.llm_modelsId))];
    const langIds = [...new Set(votes.map((v) => v.languagesId))];
    const profs = await prisma.language_proficiencies.findMany({
        where: {
            level: { gte: 2 },
            modelId: { in: modelIds },
            languageId: { in: langIds },
        },
    });

    const levelMap = new Map<string, number>();
    for (const p of profs) {
        levelMap.set(`${p.modelId}:${p.languageId}`, p.level);
    }

    const langData = new Map<number, {
        bcp47: string;
        englishName: string;
        translations: Map<string, { count: number; score: number }>;
    }>();

    for (const v of votes) {
        const level = levelMap.get(`${v.llm_modelsId}:${v.languagesId}`);
        if (!level) continue;

        if (!langData.has(v.languagesId)) {
            langData.set(v.languagesId, {
                bcp47: v.language.bcp_47,
                englishName: v.language.english_name,
                translations: new Map(),
            });
        }
        const lang = langData.get(v.languagesId)!;
        if (!lang.translations.has(v.translation)) {
            lang.translations.set(v.translation, { count: 0, score: 0 });
        }
        const t = lang.translations.get(v.translation)!;
        t.count++;
        t.score += level;
    }

    const languages = [...langData.entries()].map(([langId, data]) => {
        const best = [...data.translations.entries()]
            .sort((a, b) => {
                const dc = b[1].count - a[1].count;
                if (dc !== 0) return dc;
                return b[1].score - a[1].score;
            })[0];

        return {
            languageId: langId,
            bcp47: data.bcp47,
            englishName: data.englishName,
            translation: best[0],
            voteCount: best[1].count,
            score: best[1].score,
            settled: best[1].count >= 3,
        };
    });

    return {
        totalLanguages: languages.length,
        settledLanguages: languages.filter((r) => r.settled).length,
        languages,
    };
}

// ── Stats ───────────────────────────────────────────────────────────────────

export async function getStats() {
    const [models, languages, votes, strings] = await Promise.all([
        prisma.llm_models.count(),
        prisma.languages.count(),
        prisma.votes.count({ where: NOT_EMPTY }),
        prisma.master_strings.count(),
    ]);
    return { models, languages, votes, strings };
}
