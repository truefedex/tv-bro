package com.phlox.tvwebbrowser.singleton

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.model.*
import com.phlox.tvwebbrowser.model.dao.*
import com.phlox.tvwebbrowser.model.util.Converters

@Database(entities = [
    Download::class, FavoriteItem::class,
    HistoryItem::class, WebTabState::class,
    HostConfig::class
                     ],
    version = 19/*, exportSchema = true*/)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun historyDao(): HistoryDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun tabsDao(): TabsDao
    abstract fun hostsDao(): HostsDao

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

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS 'tabs' ('id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'url' TEXT NOT NULL, 'title' TEXT NOT NULL, 'selected' INTEGER NOT NULL, 'thumbnailHash' TEXT, 'faviconHash' TEXT, 'incognito' INTEGER NOT NULL, 'position' INTEGER NOT NULL, 'wv_state' BLOB)")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createAdBlockListTable(db)
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tabs\n" +
                        "ADD adblock INTEGER;")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS 'adblocklist';")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tabs\n" +
                        "ADD wv_state_file TEXT;")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tabs\n" +
                        "ADD scale REAL;")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history\n" +
                        "ADD incognito INTEGER;")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM history WHERE incognito")
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createHostsTable(db)
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorites\n" +
                        "ADD HOME_PAGE_BOOKMARK INTEGER NOT NULL DEFAULT 0;")
                db.execSQL("ALTER TABLE favorites\n" +
                        "ADD I_ORDER INTEGER NOT NULL DEFAULT 0;")
                db.execSQL("ALTER TABLE favorites ADD DEST_URL TEXT;")
                db.execSQL("ALTER TABLE favorites ADD `DESCRIPTION` TEXT;")
                db.execSQL("ALTER TABLE favorites ADD VALID_UNTIL INTEGER;")
                db.execSQL("CREATE INDEX favorites_home_page_bookmark_idx ON favorites(HOME_PAGE_BOOKMARK);")
                db.execSQL("CREATE INDEX favorites_parent_idx ON favorites(PARENT);")

                db.execSQL("ALTER TABLE `hosts` ADD `favicon` TEXT;")
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorites\n" +
                        "ADD USEFUL INTEGER NOT NULL DEFAULT 0;")
            }
        }

        private fun createHostsTable(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `hosts` (`host_name` TEXT NOT NULL, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `popup_block_level` INTEGER)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `hosts_name_idx` ON `hosts` (`host_name`)")
        }

        private fun createAdBlockListTable(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS 'adblocklist' ('id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 'type' INTEGER NOT NULL, 'value' TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS 'type_value_idx' ON 'adblocklist' ('type', 'value')")
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

        val db: AppDatabase by lazy {
            Room.databaseBuilder(
                TVBro.instance,
                AppDatabase::class.java, "main.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_8, MIGRATION_8_9,
                MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19)
            .allowMainThreadQueries()
                .build()
        }
    }
}