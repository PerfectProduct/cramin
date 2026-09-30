# Cramin — спецификация v1

> Документ для `docs/SPEC.md` репозитория `PerfectProduct/cramin`. Источник истины для реализации v1.
> Язык интерфейса приложения — русский. Идентификаторы в коде — английские.
> Решения владельца по развилкам сведены в §15.

## 1. Продукт в одном абзаце

Cramin — Android-приложение для зубрёжки слов из реальных текстов. Пользователь даёт источник (ссылку на статью, видео YouTube, PDF или скопированный текст). Приложение извлекает текст, переводит его на выбранный язык и строит из пары «оригинал ↔ перевод» двусторонние карточки. Перевод слова берётся из контекста, в котором оно встретилось. Изучение — в механике Quizlet: тап переворачивает карточку, свайп вправо означает «знаю», свайп влево — «ещё учу». Подготовка карточек требует сети, изучение работает полностью офлайн.

## 2. Глоссарий

- **Документ** — один импортированный источник со всем производным: текстом, переводом, карточками.
- **Лемма** — словарная форма лексической единицы в языке источника.
- **Лексическая единица** — слово или многословное выражение: фразовый глагол, идиома, коллокация.
- **Смысл (sense)** — одно значение леммы с контекстным переводом. Карточка может нести несколько смыслов.
- **Вхождение (occurrence)** — место в тексте, где встретилась единица; служит примером.
- **Карточка** — одна лемма одной части речи в одном документе. Лицевая и оборотная стороны зависят от направления.
- **Колода** — набор карточек для сессии: карточки документа или общая колода «Все невыученные».
- **Направление** — `SRC_FRONT` (лицом слово оригинала) или `TGT_FRONT` (лицом перевод).

## 3. Языки

- Поддерживаемые языки v1: `en`, `ru`, `he`; любая пара из них в любую сторону.
- Язык источника определяется автоматически по письменности (§6.1). Язык перевода выбирает пользователь при импорте; по умолчанию берётся из настроек.
- Если язык источника совпал с языком перевода, импорт блокируется с сообщением и предложением выбрать другой язык.
- Иврит — RTL. Любой текст рендерится с направлением по содержимому (`TextDirection.Content`). Макет экранов остаётся LTR, потому что язык интерфейса русский.

## 4. Пользовательские сценарии

**U1. Первый запуск.** Короткий онбординг из одного экрана: что делает приложение, поле для ключа OpenRouter, кнопка «Проверить ключ» и выбор модели (§11). Без ключа импорт недоступен, а изучение уже созданных карточек доступно всегда.

**U2. Добавление источника.** На главном экране FAB «+ Создать» открывает экран загрузки (§9.2). Пользователь выбирает язык перевода и источник: PDF-файл, ссылку на сайт или YouTube, скопированный текст. Документ появляется в списке со статусом обработки.

**U3. Приём через «Поделиться».** Из браузера, YouTube-клиента или файлового менеджера пользователь делится ссылкой, текстом или PDF в Cramin. Открывается экран загрузки с предзаполненным источником. Импорт запускается одной кнопкой, после выбора языка.

**U4. Фоновая обработка.** Обработка идёт в WorkManager и переживает сворачивание приложения и смерть процесса. Прогресс виден в строке документа и в уведомлении. По завершении приходит уведомление «Карточки готовы: N слов». При ошибке строка показывает «Ошибка — повторить», повтор продолжает с места сбоя.

**U5. Зубрёжка.** Кнопка ▶ у документа открывает экран документа на вкладке «Карточки». Дальше: сессия, тап переворачивает, свайп сортирует, ↶ отменяет, ▶ включает автопроигрывание. В конце раунда предлагается повторить невыученные.

**U6. Параллельный текст.** Вкладка «Текст» на экране документа показывает пары «предложение — перевод». Слова, по которым есть карточки, подчёркнуты. Тап по подчёркнутому слову открывает мини-карточку.

**U7. Общая колода.** Закреплённый первый элемент списка «Все невыученные» — колода из всех невыученных карточек всех готовых документов, с дедупликацией (§10.6).

**U8. Обновление.** В настройках есть кнопка «Проверить обновления». Если есть новая версия: показываются заметки выпуска, затем «Скачать и установить» с проверкой SHA-256 и системной установкой. Прогресс изучения сохраняется (§12).

## 5. Архитектура

### 5.1. Стек

- Kotlin, Jetpack Compose (Material 3), Navigation Compose.
- Room (KSP) с `exportSchema = true`; схемы коммитятся в `app/schemas/`.
- WorkManager (CoroutineWorker, foreground-режим для долгой обработки).
- DataStore (Preferences) для настроек.
- OkHttp и kotlinx.serialization для сети и JSON.
- Readability4J и jsoup для извлечения статей.
- NewPipeExtractor (JitPack) для субтитров и аудиопотока YouTube. Требует `coreLibraryDesugaring`.
- PdfBox-Android (`com.tom-roush:pdfbox-android`) для текста из PDF.
- `android.icu.text.BreakIterator` для разбиения на предложения.
- Системный `TextToSpeech`.
- Android Keystore (AES/GCM) для шифрования ключа API в DataStore. `EncryptedSharedPreferences` не используется: библиотека устарела.
- DI вручную (`AppContainer` в `Application`). Hilt не используется, чтобы сократить поверхность сборки.
- minSdk 26, compileSdk 36, targetSdk 36, JDK 21 для сборки. Версии библиотек — актуальные стабильные на момент сборки, в `gradle/libs.versions.toml`.

### 5.2. Слои и пакеты

