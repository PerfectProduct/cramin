# CONSOLIDATING UNKNOWN — 2026-10-03

## Evidence and limits

Phone: 0.1.24-debug, URL, CONSOLIDATING / UNKNOWN, observedAt=1791027829315,
no recorded request event. Seeing 54% earlier does not locate the failure. Installed production
source is commit 09c540c; 9431fd8 only added BRIEF test/evidence. This is not the old BRIEF
BAD_REQUEST. Exact phone exception is **unknown**; no phone database, article or response was read.
No paid calls were made in this investigation. Earlier two-call BRIEF authorization is exhausted.

## What the stage means in 0.1.24

`DocumentProcessor.run` reaches `consolidateAndBuildStage` after fetch/language, brief,
translation and extraction. A failed BRIEF can have an allowed fallback; entering consolidation
does not mean a successful BRIEF. URL progress normally enters consolidation at 90%; the observed
54% is not evidence about the final failure point.

The stage includes:

1. Loading Segment translations and EXTRACT Job responseJson; strict StoredExtraction decoding.
2. UnitValidator (ranges, stoplists, surfaces/offsets), UnitMerger (lemma/POS and translation groups).
3. For groups with multiple translations: batches of at most 40 lexical groups, saved job lookup,
   or **CONSOLIDATE API call**. Invalid JSON can cause one further call. Other HTTP/client retries
   remain governed by the existing client. This stage is not purely local.
4. Response parsing, validation of occurrence IDs, conservative fallback, ExamplePicker.
5. Reprocess progress capture; one Room transaction deletes/replaces cards, senses/occurrences,
   restores progress by lemma+meaning, sets READY, completes snapshot. Cleanup follows commit.

`processLocked` catches non-cancellation exceptions. 0.1.24 converted unclassified exceptions to
UNKNOWN and persisted only stage/code/time, plus request diagnostics if the exception was an LLM
failure carrying them. Plain parsing/DB exceptions lost type and stack. A successful HTTP response
followed by a local exception also had no request event. Missing event does NOT imply no API call.
A failure after committed READY cannot revert completion. Cancellation is rethrown.

## Reproduced defects, not established phone causes

* CONSOLIDATE `finish_reason=length`, both first response and JSON-retry response, raised private
  TruncatedResponse; only InvalidResponse was caught at this stage. Result: CONSOLIDATING UNKNOWN,
  no request event. Fixed by the existing conservative fallback policy: keep distinct normalized
  translations, persist DONE/fallback, no extra length retry. Synonymous variants may remain separate;
  different meanings are never combined by this fallback.
* Initial parsing accepted JSON fences but cached parsing did not. Replaying a cached fenced response
  silently selected fallback and could change meanings. Both now use the same parser. Broken persisted
  JSON stops with a safe local diagnostic rather than silently replacing progress.
* Incomplete/null-range persisted EXTRACT jobs now fail explicitly before replacement. Coverage of
  sentences/segments is checked. These are guards, not evidence that phone data is corrupt.
* Pre-v4 grouping normalized punctuation/ё/HE marks differently. This can shift batch-local occurrence
  IDs. New saved consolidation JSON adds `cacheInputSha256` binding the full batch (not exported).
  Old readers ignore that extra field. Unbound legacy responses are accepted only when reconstructed
  historical and current batch items match. Incompatible batches stop; no guessed mapping of KNOWN.

The first two tests failed against the previous implementation, then passed. Local fault injection
is an exception simulation, **not Android process death**.

## Safe new event

Optional `FailureDiagnostic.local` stores event build/versionCode, random attempt ID, cache-only flag,
concrete substage, up to eight exception/cause **class names**, up to sixteen application stack frames
(class/method/line only), allowlisted numeric structural counts, consolidation client invocation and
response counts. No exception message, file path, text, URL, key or provider body is stored/copied.
Build comes from the event; copy-time build remains separately labelled.

Saved batch state and attempts are explicitly **historical cache metadata**, not the failing request's
parameters. UNFINISHED_LENGTH can reveal a persisted length-limited response without claiming when
it occurred. NOT_INVOKED describes only this consolidation attempt; client INVOKED is not a claim
that HTTP reached a provider. Missing request event remains UNKNOWN. Old events acquire no new fields
until an actual new attempt. A process kill/OOM or inability to write the database may still prevent
recording a diagnostic; this is not crash telemetry.

## Durable results, migrations and network-free retry

Already persisted: source file, Sentence, Segment translations, BRIEF response/fallback, normalized
EXTRACT responseJson per DONE Job; successful CONSOLIDATE responses or DONE/fallback; token/cost
accounting and configuration snapshot. Migrations 1→2→3→4 preserve Sentence/Segment/Job JSON.
v4 splits study cards/senses and safely invalidates ambiguous study sessions; it does not rewrite jobs.
No schema change/destructive migration in this fix. Legacy roles-only snapshots decode with historical
models and fallback parameters; cache-only retry never refreshes catalog or uses those parameters
for requests. Existing snapshot progress and transactional replacement remain intact.

Normal retry skips DONE jobs, but a failed BRIEF fallback or unfinished CONSOLIDATE can call paid API.
It is NOT a guaranteed free retry. Reprocess clears intermediates and may redo paid work.

