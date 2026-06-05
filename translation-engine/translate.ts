import {
    ModelRef,
    OpencodeClient,
    SendOptions,
} from "./lib/opencode_client.ts";
import {
    InputString,
    VerifyError,
    verifyProficiencyFile,
    verifyTranslationFile,
} from "./lib/verify.ts";
import { getPrisma } from "./lib/prisma.ts";
import {
    getAllLanguages,
    getAllMasterStrings,
    getAllModels,
    getExistingVotes,
    getModelByName,
    getNullExistingVotes,
    getOrCreateLanguage,
    getOrCreateModel,
    getProficiencyLevel,
    getSettledStrings,
    hasProficiency,
    upsertProficiency,
    upsertVote,
} from "./lib/db.ts";

// ── Model → Provider mapping (ordered by preference) ─────────────────────────
const MODEL_PROVIDERS_RAW: Record<string, string | string[]> = {
    "claude-opus-4.7": "openrouter",
    "deepseek-v4-pro": "opencode-go",
    "gemini-3.1-pro-preview": ["google", "github-copilot"],
    "gemini-3.1-pro": ["opencode"],
    "gemini-3.5-flash": ["opencode", "github-copilot", "google", "openrouter"],
    "glm-5.1": ["zai-coding-plan", "opencode-go"],
    "gpt-5.4": ["openrouter", "github-copilot"],
    "gpt-5.5": "opencode",
    "grok-4.3": "openrouter",
    "kimi-k2.6": "opencode-go",
    "minimax-m2.7": "opencode-go",
    "mistral-large": "opencode-go",
    "qwen3.6-plus": "opencode-go",
};

const MODEL_PROVIDERS: Map<string, string[]> = new Map(
    Object.entries(MODEL_PROVIDERS_RAW).map(([key, val]) => [
        normalizeModelName(key),
        typeof val === "string" ? [val] : val,
    ]),
);

function getModelRank(modelName: string, providerID: string): number {
    const providers = MODEL_PROVIDERS.get(normalizeModelName(modelName));
    if (!providers) return 99;
    const idx = providers.indexOf(providerID);
    return idx === -1 ? 99 : idx + 1;
}

function normalizeModelName(name: string): string {
    return name.replace(/[.-]/g, "").toLowerCase();
}

interface ModelEntry {
    providerID: string;
    modelID: string;
}

async function fetchAvailableModels(): Promise<Map<string, ModelEntry[]>> {
    const cmd = new Deno.Command("opencode", { args: ["models"] });
    const { stdout, stderr, success } = await cmd.output();
    if (!success) {
        throw new Error(
            `opencode models failed: ${new TextDecoder().decode(stderr)}`,
        );
    }

    const lines = new TextDecoder().decode(stdout).trim().split("\n");
    const rawMap = new Map<string, ModelEntry[]>();

    for (const line of lines) {
        const slug = line.trim();
        const firstSlash = slug.indexOf("/");
        if (firstSlash === -1) continue;

        const providerID = slug.slice(0, firstSlash);
        const modelPath = slug.slice(firstSlash + 1);
        const lastSlash = modelPath.lastIndexOf("/");
        const matchKey = lastSlash === -1
            ? modelPath
            : modelPath.slice(lastSlash + 1);
        const normalized = normalizeModelName(matchKey);

        if (!rawMap.has(normalized)) rawMap.set(normalized, []);
        rawMap.get(normalized)!.push({ providerID, modelID: modelPath });
    }

    return rawMap;
}

function buildFallbackChain(
    seedModelName: string,
    available: Map<string, ModelEntry[]>,
): ModelRef[] {
    const normalized = normalizeModelName(seedModelName);
    const providers = MODEL_PROVIDERS.get(normalized);
    if (!providers) return [];

    const availableEntries = available.get(normalized) || [];
    const chain: ModelRef[] = [];

    for (const pid of providers) {
        const match = availableEntries.find((e) => e.providerID === pid);
        if (match) {
            chain.push({
                providerID: match.providerID,
                modelID: match.modelID,
            });
        }
    }
    return chain;
}

// ── Logging ───────────────────────────────────────────────────────────────────
const LOG_DIR = "logs";
const LOG_FILE = "logs/orchestrator.log";