```
pro.perfectproduct.cramin
├── app/            Application, AppContainer, MainActivity, навигация, тема
├── data/
│   ├── db/         Room: entities, DAO, миграции, конвертеры
│   ├── prefs/      DataStore, SecretStore (Keystore)
│   └── repo/       DocumentRepository, CardRepository, StudyRepository, UsageRepository
├── ingest/         SourceExtractor (интерфейс) + ArticleExtractor, YoutubeExtractor, PdfExtractor, PlainTextExtractor
├── transcribe/     Transcriber (интерфейс), OpenRouterSttTranscriber, AudioSegmenter
├── llm/            LlmClient (интерфейс), OpenRouterClient, схемы, промпты, ModelCatalog, ModelConfig
├── pipeline/       ProcessDocumentWorker, стадии (Brief, Translate, Extract, Consolidate), Segmenter, SectionPlanner, ChunkPlanner, UnitMerger, SurfaceMatcher, LangDetector, Stoplists
├── study/          DeckBuilder, StudySessionMachine (чистый Kotlin), TtsController
├── update/         UpdateChecker, ApkDownloader, ApkInstaller (изолированный модуль, §12.4)
├── ui/
│   ├── library/    главный экран
│   ├── create/     экран загрузки
│   ├── document/   экран документа: вкладки «Текст» и «Карточки»
│   ├── study/      экран сессии, карточка, свайпы
│   ├── settings/
│   └── components/
└── util/           Log (no-op в release), Clock, Hashing
```

`LlmClient`, `Transcriber` и `SourceExtractor` — интерфейсы с фейками для тестов. Будущие провайдеры (OAuth Gemini, Codex) добавляются новой реализацией `LlmClient` без правок пайплайна.

### 5.3. Потоки данных

```
Источник → SourceExtractor (| Transcriber) → RawText(+title?)
        → LangDetector → Segmenter (абзацы, предложения)
        → [LLM brief] бриф: тема, регистр, глоссарий, заголовок, эмодзи
        → [LLM translate] перевод секциями последовательно (бриф + глоссарий + хвост предыдущей секции)
        → Segment[] (выровненный параллельный текст)
        → [LLM extract] единицы по чанкам «оригинал + готовый перевод», параллельно до 3
        → UnitMerger (группировка по ключу леммы)
        → [LLM consolidate] смыслы для лемм с расходящимися переводами (пакетами)
        → SurfaceMatcher (все вхождения для подчёркивания)
        → Room: Sentence, Segment, Card, Sense, Occurrence
```

## 6. Пайплайн обработки

Принцип: **перевод и извлечение разделены.** Сначала весь документ переводится крупными связными секциями с общим брифом и глоссарием. Затем единицы извлекаются из пары «оригинал + готовый перевод». Так перевод видит контекст всего источника, терминология едина, а карточки согласованы с тем переводом, который пользователь видит во вкладке «Текст».

### 6.1. Определение языка (`LangDetector`)

Считается доля букв каждой письменности среди буквенных символов: иврит — `\p{InHebrew}`, кириллица, латиница. Побеждает письменность с долей ≥ 60 %. Иначе сообщение «Не удалось определить язык текста» и ручной выбор языка источника. Функция детерминированная и покрыта юнит-тестами.

### 6.2. Сегментация (`Segmenter`)

- Нормализация: NFC, унификация переводов строк, схлопывание пробелов. Для PDF — склейка переносов по дефису в конце строки и строк внутри абзаца (§7.3).
- Абзацы режутся по пустым строкам. Предложения внутри абзаца — через `BreakIterator.getSentenceInstance(locale)`.
- Каждое предложение получает сквозной индекс `idx` и `paragraphIdx`.
- Предложения длиннее 600 символов режутся по `;`, `:` или `,`.

### 6.3. Общие правила вызовов LLM

- `POST https://openrouter.ai/api/v1/chat/completions` с `response_format: {type: "json_schema", strict: true}` и `usage: {include: true}`.
- `temperature` и `max_tokens` берутся из роли (§6.12).
- Статичный системный промпт стоит первым: так срабатывает префиксный кэш провайдеров.
- Каждый вызов — строка `Job` в БД (§13): статус, попытки, сырой ответ, токены, стоимость, модель. Это даёт идемпотентность, возобновление после смерти процесса и аудит.
- Невалидный JSON или нарушение схемы — один повтор, затем `FAILED`.
- Ретраи сети и 429/5xx — экспоненциальная задержка (1, 2, 4, 8 с, до 4 попыток) с учётом `Retry-After`.
- Промпты модели пишутся по-английски. Ответ — строго JSON по схеме.

### 6.4. Стадия 1: бриф документа (роль `brief`)

**Вход:**
- весь текст, если в нём ≤ `brief.maxInputWords` слов (по умолчанию 60 000);
- иначе первые 20 000 слов плюс равномерно выбранные абзацы до лимита.

**Системный промпт:**

```
You prepare a translation brief for a document that will be translated from SOURCE to TARGET
and turned into vocabulary flashcards. Read the text and return:
- title: a short title in SOURCE language (<= 60 chars);
- emoji: one emoji that fits the topic;
- summary: 2-4 sentences in TARGET language describing what the text is about;
- domain: short label (e.g. "AI economics", "medicine", "everyday conversation");
- register: one of formal, neutral, informal, technical, literary, conversational;
- glossary: up to 80 recurring or domain-specific terms, multiword expressions and named entities
  that must be translated consistently. For each: src (as in text, dictionary form),
  tgt (the translation to use everywhere in TARGET; for names use the conventional rendering),
  note (short usage note or null).
Output only JSON matching the schema.
```

**Схема ответа:**

```json
{"type":"object","additionalProperties":false,
 "required":["title","emoji","summary","domain","register","glossary"],
 "properties":{
  "title":{"type":"string"},"emoji":{"type":"string"},"summary":{"type":"string"},
  "domain":{"type":"string"},
  "register":{"type":"string","enum":["formal","neutral","informal","technical","literary","conversational"]},
  "glossary":{"type":"array","items":{"type":"object","additionalProperties":false,
    "required":["src","tgt","note"],
    "properties":{"src":{"type":"string"},"tgt":{"type":"string"},"note":{"type":["string","null"]}}}}}}
```

Бриф сохраняется в `Document.briefJson`. Заголовок и эмодзи документа берутся из брифа; если бриф не удался, используется фолбэк из §6.10.

### 6.5. Стадия 2: перевод секциями (роль `translate`)

**Размер секции.** `sectionWords = min(translate.maxSectionWords, floor(maxCompletionTokens × 0.7 / tokensPerWord[targetLang]))`, где:
- `maxSectionWords` по умолчанию 4000;
- `maxCompletionTokens` берётся из каталога OpenRouter для модели (`top_provider.max_completion_tokens`) либо из роли;
- `tokensPerWord` задаётся в конфиге (по умолчанию en 1.4, ru 2.6, he 2.6).

