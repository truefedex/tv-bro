package com.phlox.tvwebbrowser.singleton

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.Download
import com.phlox.tvwebbrowser.model.FavoriteItem
import com.phlox.tvwebbrowser.model.HistoryItem
import com.phlox.tvwebbrowser.model.dao.DownloadDao
import com.phlox.tvwebbrowser.model.dao.FavoritesDao
import com.phlox.tvwebbrowser.model.dao.HistoryDao
import com.phlox.tvwebbrowser.model.dao.TabsDao

@Database(entities = arrayOf(Download::class, FavoriteItem::class, HistoryItem::class), version = 9)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun historyDao(): HistoryDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun tabsDao(): TabsDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createHistoryTable(db)
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createDownloadsTable(db)
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history\n" +
                        "ADD favicon TEXT;")
                db.execSQL("ALTER TABLE favorites\n" +
                        "ADD favicon TEXT;")
            }
        }
        private val MIGRATION_6_8 = object : Migration(6, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.beginTransactionNonExclusive()
                try {
                    db.execSQL("CREATE TEMPORARY TABLE downloads_backup(id INTEGER PRIMARY KEY NOT NULL, \"time\" INTEGER NOT NULL, filename TEXT NOT NULL, filepath TEXT NOT NULL, url TEXT NOT NULL, size INTEGER NOT NULL, bytes_received INTEGER NOT NULL);")
                    db.execSQL("INSERT INTO downloads_backup SELECT id ,\"time\", filename, filepath, url, size, bytes_received FROM downloads;")
                    db.execSQL("DROP TABLE downloads;")
                    db.execSQL("CREATE TABLE downloads(id INTEGER PRIMARY KEY NOT NULL, \"time\" INTEGER NOT NULL, filename TEXT NOT NULL, filepath TEXT NOT NULL, url TEXT NOT NULL, size INTEGER NOT NULL, bytes_received INTEGER NOT NULL);")
                    db.execSQL("INSERT INTO downloads SELECT id ,\"time\", filename, filepath, url, size, bytes_received FROM downloads_backup;")
                    db.execSQL("DROP TABLE downloads_backup;")
                    db.execSQL("CREATE INDEX downloads_time_idx ON downloads(time);")
                    db.execSQL("CREATE INDEX downloads_filename_idx ON downloads(filename);")
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                db.beginTransactionNonExclusive()
                try {
                    db.execSQL("CREATE TEMPORARY TABLE history_backup(id INTEGER PRIMARY KEY NOT NULL, \"time\" INTEGER NOT NULL, title TEXT NOT NULL, url TEXT NOT NULL, favicon TEXT);")
                    db.execSQL("INSERT INTO history_backup SELECT id ,\"time\", title, url, favicon FROM history;")
                    db.execSQL("DROP TABLE history;")
                    db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY NOT NULL, \"time\" INTEGER NOT NULL, title TEXT NOT NULL, url TEXT NOT NULL, favicon TEXT);")
                    db.execSQL("INSERT INTO history SELECT id ,\"time\", title, url, favicon FROM history_backup;")
                    db.execSQL("DROP TABLE history_backup;")
                    db.execSQL("CREATE INDEX history_time_idx ON history(\"time\");")
                    db.execSQL("CREATE INDEX history_title_idx ON history(title);")
                    db.execSQL("CREATE INDEX history_url_idx ON history(url);")
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        private fun createHistoryTable(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE history ("
                    + "id INTEGER PRIMARY KEY NOT NULL,"
                    + "time LONG NOT NULL,"
                    + "title TEXT NOT NULL,"
                    + "url TEXT NOT NULL,"
                    + "favicon TEXT"
                    + ");")
            db.execSQL("CREATE INDEX history_time_idx ON history(time);")
            db.execSQL("CREATE INDEX history_title_idx ON history(title);")
            db.execSQL("CREATE INDEX history_url_idx ON history(url);")
        }

        private fun createDownloadsTable(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE downloads ("
                    + "id INTEGER PRIMARY KEY NOT NULL,"
                    + "time LONG NOT NULL,"
                    + "filename TEXT NOT NULL,"
                    + "filepath TEXT NOT NULL,"
                    + "url TEXT NOT NULL,"
                    + "size LONG NOT NULL,"
                    + "bytes_received LONG NOT NULL"
                    + ");")
            db.execSQL("CREATE INDEX downloads_time_idx ON downloads(time);")
            db.execSQL("CREATE INDEX downloads_filename_idx ON downloads(filename);")
        }

        val db: AppDatabase by lazy { Room.databaseBuilder(
                TVBro.instance,
                AppDatabase::class.java, "main.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_8).build() }
    }
}