New **«Повторить консолидацию без API»** for FAILED/CONSOLIDATING uses persisted configuration,
sentences, translations and jobs only. It skips extraction, language detection, BRIEF, translation,
STT, catalog/config refresh. Scheduler carries the durable cache-only WorkData flag, without network
constraint. Incomplete cache stops with diagnostic; it never automatically escalates to network.
A second guard in callRaw forbids any LLM invocation. Prior worker is cancelled/awaited, document mutex
serializes database operations. Existing cards survive validation failure or replacement rollback.
READY short-circuits repeat work. No public/test bypass or fake client is added to production.

## Owner next step (zero new API expense)

1. Install the supplied signed debug APK over 0.1.24; do not uninstall or clear storage.
2. Open the failed article. Optionally copy the old diagnostic first for comparison.
3. Tap **«Повторить консолидацию без API»**, not the ordinary retry or reprocess action.
4. If FAILED again, tap «Скопировать диагностику» and send the complete new block. It must contain
   Local event build, Attempt, Substage, Exception types, Application frames and structural counts.
   No document/database export is needed. If it reaches READY, report that and the remaining last-failure
   timestamp: the retained old failure is historical, not proof of a new failure.
5. CacheMissing/CacheInvalid means an automatic free finish is unavailable. Share diagnostic first;
   any proposed new paid processing requires separate authorization. No new paid calls are authorized.

This establishes a safe next observation, not a promise that the phone's unknown failure will reproduce.

## Follow-up: Payment → resume → consolidation (2026-10-03)

Second phone report: TEXT, 0.1.24, observedAt=1791032941622, CONSOLIDATING/UNKNOWN without
request event; owner observed a balance-related stop near 54%, then two stops near 89%.
Neither the percentages nor that observation establish the causes of the later failures.
No original source text or phone database was accessed; the supplied title is not a test fixture.

Previously the client test covered HTTP 402 → Payment without retry, and pipeline tests covered
generic interruption, but there was no complete Payment → persisted resume → consolidation scenario.
`PaymentResumeTest` adds five cases: Payment during translation, concurrent extraction and consolidation;
also Payment after the first successful half of a split translation/extraction request. All use fakes.
The first three deliberately inject an independent local write failure after restoration, then recover
using cache-only consolidation. This separates PAYMENT from a later UNKNOWN instead of assuming causation.

Persistence facts:

- Translation publishes a whole validated section and marks its job DONE in one transaction.
  Previously DONE sections survive; unfinished sections can exist as PENDING jobs without responseJson.
- Extraction saves a whole assembled chunk as one DONE Job update. Parallel siblings may already be
  DONE when another gets Payment; others are cancelled. A new processor retries only unfinished jobs.
- Split-range halves are held in memory until the full range succeeds. Usage for successful calls may
  already be persisted, while their half-result is not. Ordinary retry can therefore pay for work again.
  A response lost before durable accounting is not proof of zero provider expense either.
- Consolidation retains completed batches and does not replace cards until every required batch has
  a result/fallback. Unique Job(documentId,kind,idx) prevents duplicate job rows; the frozen config and
  saved plan are reused. Normal Payment/resume does not itself change ranges or batch meaning mappings.
- Tests compare saved DONE jobs unchanged, retained segments, assembled extraction output against an
  uninterrupted baseline, exact segment coverage, unique resulting occurrences/cards, and zero model
  calls from cache-only recovery. Partial-result retry creates no additional duplicates in these cases.
  Raw extraction output itself can contain repeated entries; UnitValidator deduplicates matching spans.
  An initial overly strict test assertion against any raw repetition was corrected to compare the
  uninterrupted baseline and assert uniqueness of final occurrences; no application fix was needed.

What 0.1.26 cache-only checks actually establish:

1. Saved snapshot/languages/sentences exist; every sentence has a segment translation entry.
2. EXTRACT jobs exist, are DONE with decodable responseJson and valid endpoint ranges; their range
   union covers every stored sentence. Units then pass existing surface/POS/stoplist validation.
3. Each required CONSOLIDATE batch is DONE with JSON or explicit fallback, parses successfully and
   satisfies the current binding/legacy compatibility rules described above.
4. Only then does atomic card replacement run. Missing/incompatible data produces local diagnostics;
   the command does not call a provider, fetch catalog or rerun earlier stages.

Limits: these checks are structural, not a proof that a model found every useful word. Range-union
coverage does not independently reject all hypothetical overlapping EXTRACT ranges or overlapping
Segment rows in a manually corrupted database. Untagged non-legacy cached responses have no historical
input hash. The normal saved planner/transactions avoid those states, and Payment tests did not produce
them. No production code was changed on those hypotheses. If phone diagnostics implicate such a state,
reproduce that concrete condition before choosing a fix. CacheMissing after Payment is an expected
safe stop, not proof of the later old UNKNOWN's cause.

Targeted command: `./gradlew testDebugUnitTest -Plive=false --tests '*PaymentResumeTest'
--tests '*OpenRouterClientTest' --tests '*ConsolidationRecoveryTest'` — 22 passed, 0 failed/skipped.
No paid calls, real source regeneration, device operations or process-death simulation in this follow-up.
Owner should keep the planned 0.1.26 cache-only checks for both documents; production code is unchanged.