Если документ помещается в одну секцию, он переводится целиком одним вызовом. Границы секций проходят только по абзацам; если абзац длиннее секции — по предложениям.

**Последовательность.** Секции переводятся **последовательно**. Каждая получает бриф (summary, domain, register), глоссарий и **контекст продолжения**: последние 3 предложения предыдущей секции с их переводом, помеченные как уже переведённые.

**Системный промпт:**

```
You are a professional translator. Translate the numbered SOURCE sentences into TARGET.
Use the brief to keep topic, tone and register of the whole document.
Always use the glossary translations for glossary terms.
The CONTEXT block is already translated; do not translate it again, use it only for continuity
(pronouns, abbreviations, terminology introduced earlier).
Translate naturally and faithfully, as a skilled human translator would, not word by word.
You may merge up to 3 adjacent sentences into one translated segment when natural TARGET style
requires it; otherwise keep one segment per sentence. Segments must cover every sentence id exactly once,
in order, without gaps or overlaps. Do not add or omit content.
Output only JSON matching the schema.
```

**Сообщение пользователя:**

```
SOURCE: en
TARGET: ru
BRIEF: <summary> | domain: <domain> | register: <register>
GLOSSARY:
- compute → вычислительные мощности
- frontier lab → передовая лаборатория (note: ...)
CONTEXT (already translated):
[118] ... => ...
[119] ... => ...
SENTENCES:
[120] ...
[121] ...
```

Глоссарий в сообщении фильтруется локально: передаются только термины, встречающиеся в секции (регистронезависимое вхождение `src`), плюс имена собственные.

**Схема ответа:**

```json
{"type":"object","additionalProperties":false,"required":["seg"],
 "properties":{"seg":{"type":"array","items":{"type":"object","additionalProperties":false,
   "required":["from","to","t"],
   "properties":{"from":{"type":"integer"},"to":{"type":"integer"},"t":{"type":"string"}}}}}}
```

**Валидация:**
- Сегменты покрывают все id секции подряд, без дыр и пересечений; `to - from ≤ 2`; `t` непустой.
- Дыры дозапрашиваются одним вызовом с тем же промптом, только по пропущенным предложениям и с контекстом вокруг.
- При `finish_reason == "length"` секция делится пополам по абзацам, обе половины переводятся заново.
- Результат пишется в `Segment` (§13).

### 6.6. Стадия 3: извлечение единиц (роль `extract`)

**Нарезка.** Чанки по ~`extract.chunkWords` слов (по умолчанию 700) целыми сегментами перевода. Чанки обрабатываются **параллельно**, `Semaphore(extract.concurrency)`, по умолчанию 3.

**Системный промпт:**

```
You are a bilingual lexicographer building vocabulary flashcards from a SOURCE text and its
existing TARGET translation. Do not retranslate the text.
Extract lexical units worth learning from the SOURCE sentences:
- content words: NOUN, VERB, ADJ, ADV;
- multiword units: PHRASAL_VERB, IDIOM, COLLOCATION (only if the combined meaning is worth learning as a unit).
Do NOT extract: pronouns, articles, determiners, numerals, prepositions, conjunctions, particles,
auxiliary/modal verbs, interjections, proper names, abbreviations, numbers, URLs.
For each unit give:
- i: sentence id where it occurs (choose the clearest example if it occurs several times in this batch);
- f: the exact span from that SOURCE sentence as written (for discontinuous phrasal verbs, the full span);
- l: the lemma in SOURCE language, dictionary form
     (en: base form; ru: nominative singular / infinitive, use ё where standard;
      he: without niqqud, without attached prefixes ו ה ב ל מ ש כ; verbs in past 3rd person masc. singular);
- lv: Hebrew lemma WITH niqqud if SOURCE is he, otherwise null;
- p: one of NOUN, VERB, ADJ, ADV, PHRASAL_VERB, IDIOM, COLLOCATION;
- g: TARGET translation of the lemma in dictionary form, with the meaning it has IN THIS CONTEXT,
     consistent with the given translation and the glossary (he TARGET: without niqqud);
- ft: the exact span in the GIVEN translation segment that renders this unit, or null if it is not rendered explicitly.
List each (lemma, meaning) pair at most once per batch.
Output only JSON matching the schema.
```

**Сообщение пользователя:** SOURCE/TARGET, отфильтрованный глоссарий, затем пары сегментов:

```
[120] <source sentence>
[121] <source sentence>
=> [120-121] <translation segment>
```

**Схема ответа:**

```json
{"type":"object","additionalProperties":false,"required":["u"],
 "properties":{"u":{"type":"array","items":{"type":"object","additionalProperties":false,
   "required":["i","f","l","lv","p","g","ft"],
   "properties":{"i":{"type":"integer"},"f":{"type":"string"},"l":{"type":"string"},
     "lv":{"type":["string","null"]},
     "p":{"type":"string","enum":["NOUN","VERB","ADJ","ADV","PHRASAL_VERB","IDIOM","COLLOCATION"]},
     "g":{"type":"string"},"ft":{"type":["string","null"]}}}}}}
```

**Валидация (локально, детерминированно):**
1. Единицы с `i` вне чанка отбрасываются.
2. Если `f` не найдено в предложении (без учёта регистра и огласовок), единица сохраняется, но без смещений подсветки.
3. Если `ft` не найдено в сегменте перевода, поле обнуляется.
4. Страховочный стоп-лист (`res/raw/stop_{en,ru,he}.txt`, 150–300 служебных слов) отбрасывает однословные единицы с `l` из списка.
5. Единицы с пустыми `l` или `g` отбрасываются.
6. `finish_reason == "length"` — чанк делится пополам по сегментам.

### 6.7. Слияние единиц (`UnitMerger`)

- Ключ карточки: `lemmaKey = normalize(l) + "|" + p`. Нормализация: NFC, нижний регистр, обрезка пунктуации по краям, для `ru` замена ё→е, для `he` удаление огласовок.
- Все единицы документа с одинаковым ключом собираются в одну карточку.
- Переводы `g` нормализуются так же и дедуплицируются.
- Один различный перевод — один смысл. Два и больше — ключ ставится в очередь консолидации (§6.8).

