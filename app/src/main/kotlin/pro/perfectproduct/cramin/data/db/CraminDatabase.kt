package pro.perfectproduct.cramin.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * База Cramin, схема v2 (SPEC §13). `exportSchema = true`: JSON-схема коммитится в `app/schemas/`.
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
        ReprocessState::class,
    ],
    version = CraminDatabase.VERSION,
    exportSchema = true,
)
abstract class CraminDatabase : RoomDatabase() {
    // All processors and reprocess requests share this lock in the single app process.
    private val documentLocks = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.sync.Mutex>()
    fun documentLock(id: Long) = documentLocks.getOrPut(id) { kotlinx.coroutines.sync.Mutex() }

    abstract fun reprocessDao(): ReprocessDao
    abstract fun documentDao(): DocumentDao
    abstract fun sentenceDao(): SentenceDao
    abstract fun segmentDao(): SegmentDao
    abstract fun jobDao(): JobDao
    abstract fun cardDao(): CardDao
    abstract fun studySessionDao(): StudySessionDao

    companion object {
        const val VERSION = 4
        const val NAME = "cramin.db"

        /** Аддитивная миграция: существующие данные v1 не меняются. */
        val MIGRATIONS: Array<Migration> = arrayOf(object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `ReprocessState` (`documentId` INTEGER NOT NULL, `snapshotJson` TEXT NOT NULL, `pending` INTEGER NOT NULL, PRIMARY KEY(`documentId`), FOREIGN KEY(`documentId`) REFERENCES `Document`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }, object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Document ADD COLUMN failureJson TEXT")
            }
        }, MeaningMigration)

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
