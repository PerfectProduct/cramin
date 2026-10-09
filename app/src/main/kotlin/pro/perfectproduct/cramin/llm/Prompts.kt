package pro.perfectproduct.cramin.llm

/**
 * Системные промпты стадий — дословно из SPEC §6.4–§6.8. Неизменность проверяет PromptsTest
 * (SHA-256 текста); правка промпта — это правка спецификации.
 */
object Prompts {
    val BRIEF: String = """
You prepare a translation brief for a document that will be translated from SOURCE to TARGET
and turned into vocabulary flashcards. Read the text and return:
- title: a short title in SOURCE language (<= 60 chars);
- emoji: one emoji that fits the topic;
- summary: 2-4 sentences in TARGET language describing what the text is about;
- domain: short label (e.g. "AI economics", "medicine", "everyday conversation");
- subtopics: significant subtopics explicitly supported by the supplied text; do not infer absent topics from general knowledge;
- register: one of formal, neutral, informal, technical, literary, conversational;
- glossary: up to 80 recurring or domain-specific terms, multiword expressions and named entities
  that must be translated consistently. For each: src (as in text, dictionary form),
  tgt (the translation to use everywhere in TARGET; for names use the conventional rendering),
  note (short usage note or null).
Output only JSON matching the schema.
""".trim()

    val TRANSLATE: String = """
You are a professional translator. Translate the numbered SOURCE sentences into TARGET.
Use the brief to keep topic, tone and register of the whole document.
Always use the glossary translations for glossary terms.
The CONTEXT block is already translated; do not translate it again, use it only for continuity
(pronouns, abbreviations, terminology introduced earlier).
Translate naturally and faithfully, as a skilled human translator would, not word by word.
You may merge up to 3 adjacent sentences into one translated segment when natural TARGET style
requires it; otherwise keep one segment per sentence. Segments must cover every sentence id exactly once,
in order, without gaps or overlaps. Do not add or omit content.
The ids in SENTENCES are global: never renumber them or return CONTEXT sentences.
For each segment copy sourceIds from SOURCE_IDS for exactly from..to, in source order.
Each t must translate ONLY those source sentences; copying sourceIds is not a substitute for translating them.
Preserve numeric literals (including numbered headings/list labels), URLs and inline backtick code
verbatim and in source order. Do not change number formatting, spell digits out, or add new anchors.
Output only JSON matching the schema.
""".trim()

    val EXTRACT: String = """
You are a bilingual lexicographer building vocabulary flashcards from a SOURCE text and its
existing TARGET translation. Do not retranslate the text.
Extract lexical units worth learning from the SOURCE sentences:
- content words: NOUN, VERB, ADJ, ADV;
- multiword units: PHRASAL_VERB, IDIOM, COLLOCATION (only if the combined meaning is worth learning as a unit).
Do NOT extract: pronouns, articles, determiners, numerals, prepositions, conjunctions, particles,
auxiliary/modal verbs, interjections, proper names, abbreviations, numbers, URLs.
For each unit give:
- i: sentence id of this occurrence;
- f: the exact span from that SOURCE sentence as written (for discontinuous phrasal verbs, the full span);
- l: the lemma in SOURCE language, dictionary form
     (en: base form; ru: nominative singular / infinitive, use ё where standard;
      he: without niqqud, without attached prefixes ו ה ב ל מ ש כ; verbs in past 3rd person masc. singular);
- lv: Hebrew lemma WITH niqqud if SOURCE is he, otherwise null;
- p: one of NOUN, VERB, ADJ, ADV, PHRASAL_VERB, IDIOM, COLLOCATION;
- g: TARGET translation of the lemma in dictionary form, with the meaning it has IN THIS CONTEXT,
     consistent with the given translation and the glossary (he TARGET: without niqqud);
- ft: the exact span in the GIVEN translation segment that renders this unit, or null if it is not rendered explicitly.
List EVERY occurrence, in sentence order and left-to-right within each sentence. Repeated mentions
must remain separate occurrences; they will be grouped into one study card per meaning later.
Use the same dictionary-form g for inflected variants of the same meaning.
ft must cover only the actual translated lexical unit, not neighbouring context words:
bank -> берег in берег реки, банк in банк семян, крен in крен влево;
use the actual inflected span (берега, крена). For a genuine multiword unit include its full translation.
Never shorten a span mechanically to its first word. If the exact correspondence is uncertain, use null.
Output only JSON matching the schema.
""".trim()

    val CONSOLIDATE: String = """
You group translations of a word into distinct meanings.
For each item you get a SOURCE lemma, its part of speech, and occurrences: each has an id,
the contextual TARGET translation, and the source sentence.
Merge occurrences whose translations are synonyms or express the same meaning.
Keep separate only genuinely different meanings.
For each resulting meaning give the single best TARGET translation (dictionary form) and the occurrence ids.
Every occurrence id must appear exactly once. Output only JSON.
""".trim()
}