function ts(): string {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${
        String(d.getDate()).padStart(2, "0")
    }_${String(d.getHours()).padStart(2, "0")}-${
        String(d.getMinutes()).padStart(2, "0")
    }-${String(d.getSeconds()).padStart(2, "0")}`;
}

function ensureLogDir(): void {
    try {
        Deno.mkdirSync(LOG_DIR, { recursive: true });
    } catch {
        // ignore
    }
}

function writeLog(line: string): void {
    ensureLogDir();
    try {
        Deno.writeTextFileSync(LOG_FILE, line + "\n", {
            append: true,
            create: true,
        });
    } catch {
        // ignore write errors
    }
}

function log(msg: string): void {
    const line = `[${ts()}] ${msg}`;
    console.log(line);
    writeLog(line);
}

function logError(msg: string): void {
    const line = `[${ts()}] ERROR: ${msg}`;
    console.error(line);
    writeLog(line);
}

// ── Skill files ──────────────────────────────────────────────────────────────
const SKILL_TRANSLATE = await Deno.readTextFile(
    new URL("../.opencode/skills/translate/SKILL.md", import.meta.url),
);
const SKILL_PROFICIENCY = await Deno.readTextFile(
    new URL("../.opencode/skills/proficiency/SKILL.md", import.meta.url),
);

// ── Constants ────────────────────────────────────────────────────────────────
const PROJECT_DIR = Deno.cwd();
const INPUT_FILE = `${PROJECT_DIR}/translate-input.json`;
const OUTPUT_FILE = `${PROJECT_DIR}/translate-output.json`;
const PROFICIENCY_OUTPUT_FILE = `${PROJECT_DIR}/proficiency-output.json`;

const START_TIME = Date.now();
const MAX_RETRIES = 3;
const POLL_TIMEOUT_MS = 15 * 60 * 1000; // 15 minutes max per (model, locale) attempt
const DEFAULT_MIN_PROFICIENCY = 2;
const DEFAULT_MAX_DURATION_MIN = 210;

function isTimeUp(maxDurationMs: number): boolean {
    return Date.now() - START_TIME >= maxDurationMs;
}

function sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function isQuotaError(
    s: { message?: string; action?: { reason?: string } },
): boolean {
    if (s.action?.reason === "free_tier_limit") return true;
    const msg = (s.message || "").toLowerCase();
    return msg.includes("quota") || msg.includes("rate limit") ||
        msg.includes("429") || msg.includes("exceeded");
}

type PollResult =
    | { type: "done" }
    | { type: "quota"; message: string }
    | { type: "timeout" };

async function pollForCompletion(
    opencode: OpencodeClient,
    sessionId: string,
    outputFile: string,
    timeoutMs: number,
): Promise<PollResult> {
    const start = Date.now();
    let lastMtime = 0;
    try {
        const stat = await Deno.stat(outputFile);
        lastMtime = stat.mtime?.getTime() || 0;
    } catch {
        // file doesn't exist yet
    }

    while (Date.now() - start < timeoutMs) {
        await sleep(10000);

        // Session-Status für Quota-Detection (fast-fail)
        try {
            const statusMap = await opencode.getSessionStatus();
            const s = statusMap[sessionId] as
                | {
                    type: string;
                    message?: string;
                    action?: { reason?: string };
                }
                | undefined;
            if (s?.type === "retry" && isQuotaError(s)) {
                return { type: "quota", message: s.message || "unknown" };
            }
        } catch {
            // ignore poll errors
        }

        // Output-File als primäres Completion-Signal
        try {
            const stat = await Deno.stat(outputFile);
            const mtime = stat.mtime?.getTime() || 0;
            if (mtime > lastMtime) {
                const content = await Deno.readTextFile(outputFile);
                if (content.trim().startsWith("{")) {
                    return { type: "done" };
                }
            }
        } catch {
            // file not yet written
        }
    }
    return { type: "timeout" };
}

// ── CLI args ─────────────────────────────────────────────────────────────────
interface CliArgs {
    all: boolean;
    minProficiency: number;
    maxDuration: number;
    model?: string;
    locale?: string;
}

function parseArgs(): CliArgs {
    const args = Deno.args
        .flatMap((a) =>
            a.startsWith("--") && a.includes("=") ? a.split("=", 2) : [a]
        );
    let all = false;
    let minProficiency = DEFAULT_MIN_PROFICIENCY;
    let maxDuration = DEFAULT_MAX_DURATION_MIN;
    let model: string | undefined;
    let locale: string | undefined;

    for (let i = 0; i < args.length; i++) {
        switch (args[i]) {
            case "--model":
                model = args[++i];
                break;
            case "--locale":
                locale = args[++i];
                break;
            case "--all":
                all = true;
                break;
            case "--min-proficiency":
                minProficiency = parseInt(args[++i], 10);
                break;
            case "--max-duration":
                maxDuration = parseInt(args[++i], 10);
                break;
            case "--help":
                log(
                    `Usage: deno task translate -- [OPTIONS]

Options:
  --model <name> --locale <bcp47>    Single (model, locale) pair
  --model <name> --all                Single model over all locales
  --all                               Full matrix over all models x locales
  --min-proficiency <N>               Skip locales below this proficiency (default: ${DEFAULT_MIN_PROFICIENCY})
  --max-duration <minutes>            Max runtime before graceful stop (default: ${DEFAULT_MAX_DURATION_MIN})
  --help                              Show this help`,
                );
                Deno.exit(0);
        }
    }

    if (all && !model) {
        return {
            all: true,
            model: undefined,
            locale: undefined,
            minProficiency,
            maxDuration,
        };
    }
    if (model && all) {
        return {
            all: true,
            model,
            locale: undefined,
            minProficiency,
            maxDuration,
        };
    }
    if (model && locale) {
        return { all: false, model, locale, minProficiency, maxDuration };
    }
    logError(
        "Specify --model <name> --locale <bcp47>, --model <name> --all, or --all",
    );
    Deno.exit(1);
}

// ── File helpers ─────────────────────────────────────────────────────────────
async function writeInput(data: unknown): Promise<void> {
    await Deno.writeTextFile(INPUT_FILE, JSON.stringify(data, null, 2));
}

// ── Dispatch functions ───────────────────────────────────────────────────────
async function dispatchProficiency(
    opencode: OpencodeClient,
    modelName: string,
    langBcp47: string,
    langEnglishName: string,
    availableModels: Map<string, ModelEntry[]>,
): Promise<number> {
    const input = {
        locale: { bcp_47: langBcp47, english_name: langEnglishName },
        app_name: "ZazenTimer",
    };
    await writeInput(input);

    const chain = buildFallbackChain(modelName, availableModels);
    if (chain.length === 0) {
        throw new Error(`No available provider for model '${modelName}'`);
    }

    for (const modelRef of chain) {
        let lastError: string | undefined;
        let sessionId = await opencode.createSession(PROJECT_DIR);

        for (let attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                const opts: SendOptions = {
                    system: SKILL_PROFICIENCY,
                    model: attempt === 0 ? modelRef : undefined,
                };

                const text = attempt === 0
                    ? `Write the proficiency assessment to ${PROFICIENCY_OUTPUT_FILE}. Read the input data from ${INPUT_FILE}.`
                    : `Verification failed: ${lastError}. Please fix ${PROFICIENCY_OUTPUT_FILE}. You may read '${PROFICIENCY_OUTPUT_FILE}' and '${INPUT_FILE}' to inspect.`;

                const context =
                    `${langEnglishName} to ${modelRef.providerID}(rank ${
                        getModelRank(modelName, modelRef.providerID)
                    })/${modelRef.modelID}`;

                log(`submitting proficiency request for ${context}`);

                await opencode.sendMessageAsync(sessionId, text, opts);

                const pollResult = await pollForCompletion(
                    opencode,
                    sessionId,
                    PROFICIENCY_OUTPUT_FILE,
                    POLL_TIMEOUT_MS,
                );

                if (pollResult.type === "quota") {
                    lastError = `Quota exceeded: ${pollResult.message}`;
                    logError(`${context}: ${lastError}, falling back`);
                    await opencode.closeSession(sessionId);
                    break;
                }

                if (pollResult.type === "timeout") {
                    logError(
                        `proficiency session timeout after ${
                            POLL_TIMEOUT_MS / 60000
                        } min for ${context}, creating new session`,
                    );
                    await opencode.closeSession(sessionId);
                    sessionId = await opencode.createSession(PROJECT_DIR);
                    continue;
                }

                const result = await verifyProficiencyFile(
                    PROFICIENCY_OUTPUT_FILE,
                    langBcp47,
                );
                const language = await getOrCreateLanguage(langBcp47);
                const modelDb = await getOrCreateModel(modelName);
                await upsertProficiency(
                    language.id,
                    modelDb.id,
                    result.proficiency,
                );

                const rank = getModelRank(modelName, modelRef.providerID);
                log(
                    `proficiency ${modelName} ${langBcp47} ${modelRef.providerID} rank=${rank} → ${result.proficiency}`,
                );
                return result.proficiency;
            } catch (e) {
                lastError = e instanceof VerifyError ? e.message : String(e);
                logError(
                    `${modelRef.providerID}/${modelRef.modelID} attempt ${
                        attempt + 1
                    }/${MAX_RETRIES}: ${lastError}`,
                );
            }
        }
    }

    throw new VerifyError(
        `Proficiency assessment for '${modelName}' in '${langBcp47}' failed across all ${chain.length} provider(s)`,
    );
}

async function dispatchTranslate(
    opencode: OpencodeClient,
    modelName: string,
    langBcp47: string,
    langEnglishName: string,
    strings: InputString[],
    availableModels: Map<string, ModelEntry[]>,
    proficiency: number,
    unsettledCount: number,
): Promise<void> {
    const input = {
        locale: { bcp_47: langBcp47, english_name: langEnglishName },
        app_name: "ZazenTimer",
        strings,
    };
    await writeInput(input);

    const chain = buildFallbackChain(modelName, availableModels);
    if (chain.length === 0) {
        throw new Error(`No available provider for model '${modelName}'`);
    }

    for (const modelRef of chain) {
        let lastError: string | undefined;
        let sessionId = await opencode.createSession(PROJECT_DIR);

        for (let attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                const opts: SendOptions = {
                    system: SKILL_TRANSLATE,
                    model: attempt === 0 ? modelRef : undefined,
                };

                const text = attempt === 0
                    ? `Write the translations to ${OUTPUT_FILE}. Read the input data from ${INPUT_FILE}.`
                    : `Verification failed: ${lastError}. Please fix ${OUTPUT_FILE}. You may read '${INPUT_FILE}' and '${OUTPUT_FILE}' to inspect.`;

                const rank = getModelRank(modelName, modelRef.providerID);
                log(
                    `submitting translation request for ${langEnglishName} to ${modelRef.providerID}(rank ${rank})/${modelRef.modelID}, proficiency: ${proficiency}. searching for ${unsettledCount} unsettled strings`,
                );

                const context =
                    `${langEnglishName} to ${modelRef.providerID}(rank ${rank})/${modelRef.modelID}`;

                await opencode.sendMessageAsync(sessionId, text, opts);

                const pollResult = await pollForCompletion(
                    opencode,
                    sessionId,
                    OUTPUT_FILE,
                    POLL_TIMEOUT_MS,
                );

                if (pollResult.type === "quota") {
                    lastError = `Quota exceeded: ${pollResult.message}`;
                    logError(`${context}: ${lastError}, falling back`);
                    await opencode.closeSession(sessionId);
                    break;
                }

                if (pollResult.type === "timeout") {
                    logError(
                        `translation session timeout after ${
                            POLL_TIMEOUT_MS / 60000
                        } min for ${context}, creating new session`,
                    );
                    await opencode.closeSession(sessionId);
                    sessionId = await opencode.createSession(PROJECT_DIR);
                    continue;
                }

                const result = await verifyTranslationFile(
                    OUTPUT_FILE,
                    langBcp47,
                    strings,
                );
                const language = await getOrCreateLanguage(langBcp47);
                const modelDb = await getOrCreateModel(modelName);

                const allMs = await getAllMasterStrings();
                let stringCount = 0;
                let emptyCount = 0;
                let skippedMasterString = 0;
                for (const t of result.translations) {
                    const ms = allMs.find((s) => s.text === t.key);
                    if (!ms) {
                        skippedMasterString++;
                        continue;
                    }
                    if (t.translation === null) {
                        await upsertVote(language.id, modelDb.id, ms.id, "");
                        emptyCount++;
                        continue;
                    }
                    const items = Array.isArray(t.translation)
                        ? t.translation
                        : [t.translation];
                    for (const item of items) {
                        await upsertVote(
                            language.id,
                            modelDb.id,
                            ms.id,
                            item,
                        );
                        if (item === "") {
                            emptyCount++;
                        } else {
                            stringCount++;
                        }
                    }
                }

                const total = stringCount + emptyCount;
                const suffix = skippedMasterString > 0
                    ? `, ${skippedMasterString} skipped (no master_string match)`
                    : "";
                log(
                    `got translation result for ${langEnglishName} to ${modelRef.providerID}(rank ${rank})/${modelRef.modelID}: ${total} votes stored (${stringCount} strings, ${emptyCount} empty)${suffix}`,
                );
                await (await getPrisma()).$queryRawUnsafe(
                    "PRAGMA wal_checkpoint(TRUNCATE)",
                );
                return;
            } catch (e) {
                lastError = e instanceof VerifyError ? e.message : String(e);
                logError(
                    `${modelRef.providerID}/${modelRef.modelID} attempt ${
                        attempt + 1
                    }/${MAX_RETRIES}: ${lastError}`,
                );
            }
        }
    }

    logError(
        `${modelName} ${langBcp47}: failed after ${MAX_RETRIES} retries across ${chain.length} provider(s)`,
    );
}

// ── Single (model, locale) run ──────────────────────────────────────────────
async function runOne(
    opencode: OpencodeClient,
    modelName: string,
    langBcp47: string,
    langEnglishName: string,
    availableModels: Map<string, ModelEntry[]>,
    minProficiency: number,
): Promise<void> {
    const modelDb = await getOrCreateModel(modelName);
    const language = await getOrCreateLanguage(langBcp47);

    // Step 1: Proficiency (on-demand if not in DB)
    if (!(await hasProficiency(modelDb.id, language.id))) {
        try {
            log(`requesting proficiency for ${modelName} ${langEnglishName} (${langBcp47})`);
            await dispatchProficiency(
                opencode,
                modelName,
                langBcp47,
                langEnglishName,
                availableModels,
            );
        } catch (e) {
            logError(
                `${modelName} ${langBcp47}: proficiency failed, skipping translate: ${
                    e instanceof Error ? e.message : String(e)
                }`,
            );
            return;
        }
    }

    const proficiency = await getProficiencyLevel(modelDb.id, language.id) ?? 0;

    if (proficiency < minProficiency) {
        log(`${modelName} ${langBcp47}: proficiency ${proficiency} below threshold ${minProficiency}, skipping`);
        return;
    }

    // Step 2: Translate (only missing strings)
    const allMs = await getAllMasterStrings();
    const existing = await getExistingVotes(modelDb.id, language.id);
    const nullVotes = await getNullExistingVotes(modelDb.id, language.id);
    const settled = await getSettledStrings(language.id);
    const skip = new Set([...existing, ...nullVotes, ...settled]);
    const missing = allMs.filter((s) => !skip.has(s.id));

    if (missing.length === 0) {
        log(`${modelName} ${langBcp47}: all ${allMs.length} strings settled or existing, skipping`);
        return;
    }

    const chain = buildFallbackChain(modelName, availableModels);
    const modelProviders = MODEL_PROVIDERS.get(normalizeModelName(modelName));
    const isOnlyProvider = modelProviders && modelProviders.length === 1;
    const providerLabel = chain.length > 0
        ? isOnlyProvider
            ? `only provider (${chain[0].providerID})`
            : `${chain[0].providerID} (rank ${
                getModelRank(modelName, chain[0].providerID)
            }/${modelProviders!.length})/${chain[0].modelID}`
        : "no provider";
    const nonSettled = allMs.length - settled.size;
    const votesOnNonSettled = [...existing].filter((id) => !settled.has(id)).length;
    const nullsOnNonSettled = [...nullVotes].filter((id) => !settled.has(id)).length;
    const totalOnNonSettled = votesOnNonSettled + nullsOnNonSettled;
    log(
        `In ${langEnglishName} (${langBcp47}) to ${providerLabel}: ${settled.size} of ${allMs.length} strings are settled. ` +
            `Of the remaining ${nonSettled} unsettled, this model has already voted on ${totalOnNonSettled} (including ${nullsOnNonSettled} empty votes). ` +
            `Asking for ${missing.length} new string(s).`,
    );

    const strings = missing.map((s) => ({ key: s.text, text: s.text }));
    try {
        await dispatchTranslate(
            opencode,
            modelName,
            langBcp47,
            langEnglishName,
            strings,
            availableModels,
            proficiency,
            missing.length,
        );
    } catch (e) {
        logError(
            `${modelName} ${langBcp47}: ${
                e instanceof Error ? e.message : String(e)
            }`,
        );
    }
}

// ── Full matrix run ──────────────────────────────────────────────────────────
async function runAll(
    opencode: OpencodeClient,
    models: { id: number; name: string }[],
    languages: { id: number; bcp_47: string; english_name: string }[],
    availableModels: Map<string, ModelEntry[]>,
    minProficiency: number,
    maxDurationMs: number,
): Promise<void> {
    for (const m of models) {
        for (const lang of languages) {
            if (isTimeUp(maxDurationMs)) {
                log(`Stopping: configured runtime reached at model=${m.name}, locale=${lang.bcp_47}`);
                Deno.exit(0);
            }
            await runOne(
                opencode,
                m.name,
                lang.bcp_47,
                lang.english_name,
                availableModels,
                minProficiency,
            );
        }
    }
}

// ── Single model over all locales ────────────────────────────────────────────
async function runOneModelAllLocales(
    opencode: OpencodeClient,
    modelName: string,
    availableModels: Map<string, ModelEntry[]>,
    minProficiency: number,
    maxDurationMs: number,
): Promise<void> {
    const languages = await getAllLanguages();
    log(`Model '${modelName}' over ${languages.length} locales`);

    for (const lang of languages) {
        if (isTimeUp(maxDurationMs)) {
            log(`Stopping: configured runtime reached at model=${modelName}, locale=${lang.bcp_47}`);
            Deno.exit(0);
        }
        await runOne(
            opencode,
            modelName,
            lang.bcp_47,
            lang.english_name,
            availableModels,
            minProficiency,
        );
    }
}

// ── Main ─────────────────────────────────────────────────────────────────────
async function main() {
    const args = parseArgs();
    const opencode = new OpencodeClient();

    log("Fetching available models from opencode...");
    const availableModels = await fetchAvailableModels();
    log(`Found ${availableModels.size} unique models across providers`);

    if (args.all && !args.model) {
        const allModels = await getAllModels();
        const languages = await getAllLanguages();

        // Error: MODEL_PROVIDERS-Model nicht in DB
        for (const [norm] of MODEL_PROVIDERS) {
            if (!allModels.some((m) => normalizeModelName(m.name) === norm)) {
                logError(
                    `Model '${norm}' is defined in MODEL_PROVIDERS (prisma/translate.ts) ` +
                        `but not in DB (llm_models). ` +
                        `Ensure it's in 'prisma/resources/llmmodels_master.json' ` +
                        `and run 'deno task prismatranslationsseed'.`,
                );
                Deno.exit(1);
            }
        }

        // Warning: DB-Model nicht in MODEL_PROVIDERS
        for (const m of allModels) {
            if (!MODEL_PROVIDERS.has(normalizeModelName(m.name))) {
                log(
                    `WARNING: model '${m.name}' exists in DB ` +
                        `but is not defined in MODEL_PROVIDERS (prisma/translate.ts)`,
                );
            }
        }

        const activeModels = allModels.filter((m) =>
            MODEL_PROVIDERS.has(normalizeModelName(m.name))
        );
        log(`Full run: ${activeModels.length} models x ${languages.length} locales`);

        await runAll(
            opencode,
            activeModels,
            languages,
            availableModels,
            args.minProficiency,
            args.maxDuration * 60_000,
        );

        log("Full run complete");
    } else if (args.model && args.all) {
        const normalized = normalizeModelName(args.model);
        if (!MODEL_PROVIDERS.has(normalized)) {
            logError(
                `Model '${args.model}' is not defined in MODEL_PROVIDERS (prisma/translate.ts)`,
            );
            Deno.exit(1);
        }
        const dbModel = await getModelByName(args.model);
        if (!dbModel) {
            logError(
                `Model '${args.model}' is defined in MODEL_PROVIDERS ` +
                    `but not in DB (llm_models). ` +
                    `Ensure it's in 'prisma/resources/llmmodels_master.json' ` +
                    `and run 'deno task prismatranslationsseed'.`,
            );
            Deno.exit(1);
        }

        await runOneModelAllLocales(
            opencode,
            args.model,
            availableModels,
            args.minProficiency,
            args.maxDuration * 60_000,
        );

        log(`Model '${args.model}' complete`);
    } else if (args.model && args.locale) {
        const language = await getOrCreateLanguage(args.locale);
        await runOne(
            opencode,
            args.model,
            args.locale,
            language.english_name,
            availableModels,
            args.minProficiency,
        );
    }
}

if (import.meta.main) {
    await main();
}