### 6.8. Стадия 4: консолидация смыслов (роль `consolidate`)

Пакетный вызов, до 40 лемм за запрос, со строгой схемой.

**Системный промпт:**

```
You group translations of a word into distinct meanings.
For each item you get a SOURCE lemma, its part of speech, and occurrences: each has an id,
the contextual TARGET translation, and the source sentence.
Merge occurrences whose translations are synonyms or express the same meaning.
Keep separate only genuinely different meanings.
For each resulting meaning give the single best TARGET translation (dictionary form) and the occurrence ids.
Every occurrence id must appear exactly once. Output only JSON.
```

Вход — список `{k, l, p, o: [{id, g, s}]}`. Выход:

```json
{"items":[{"k":"string","senses":[{"g":"string","ids":[1,2]}]}]}
```

**Валидация:** каждое `id` ровно в одном смысле. При нарушении — фолбэк: каждый различный перевод становится отдельным смыслом. Смыслы сортируются по числу вхождений. Больше 4 смыслов на карточку не выводится; хвост сохраняется в БД.

### 6.9. Пример и все вхождения

- **Пример смысла.** Предпочтение предложениям длиной 40–220 символов, затем самому раннему. Пример — исходное предложение и сегмент перевода, в который оно входит.
- **`SurfaceMatcher`.** Все известные поверхностные формы (`f`) каждой карточки ищутся во всех предложениях документа. Поиск регистронезависимый, по границам слов. Для `he` допускаются префиксы ו/ה/ב/ל/מ/ש/כ. Найденное пишется в `Occurrence` с `isExample = false`. LLM не используется.

### 6.10. Заголовок и эмодзи

Берутся из брифа. Фолбэк: заголовок источника (title статьи или видео, имя PDF) и эмодзи `📄`. Пользователь может переименовать документ.

### 6.11. Оркестрация (`ProcessDocumentWorker`)

- Уникальная работа `process-{documentId}`, `ExistingWorkPolicy.KEEP`, ограничение `NetworkType.CONNECTED`.
- Foreground-уведомление с прогрессом (`FOREGROUND_SERVICE_DATA_SYNC`, тип `dataSync`). На API 33+ `POST_NOTIFICATIONS` запрашивается при первом импорте.
- Статусы документа: `QUEUED → FETCHING → (TRANSCRIBING) → BRIEFING → TRANSLATING → EXTRACTING → CONSOLIDATING → READY` или `FAILED`.
- Прогресс: получение 0–5 %, транскрипция 5–30 % (если есть), бриф 5 %, перевод 45 %, извлечение 35 %, консолидация 10 %. Веса нормируются, если транскрипции нет.
- В начале обработки загружается эффективный конфиг моделей (§6.12), и снимок ролей пишется в документ.
- Извлечённый текст хранится в `files/docs/{id}/source.txt`.
- Повтор после `FAILED` продолжает с первой незавершённой стадии и незавершённых `Job`.
- Версия пайплайна `PIPELINE_VERSION` пишется в документ. «Обработать заново» перезапускает обработку с сохранением статусов карточек: сопоставление по `lemmaKey`.

### 6.12. Роли моделей и конфиг (`ModelConfig`)

**Роли:** `brief`, `translate`, `extract`, `consolidate`, `stt`.

**Файл `config/models.json` в репозитории:**

```json
{
  "schemaVersion": 1,
  "updatedAt": "2026-10-01",
  "roles": {
    "brief":       {"model": "<id>", "temperature": 0.2, "maxTokens": 6000},
    "translate":   {"model": "<id>", "temperature": 0.3, "maxTokens": null},
    "extract":     {"model": "<id>", "temperature": 0.1, "maxTokens": 8000},
    "consolidate": {"model": "<id>", "temperature": 0.1, "maxTokens": 4000},
    "stt":         {"model": "<id>"}
  },
  "pipeline": {
    "brief.maxInputWords": 60000,
    "translate.maxSectionWords": 4000,
    "extract.chunkWords": 700,
    "extract.concurrency": 3,
    "tokensPerWord": {"en": 1.4, "ru": 2.6, "he": 2.6}
  }
}
```

`maxTokens: null` означает «брать `max_completion_tokens` из каталога».

**Источники конфига, по убыванию приоритета:**
1. Переопределения пользователя в настройках — по ролям и отдельным параметрам.
2. Удалённый конфиг `https://raw.githubusercontent.com/PerfectProduct/cramin/main/config/models.json`. Загружается в начале каждой обработки и по кнопке в настройках. Кэшируется с `ETag`; ошибка сети не блокирует работу.
3. Копия, вшитая в APK (`assets/models.json`). Собирается из того же файла при сборке задачей Gradle, чтобы копии не расходились.

**Валидация.**
- Конфиг с неизвестной `schemaVersion` игнорируется.
- Модель текстовой роли без `structured_outputs` или `response_format` в `supported_parameters` каталога отклоняется, и берётся следующий источник. На экране настроек об этом появляется предупреждение.
- Каталог моделей кэшируется на 24 часа.

## 7. Источники

### 7.1. Статья по ссылке

OkHttp GET (User-Agent обычного мобильного браузера, таймаут 30 с, редиректы включены) → Readability4J → текст с абзацами и title. Если извлечено меньше 200 символов: ошибка «Не удалось извлечь текст статьи. Попробуйте скопировать текст вручную». Страницы за логином не поддерживаются.

### 7.2. YouTube

Ссылка распознаётся по хостам `youtube.com`, `m.youtube.com`, `youtu.be`, `music.youtube.com`; Shorts тоже поддерживаются. Порядок:
1. Авторские (не автоматические) субтитры на языке видео через NewPipeExtractor. Язык видео определяется по метаданным или по языку авторской дорожки.
2. Если авторских субтитров нет — собственная транскрипция (§8). **Автоматические субтитры YouTube не используются** (решение владельца: их качество недостаточно).

Субтитры (TTML или VTT) очищаются от таймкодов и дублей и склеиваются в текст. Абзацы формируются по паузам больше 2 с. Заголовок — название видео. Отказ NewPipeExtractor даёт понятную ошибку: «YouTube изменил формат. Проверьте обновления приложения».

### 7.3. PDF

