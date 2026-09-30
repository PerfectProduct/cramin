package pro.perfectproduct.cramin.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * База Cramin, схема v1 (SPEC §13). `exportSchema = true`: JSON-схема коммитится в `app/schemas/`.
 * Каждое изменение схемы — новая версия, [Migration] в [MIGRATIONS] и тест с MigrationTestHelper (SPEC §12.5).
 * `fallbackToDestructiveMigration*` запрещён: прогресс изучения переживает обновления.
 */
@Database(
    entities = [
        DocumentEntity::class,
        SentenceEntity::class,
        SegmentEntity::class,
        JobEntity::class,
        CardEntity::class,
        SenseEntity::class,
        OccurrenceEntity::class,
        StudySessionEntity::class,
    ],
    version = CraminDatabase.VERSION,
    exportSchema = true,
)
abstract class CraminDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun sentenceDao(): SentenceDao
    abstract fun segmentDao(): SegmentDao
    abstract fun jobDao(): JobDao
    abstract fun cardDao(): CardDao
    abstract fun studySessionDao(): StudySessionDao

    companion object {
        const val VERSION = 1
        const val NAME = "cramin.db"

        /** Миграции между версиями схемы; в v1 пусто. */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun build(context: Context): CraminDatabase =
            Room.databaseBuilder(context.applicationContext, CraminDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()

        /** База в памяти для тестов; продовый билдер выше остаётся единственным местом с именем файла. */
        fun inMemory(context: Context): CraminDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, CraminDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
