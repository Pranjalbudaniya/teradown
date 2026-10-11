package com.tera.down.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tera.down.core.database.dao.DownloadManifestDao
import com.tera.down.core.database.dao.DownloadTaskDao
import com.tera.down.core.database.dao.LibraryMediaDao
import com.tera.down.core.database.entity.DownloadManifestEntity
import com.tera.down.core.database.entity.DownloadTaskEntity
import com.tera.down.core.database.entity.LibraryMediaEntity

@Database(
    entities = [
        DownloadTaskEntity::class,
        LibraryMediaEntity::class,
        DownloadManifestEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun libraryMediaDao(): LibraryMediaDao
    abstract fun downloadManifestDao(): DownloadManifestDao

    companion object {
        private const val DATABASE_NAME = "teradown_database.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE library_media ADD COLUMN lastPositionMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS download_manifest (
                        id TEXT NOT NULL PRIMARY KEY,
                        taskId TEXT NOT NULL,
                        sourceUrlRedacted TEXT NOT NULL,
                        fileName TEXT NOT NULL,
                        fileSize INTEGER NOT NULL,
                        mediaType TEXT NOT NULL,
                        destinationPath TEXT,
                        status TEXT NOT NULL,
                        bytesReceived INTEGER NOT NULL,
                        resumed INTEGER NOT NULL,
                        errorCategory TEXT,
                        errorMessage TEXT,
                        checksumSha256 TEXT,
                        createdAt INTEGER NOT NULL,
                        completedAt INTEGER,
                        attemptCount INTEGER NOT NULL,
                        schemaVersion INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_download_manifest_taskId ON download_manifest (taskId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_download_manifest_status ON download_manifest (status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_download_manifest_createdAt ON download_manifest (createdAt)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }
    }
}