Файл выбирается через `ACTION_OPEN_DOCUMENT` (`application/pdf`) или приходит через share. Текст извлекается PdfBox-Android постранично. Если текста нет (скан), ошибка «PDF не содержит текстового слоя; OCR появится в следующей версии». Отдельной эвристикой удаляются повторяющиеся колонтитулы: строки, одинаковые на трёх и более страницах. Размер не ограничивается. Предупреждение показывается, если текст длиннее 50 000 слов.

### 7.4. Скопированный текст

Многострочное поле, предзаполненное из буфера обмена, если там текст. Необязательное поле заголовка.

### 7.5. Приём через «Поделиться»

Интент-фильтры `ACTION_SEND` для `text/plain` и `application/pdf`. Текст, который целиком является URL, считается ссылкой; иначе это скопированный текст. Открывается экран загрузки с предзаполнением.

## 8. Транскрипция

- `AudioSegmenter` берёт аудиопоток YouTube в AAC/M4A (NewPipeExtractor, предпочтительно itag 140), скачивает во временный файл и режет на сегменты по 10 минут через `MediaExtractor` + `MediaMuxer` без перекодирования.
- `OpenRouterSttTranscriber` для каждого сегмента делает `POST https://openrouter.ai/api/v1/audio/transcriptions` с `{"model": <stt_model>, "input_audio": {"data": <base64>, "format": "m4a"}, "language": <src>}`. Результаты склеиваются по порядку.
- Язык источника для видео без субтитров берётся из метаданных видео. Если определить не удалось, пользователь выбирает язык в диалоге до запуска транскрипции.
- Временные файлы удаляются после успешной транскрипции. Текст транскрипции сохраняется как `source.txt`.
- Длительность и стоимость, если она вернулась, пишутся в учёт.

## 9. Экраны

### 9.1. Главный экран (библиотека)

Референс — главный экран NotebookLM.
- Шапка: «Cramin», иконка поиска (фильтр по заголовку), иконка настроек.
- Вкладки-чипы: **Все / В процессе / Выученные**.
  - «Все» — все документы, включая обрабатываемые и ошибочные.
  - «В процессе» — готовые документы, где выучены не все карточки.
  - «Выученные» — готовые документы, где выучено 100 %.
- Первый элемент — закреплённая «🗂 Все невыученные», если есть хотя бы один готовый документ.
- Элемент документа — скруглённая плашка:
  - слева эмодзи, затем заголовок (одна строка с многоточием);
  - подзаголовок `N слов · выучено K · 30 сент. 2026`;
  - во время обработки подзаголовок «Обработка… 40 %» с тонким прогресс-баром, при ошибке — «Ошибка — нажмите, чтобы повторить»;
  - справа круглая кнопка ▶ (у документа в обработке она неактивна).
- Тап по плашке или ▶ открывает экран документа.
- Долгое нажатие открывает меню: «Переименовать», «Обработать заново», «Удалить» (с подтверждением).
- Плавающая кнопка «+ Создать» внизу по центру. Кнопки камеры в v1 нет.
- Пустое состояние: иллюстрация-эмодзи и «Добавьте первый текст — статью, видео или PDF».

### 9.2. Экран загрузки

Референс — «Create audio overviews» в NotebookLM, без строки веб-поиска.
- Полноэкранный диалог, ✕ в углу.
- Заголовок: «Создать карточки» / «из **ваших текстов**» (акцентный цвет).
- Строка «Перевод на: [RU] [EN] [HE]» — сегментированный выбор, по умолчанию из настроек.
- Подпись «Загрузите источник» и кнопки-пилюли:
  - «📁 Файл PDF»;
  - «🔗 ▶ Сайт или YouTube» — диалог с полем URL, предзаполненным из буфера;
  - «📋 Скопированный текст» — экран с многострочным полем.
- Без ключа API кнопки неактивны, а сверху баннер «Добавьте ключ OpenRouter в настройках».
- После подтверждения диалог закрывается, документ появляется в списке со статусом `QUEUED`.

### 9.3. Экран документа

Референс — экран блокнота NotebookLM.
- Верхняя панель: ←, заголовок в одну строку, ⋮ (переименовать, обработать заново, удалить, «Стоимость обработки: $0.0123 · модель …»).
- Шапка контента: крупное эмодзи, заголовок крупным шрифтом, строка `N слов · выучено K из N · EN → RU`.
- Нижняя навигация из двух вкладок: **«Текст»** (иконка документа) и **«Карточки»** (иконка карточек). Кнопка ▶ из библиотеки открывает вкладку «Карточки».

### 9.4. Вкладка «Текст»

- `LazyColumn` по абзацам. Единица отображения — сегмент перевода: его исходные предложения обычным шрифтом, перевод сегмента под ними вторичным цветом.
- Переключатель вида в панели: «Пары / Только оригинал / Только перевод».
- Слова с карточками подчёркнуты пунктиром. Тап открывает bottom sheet: лемма (для `he` с огласовками), часть речи, смыслы, статус (чипы «Новое / Учу / Знаю» — меняют статус), звезда, 🔊.
- RTL по содержимому для каждого текстового блока.

### 9.5. Вкладка «Карточки» (старт колоды)

- Сводка: всего, новые, учу, знаю, избранные.
- Фильтр колоды: «Все невыученные» (по умолчанию) / «Все» / «Только избранные».
- Кнопка «Учить».
- Тумблер направления в стиле переключателя темы, с подписями языков по краям: `EN ◐ RU`. Значение хранится для документа; по умолчанию — из настроек.
- Опция «Перемешать».

### 9.6. Экран сессии

Референс — Quizlet (скриншоты владельца).
- Сверху: ✕, счётчик `35 / 96`, ⚙ (направление, перемешивание, автоозвучка, интервалы автопроигрывания).
- Прогресс-бар под шапкой.
- Слева оранжевая пилюля — счётчик «ещё учу» в текущем раунде, справа зелёная — «знаю».
- Карточка на весь центр экрана, скруглённая, с 🔊 в левом верхнем углу и ☆ в правом.
- **Лицо при `SRC_FRONT`:** лемма крупно по центру, под ней мелко часть речи. Для `he` — огласованная форма мелко над леммой.
- **Оборот при `SRC_FRONT`:**
  - переводы смыслов, каждый с новой строки, крупно;
  - пример: исходное предложение с выделенной поверхностной формой, под ним перевод предложения с выделенным `ft`;
  - если смыслов несколько, у каждого свой пример; оборот прокручивается.
