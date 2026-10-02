package pro.perfectproduct.cramin.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import pro.perfectproduct.cramin.pipeline.MeaningKey

/** Preserve physical Card IDs where possible; clone progress before moving each remaining meaning. */
object MeaningMigration : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Card ADD COLUMN meaningKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE Document ADD COLUMN studyNotice INTEGER NOT NULL DEFAULT 0")
        db.execSQL("DROP INDEX index_Card_documentId_lemmaKey")
        db.execSQL("DROP INDEX index_Card_lang_targetLang_lemmaKey")
        val ids = mutableListOf<Long>()
        db.query("SELECT id FROM Card ORDER BY id").use { while (it.moveToNext()) ids += it.getLong(0) }
        for (id in ids) {
            val groups = linkedMapOf<String, MutableList<Long>>()
            db.query("SELECT id,translation FROM Sense WHERE cardId=$id ORDER BY idx,id").use {
                while (it.moveToNext()) groups.getOrPut(MeaningKey.of(it.getString(1))) { mutableListOf() }.add(it.getLong(0))
            }
            if (groups.isEmpty()) groups["legacy:$id"] = mutableListOf()
            for ((index, entry) in groups.entries.withIndex()) {
                val (key, senses) = entry
                val newId = if (index == 0) {
                    db.execSQL("UPDATE Card SET meaningKey=? WHERE id=?", arrayOf<Any>(key,id)); id
                } else {
                    db.execSQL("""INSERT INTO Card(documentId,lemmaKey,lemma,lemmaVocalized,pos,lang,targetLang,status,starred,firstSentenceIdx,updatedAt,dueAt,intervalDays,ease,reps,lapses,meaningKey)
                        SELECT documentId,lemmaKey,lemma,lemmaVocalized,pos,lang,targetLang,status,starred,firstSentenceIdx,updatedAt,dueAt,intervalDays,ease,reps,lapses,? FROM Card WHERE id=?""", arrayOf<Any>(key,id))
                    db.query("SELECT last_insert_rowid()").use { check(it.moveToFirst()); it.getLong(0) }
                }
                if (senses.isNotEmpty()) {
                    val canonical = senses.first()
                    if (groups.size == 1) db.execSQL("UPDATE Occurrence SET senseId=? WHERE cardId=? AND senseId IS NULL", arrayOf<Any>(canonical,id))
                    else db.execSQL("UPDATE Document SET studyNotice=1 WHERE id=(SELECT documentId FROM Card WHERE id=?)",arrayOf<Any>(id))
                    val list = senses.joinToString(",")
                    db.execSQL("UPDATE Occurrence SET cardId=?, senseId=? WHERE senseId IN ($list)", arrayOf<Any>(newId,canonical))
                    db.execSQL("UPDATE Sense SET cardId=?, idx=0 WHERE id=?", arrayOf<Any>(newId,canonical))
                    if (senses.size > 1) db.execSQL("DELETE FROM Sense WHERE id IN (${senses.drop(1).joinToString(",")})")
                    db.execSQL("UPDATE Card SET firstSentenceIdx=COALESCE((SELECT MIN(s.idx) FROM Occurrence o JOIN Sentence s ON s.id=o.sentenceId WHERE o.cardId=? AND o.senseId IS NOT NULL),firstSentenceIdx) WHERE id=?", arrayOf<Any>(newId,newId))
                }
            }
        }
        db.execSQL("CREATE UNIQUE INDEX index_Card_documentId_lemmaKey_meaningKey ON Card(documentId,lemmaKey,meaningKey)")
        db.execSQL("CREATE INDEX index_Card_lang_targetLang_lemmaKey_meaningKey ON Card(lang,targetLang,lemmaKey,meaningKey)")
        // Shared sessions also changed identity even where individual cards contained only one sense.
        // Keep a readable invalidation marker, never reinterpret old undo IDs as new meanings.
        db.execSQL("UPDATE Document SET studyNotice=1 WHERE EXISTS(SELECT 1 FROM StudySession)")
        db.execSQL("UPDATE StudySession SET stateJson='{\"resetReason\":\"meaning-model-v4\"}'")
    }
}
