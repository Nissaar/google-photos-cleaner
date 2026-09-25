package xyz.photocleaner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import xyz.photocleaner.security.DatabaseKeyProvider
import java.io.File

class Converters {
    @TypeConverter fun toVerdict(value: String): Verdict = Verdict.valueOf(value)
    @TypeConverter fun fromVerdict(verdict: Verdict): String = verdict.name

    @TypeConverter fun toMode(value: String?): CleanupMode? =
        value?.let { runCatching { CleanupMode.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromMode(mode: CleanupMode?): String? = mode?.name
}

/**
 * Schema upgrades. The app is in people's hands, and their verdicts exist nowhere
 * else, so every version change needs a real migration here — never a rebuild.
 * Each one is checked against the committed schema files by MigrationsTest.
 */
object Migrations {

    /** v4 records how each applied item was carried out: trash or album. */
    internal val SQL_3_4 = listOf(
        "ALTER TABLE decisions ADD COLUMN appliedMode TEXT",
        // Before v4 the two modes left identical rows. Label them as trash, which is
        // exactly how every earlier version treated them, so nothing changes on upgrade.
        "UPDATE decisions SET appliedMode = 'TRASH' WHERE applied = 1",
    )

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) = SQL_3_4.forEach(db::execSQL)
    }

    val ALL = arrayOf(MIGRATION_3_4)
}

@Database(
    entities = [Decision::class, MonthCount::class, ScanState::class],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun decisionDao(): DecisionDao

    abstract fun libraryIndexDao(): LibraryIndexDao

    companion object {
        private const val DB_NAME = "photocleaner.db"

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppDatabase {
            // SQLCipher's native library must be loaded before the factory is used.
            System.loadLibrary("sqlcipher")

            val passphrase = DatabaseKeyProvider.getPassphrase(context)
            val factory = SupportOpenHelperFactory(passphrase)

            // Deliberately no fallbackToDestructiveMigration(): a missing migration
            // must fail loudly in testing, not silently erase people's verdicts.
            return Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .addMigrations(*Migrations.ALL)
                .build()
        }

        /** Closes and erases the encrypted database and its key. */
        fun wipe(context: Context) {
            synchronized(this) {
                instance?.close()
                instance = null
                val appContext = context.applicationContext
                appContext.deleteDatabase(DB_NAME)
                // Room may leave -wal/-shm siblings behind.
                listOf("$DB_NAME-wal", "$DB_NAME-shm").forEach { name ->
                    File(appContext.getDatabasePath(DB_NAME).parentFile, name).delete()
                }
                DatabaseKeyProvider.clear(appContext)
            }
        }
    }
}