- **При `TGT_FRONT`:** лицо — переводы смыслов через `;`, оборот — лемма, часть речи и примеры.
- **Жесты:**
  - тап — переворот с 3D-анимацией по оси Y (300 мс);
  - горизонтальный свайп с порогом 30 % ширины или быстрым флингом: вправо «знаю», влево «ещё учу»;
  - во время перетаскивания карточка наклоняется, а края подсвечиваются зелёным или оранжевым;
  - после свайпа сразу показывается следующая карточка лицом вверх.
- Внизу слева ↶ — отмена последнего свайпа: возвращает карточку, её прежний статус и счётчик. Стек отмены хранит всю сессию.
- Внизу справа ▶ / ⏸ — автопроигрывание:
  - лицо 3 с, переворот, оборот 3 с, следующая карточка (интервалы в настройках);
  - статусы при этом не меняются, карточки идут по порядку;
  - если включена автоозвучка, TTS читает лицо, а затем оборот (только переводы, без примеров);
  - любой жест пользователя ставит автопроигрывание на паузу.
- Конец раунда: «Вы знаете K из N». Кнопки «Повторить L невыученных» (новый раунд только из карточек, отправленных влево), «Начать заново», «Готово».
- Выход посреди сессии сохраняет состояние. Повторный вход в ту же колоду предлагает «Продолжить с 35/96» или «Начать заново».

### 9.7. Настройки

- **OpenRouter:**
  - ключ API (маскированный ввод, «Проверить ключ» через `GET /api/v1/key`, остаток кредита, если отдан);
  - раздел «Модели»: для каждой роли (§6.12) — эффективная модель и её источник («конфиг репозитория» / «встроенный» / «своя»);
  - переопределение модели роли — выбор из `GET /api/v1/models` (для текстовых ролей только модели со structured outputs), сортировка по цене, поиск, ручной ввод id; кнопка «Сбросить к конфигу»;
  - «Обновить конфиг моделей» и дата последнего обновления.
- **Изучение:** язык перевода по умолчанию, направление по умолчанию, автоозвучка, скорость речи TTS, интервалы автопроигрывания.
- **Статистика:** суммарная стоимость и токены по всем документам.
- **О приложении:** версия (`versionName`/`versionCode`), «Проверить обновления», ссылка на репозиторий, лицензии открытых библиотек.

## 10. Механика изучения

### 10.1. Статусы карточки

`NEW` (ни разу не сортировалась) · `LEARNING` (последний свайп влево) · `KNOWN` (последний свайп вправо). Свайп вправо всегда ставит `KNOWN`, свайп влево — `LEARNING`. Отдельно хранится флаг `starred`.

### 10.2. Поля под интервальное повторение

В `Card` зарезервированы `dueAt`, `intervalDays`, `ease`, `reps`, `lapses` (nullable). В v1 они не используются, но присутствуют в схеме v1, чтобы SRS добавлялся без миграции данных.

### 10.3. Колоды

- «Все невыученные» документа: `status != KNOWN`.
- «Все» документа: все карточки.
- «Только избранные»: `starred`.
- Порядок: по первому появлению в тексте или перемешанный с зерном, сохранённым в сессии.

### 10.4. `StudySessionMachine`

Чистый Kotlin без Android-зависимостей, покрыт юнит-тестами.
- Состояние: `deckKey`, `order: List<cardId>`, `position`, `round`, `knownThisRound`, `learningThisRound`, `learningIdsThisRound`, `undoStack`, `isFlipped`, `autoplay`.
- События: `Flip`, `SwipeRight`, `SwipeLeft`, `Undo`, `ToggleAutoplay`, `Tick`, `RestartAll`, `RepeatLearning`.
- Свайп записывает статус в БД через репозиторий. `Undo` восстанавливает прежний статус из стека.
- Состояние сохраняется в таблицу `StudySession` после каждого события.

### 10.5. TTS

`TextToSpeech` с `Locale("en")`, `Locale("ru")`, `Locale("iw"/"he")`. Если язык недоступен, 🔊 для него скрыт, а в настройках показывается подсказка установить голос. Лемма на иврите читается по огласованной форме, если она есть.

### 10.6. Общая колода «Все невыученные»

- Карточки со статусом не `KNOWN` из всех документов в `READY`.
- Дедупликация по `(srcLang, tgtLang, lemmaKey)`: показывается карточка из самого свежего документа, с объединёнными смыслами без дубликатов переводов.
- Свайп в общей колоде меняет статус у всех дубликатов.
- Направление общей колоды хранится отдельно; колоды с разными парами языков разделены чипами над кнопкой «Учить».

## 11. Ключ, модели, приватность

- Ключ OpenRouter шифруется AES-256-GCM ключом из Android Keystore (`KeyGenParameterSpec`, без аутентификации пользователя). Шифротекст лежит в DataStore.
- Ключ **никогда** не пишется в лог, не попадает в отчёты об ошибках и не показывается в UI целиком: видны только последние 4 символа.
- В release логирование выключено целиком: обёртка `util.Log` — no-op при `!BuildConfig.DEBUG`. OkHttp `HttpLoggingInterceptor` подключается только в debug, уровень `BASIC`, заголовок `Authorization` редактируется.
- Тексты документов не пишутся в logcat ни в каких сборках. В debug в лог уходят только идентификаторы, счётчики и статусы.
- Запросы уходят только в `openrouter.ai`, на хосты источников, которые выбрал пользователь, в `raw.githubusercontent.com` за конфигом моделей и в `api.github.com` / `github.com` / `objects.githubusercontent.com` для обновлений. Никакой аналитики и телеметрии.
- В запросах к OpenRouter передаются заголовки `HTTP-Referer: https://github.com/PerfectProduct/cramin` и `X-Title: Cramin`.
- `allowBackup = true`, но правила `dataExtractionRules` / `fullBackupContent` исключают DataStore с шифротекстом ключа: после восстановления на другом устройстве ключ всё равно не расшифруется. Пользователь вводит его заново, на экране это объясняется.
- `usesCleartextTraffic = false`.

