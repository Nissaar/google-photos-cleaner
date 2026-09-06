package xyz.photocleaner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import xyz.photocleaner.security.DatabaseKeyProvider
import java.io.File

class Converters {
    @TypeConverter fun toVerdict(value: String): Verdict = Verdict.valueOf(value)
    @TypeConverter fun fromVerdict(verdict: Verdict): String = verdict.name
}

@Database(
    entities = [Decision::class, MonthCount::class, ScanState::class],
    version = 3,
    exportSchema = false,
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

            return Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                // The database holds only local verdicts. If a schema change ever makes
                // it unreadable, rebuilding is preferable to blocking the app.
                .fallbackToDestructiveMigration()
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
