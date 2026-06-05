-- CreateTable
CREATE TABLE "languages" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "posix_code" TEXT NOT NULL,
    "bcp_47" TEXT NOT NULL,
    "iso_639_3" TEXT NOT NULL,
    "whisper_response" TEXT,
    "directory" TEXT NOT NULL,
    "english_name" TEXT NOT NULL
);

-- CreateTable
CREATE TABLE "master_strings" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "text" TEXT NOT NULL
);

-- CreateTable
CREATE TABLE "llm_models" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "name" TEXT NOT NULL
);

-- CreateTable
CREATE TABLE "language_proficiencies" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "level" INTEGER NOT NULL,
    "modelId" INTEGER NOT NULL,
    "languageId" INTEGER NOT NULL,
    CONSTRAINT "language_proficiencies_modelId_fkey" FOREIGN KEY ("modelId") REFERENCES "llm_models" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "language_proficiencies_languageId_fkey" FOREIGN KEY ("languageId") REFERENCES "languages" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);

-- CreateTable
CREATE TABLE "votes" (
    "id" INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    "translation" TEXT NOT NULL,
    "created_at" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "languagesId" INTEGER NOT NULL,
    "llm_modelsId" INTEGER NOT NULL,
    "master_stringsId" INTEGER NOT NULL,
    CONSTRAINT "votes_languagesId_fkey" FOREIGN KEY ("languagesId") REFERENCES "languages" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "votes_llm_modelsId_fkey" FOREIGN KEY ("llm_modelsId") REFERENCES "llm_models" ("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "votes_master_stringsId_fkey" FOREIGN KEY ("master_stringsId") REFERENCES "master_strings" ("id") ON DELETE CASCADE ON UPDATE CASCADE
);

-- CreateIndex
CREATE UNIQUE INDEX "languages_posix_code_key" ON "languages"("posix_code");

-- CreateIndex
CREATE UNIQUE INDEX "languages_bcp_47_key" ON "languages"("bcp_47");

-- CreateIndex
CREATE UNIQUE INDEX "languages_directory_key" ON "languages"("directory");

-- CreateIndex
CREATE UNIQUE INDEX "master_strings_text_key" ON "master_strings"("text");

-- CreateIndex
CREATE UNIQUE INDEX "llm_models_name_key" ON "llm_models"("name");

-- CreateIndex
CREATE UNIQUE INDEX "language_proficiencies_modelId_languageId_key" ON "language_proficiencies"("modelId", "languageId");

-- CreateIndex
CREATE UNIQUE INDEX "votes_languagesId_llm_modelsId_master_stringsId_translation_key" ON "votes"("languagesId", "llm_modelsId", "master_stringsId", "translation");