## 12. Версии, релизы, обновления

### 12.1. Идентичность приложения

- `applicationId = pro.perfectproduct.cramin`: владелец контролирует домен `perfectproduct.pro`. Необратимо после первого релиза.
- Debug-сборка — `applicationIdSuffix ".debug"` и имя «Cramin (debug)», чтобы debug никогда не перезаписывал release и его данные.

### 12.2. Версионирование

- `versionCode` = `git rev-list --count HEAD`; в CI нужен `fetch-depth: 0`, иначе сборка падает с понятной ошибкой.
- `versionName` = `0.1.<versionCode>`, тег `v0.1.<versionCode>`.
- Имя ассета: `cramin-v0.1.<N>.apk` и рядом `cramin-v0.1.<N>.apk.sha256` в формате `sha256sum` (`<hex>  <filename>`).

### 12.3. Подпись и CI

По образцу, уже проверенному в TheyGrow.
- Релизный keystore владелец заводит локально: PKCS12, RSA 4096, срок 30 лет, alias `cramin-release`. Для PKCS12 один пароль на store и key.
- Бэкап — в двух местах, пароль хранится отдельно от файла.
- Секреты GitHub Actions: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`.
- В репозиторий коммитится только публичный SHA-256 отпечаток сертификата: `release-signing/cert-sha256.txt`.
- `scripts/verify_release_signature.py` сравнивает вывод `apksigner verify --print-certs` с отпечатком и валит прогон при несовпадении. Печатаются обе стороны.
- `*.jks`, `*.keystore`, `*.p12`, `local.properties`, `keystore.properties` — в `.gitignore`.
- Workflows:
  - `ci.yml` — на push и PR: `assembleDebug testDebugUnitTest lintDebug`;
  - `instrumented.yml` — на PR и `workflow_dispatch`: эмулятор API 34 x86_64 (`reactivecircus/android-emulator-runner`), `connectedDebugAndroidTest`;
  - `release.yml` — `workflow_dispatch` на `main`: вычисляет версию, собирает `assembleRelease`, подписывает из секретов, сверяет отпечаток, считает SHA-256 и создаёт GitHub Release с тегом `v0.1.<N>` и двумя ассетами. Права `contents: write` выдаются **только этой джобе**. Заметки выпуска — из `git log` с предыдущего тега.
- Первый релиз ставится на телефон с нуля. Все последующие обновляют его поверх и сохраняют базу.

### 12.4. Проверка обновлений (изолированный пакет `update/`)

1. `GET https://api.github.com/repos/PerfectProduct/cramin/releases/latest` без аутентификации (лимит 60 запросов в час на IP). Выполняется **только по нажатию**, без фоновых проверок. Репозиторий публичный.
2. Разбор `tag_name` → `versionCode`. Если он не больше текущего — «У вас последняя версия».
3. Иначе диалог: версия, заметки выпуска (`body` простым текстом), размер APK, кнопка «Скачать и установить».
4. Скачивание APK и `.sha256` через OkHttp в `cacheDir/updates/` с прогрессом и отменой. Проверка SHA-256; при несовпадении файл удаляется, показывается ошибка.
5. Если у приложения нет разрешения на установку, открывается `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` с пояснением. После возврата установка продолжается.
6. Установка через `PackageInstaller` session API; результат приходит через `PendingIntent`-ресивер.
7. В манифесте `REQUEST_INSTALL_PACKAGES`. Всё, что связано с обновлением, живёт в `update/` и в отдельном фрагменте манифеста, чтобы будущий Play-вариант удалял это одним flavor'ом.

### 12.5. Сохранение прогресса между версиями

- `fallbackToDestructiveMigration` запрещён; тест проверяет, что билдер Room его не вызывает.
- Каждое изменение схемы — версия, `Migration` и тест `MigrationTestHelper`.
- Схема v1 экспортирована и закоммичена.
- Настройки DataStore меняются только добавлением ключей с дефолтами.

## 13. Модель данных (Room, схема v1)

```
Document(
  id PK autoinc, title, emoji, sourceType[URL|YOUTUBE|PDF|TEXT], sourceRef, sourceLang, targetLang,
  status[QUEUED|FETCHING|TRANSCRIBING|BRIEFING|TRANSLATING|EXTRACTING|CONSOLIDATING|READY|FAILED], progress REAL,
  errorCode NULL, errorMessage NULL, direction[SRC_FRONT|TGT_FRONT] NULL, pipelineVersion INT,
  briefJson NULL, modelsSnapshotJson NULL, promptTokens INT, completionTokens INT, costUsd REAL NULL, audioSeconds INT,
  wordCount INT, createdAt, updatedAt)

Sentence(id PK, documentId FK CASCADE, idx, paragraphIdx, text, segmentId NULL)
  INDEX(documentId, idx) UNIQUE

Segment(id PK, documentId FK CASCADE, firstSentenceIdx, lastSentenceIdx, translation)
  INDEX(documentId, firstSentenceIdx) UNIQUE

Job(id PK, documentId FK CASCADE, kind[BRIEF|TRANSLATE|EXTRACT|CONSOLIDATE|STT], idx,
  rangeStart NULL, rangeEnd NULL, status[PENDING|DONE|FAILED], attempts, model,
  responseJson NULL, finishReason NULL, promptTokens, completionTokens, costUsd NULL, updatedAt)
  INDEX(documentId, kind, idx) UNIQUE

Card(id PK, documentId FK CASCADE, lemmaKey, lemma, lemmaVocalized NULL, pos, lang, targetLang,
  status[NEW|LEARNING|KNOWN], starred BOOL, firstSentenceIdx, updatedAt,
  dueAt NULL, intervalDays NULL, ease NULL, reps NULL, lapses NULL)
  INDEX(documentId, lemmaKey) UNIQUE; INDEX(lang, targetLang, lemmaKey); INDEX(status)

Sense(id PK, cardId FK CASCADE, idx, translation, exampleOccurrenceId NULL)

Occurrence(id PK, cardId FK CASCADE, senseId NULL FK SET NULL, sentenceId FK CASCADE,
  surface, targetSurface NULL, start NULL, end NULL,
  targetStart NULL, targetEnd NULL,        -- смещения внутри Segment.translation
  isExample BOOL)

StudySession(deckKey PK, stateJson, updatedAt)
```

`deckKey`: `doc:{id}:{filter}` или `all:{src}-{tgt}`. Счётчики для плашек библиотеки считаются запросом с агрегатами и отдаются `Flow`.

## 14. Тестирование

### 14.1. Юнит-тесты (JVM, обязательны)

- `LangDetector`: en/ru/he, смешанный текст, мало букв.
- `Segmenter`: абзацы, аббревиатуры (`Dr.`, `т. е.`), длинные предложения, склейка переносов PDF.
- Нарезка секций перевода (формула размера, границы абзацев) и чанков извлечения (целыми сегментами); деление пополам при `length`.
- Валидация сегментов перевода: покрытие, дыры, пересечения, `to - from ≤ 2`, дозапрос дыр.
- Фильтрация глоссария по секции.
- Валидация единиц: чужие `i`, стоп-лист, пустые поля, `f` / `ft` не найдены.
- `ModelConfig`: приоритет источников, неизвестная `schemaVersion`, отклонение модели без structured outputs, совпадение вшитой копии с `config/models.json`.
- `UnitMerger` и нормализация ключей (ё/е, огласовки, регистр), постановка в очередь консолидации.
- Разбор консолидации и фолбэк при нарушении инварианта «каждый id ровно один раз».
- `SurfaceMatcher`: границы слов, ивритские префиксы, регистр.
- `StudySessionMachine`: свайпы, счётчики, конец раунда, повтор невыученных, полная цепочка отмен, автопроигрывание по `Tick`, пауза при жесте, восстановление из JSON.
- Сравнение версий и разбор ответа GitHub; проверка SHA-256.
- Очистка субтитров (VTT/TTML → текст).
- Сквозной тест пайплайна с `FakeLlmClient`: фикстурные тексты en/ru/he (`src/test/resources/fixtures/`) → детерминированные ответы, сгенерированные по id предложений → ожидаемые карточки, смыслы и примеры. Отдельно проверяется возобновление после «падения» посреди чанков.
- Живые тесты (тег `live`) к OpenRouter на коротких фикстурах en/ru/he, по всем стадиям. Ключ берётся из `.env` (§14.4); без ключа тесты пропускаются. Общий бюджет прогона ограничен `LIVE_BUDGET_USD` по фактическому `usage.cost`; при превышении прогон останавливается.

### 14.2. Инструментированные тесты (эмулятор)

- Room: схема v1 создаётся `MigrationTestHelper`; тест-заготовка для будущих миграций.
- `SecretStore`: шифрование, расшифровка, отсутствие ключа в открытом виде в файле DataStore.
- Compose UI сессии: тап переворачивает, свайп вправо и влево меняет счётчики и статус в БД, ↶ возвращает, конец раунда показывает сводку.
- Библиотека: документ из фейкового репозитория виден, ▶ открывает вкладку «Карточки».
- Share-интент: `ACTION_SEND text/plain` с URL открывает экран загрузки с предзаполненной ссылкой.
- `ProcessDocumentWorker` через `WorkManagerTestInitHelper` с `FakeLlmClient` доводит документ до `READY`.

### 14.3а. Живые инструментированные тесты (эмулятор, тег `live`)

- Сквозной пайплайн на каждой фикстуре через реальный OpenRouter до `READY`.
- YouTube: короткое видео с авторскими субтитрами — получение текста. Короткое видео (1–3 мин) без авторских субтитров — `AudioSegmenter` плюс STT.
- Статья по реальному URL.
- Ключ передаётся аргументом инструментирования в момент запуска и в APK не вшивается (§14.4).

### 14.3. Ручной смоук владельца (в `docs/RUNBOOK.md`)

Реальный ключ; по одному документу каждого типа источника; транскрипция видео без субтитров; иврит в обе стороны; сессия и отмена; выход и продолжение; обновление с версии N на N+1 с сохранением статусов.

### 14.4. Секреты для разработки

- `.env` в корне репозитория (в `.gitignore`, права 600):
  - `OPENROUTER_API_KEY`;
  - `LIVE_BUDGET_USD` (по умолчанию 1.00);
  - необязательно `CRAMIN_KEYSTORE_PROPERTIES` — путь к файлу подписи **вне** репозитория.
- `.env.example` без значений коммитится.
- Создание — скриптом `scripts/setup-secrets.sh` (скрытый ввод, опционально keystore и секреты GitHub) или вручную: `cp .env.example .env && chmod 600 .env && nano .env`.
- Gradle читает `.env` только для задач тестов: переменные окружения JVM-тестов и аргументы инструментирования, передаваемые `scripts/test-device.sh --live`. **Ни в `BuildConfig`, ни в ресурсы, ни в APK ключ не попадает**; это проверяет тест, который сканирует собранный debug-APK на отсутствие префикса ключа.

## 15. Развилки, принятые по умолчанию

| № | Вопрос | Решение |
|---|---|---|
| 1 | applicationId | `pro.perfectproduct.cramin` (решение владельца) |
| 2 | Установка обновления | в приложении: скачивание + SHA-256 + PackageInstaller (решение владельца) |
| 3 | Публикация релиза | CI, `contents: write` только в release-джобе (решение владельца) |
| 4 | YouTube без авторских субтитров | сразу своя транскрипция; автосубтитры не используются (решение владельца) |
| 5 | Словарная форма иврита | глагол — прош. вр. 3 л. ед. ч. м. р.; лемма с огласовками на карточке (решение владельца) |
| 6 | Модели | роли в `config/models.json` с удалённым обновлением и переопределением в настройках (решение владельца) |
| 7 | Качество перевода | перевод крупными секциями с брифом и глоссарием, извлечение — отдельно по готовому переводу (решение владельца) |

## 16. Вне v1 (бэклог)

Фото с OCR, EPUB, интервальное повторение (поля уже в схеме), глобальный словарь «я это знаю», экспорт в Anki/CSV, OAuth Gemini и Codex как провайдеры, Play-flavor без апдейтера, синхронизация между устройствами, оценка стоимости до запуска обработки.
