package com.phlox.tvwebbrowser.singleton

import android.database.sqlite.SQLiteDatabase
import com.phlox.asql.ASQL

fun initASQL() {
    ASQL.initDefaultInstance("main.db", 6, object : ASQL.BaseCallback() {
        override fun onCreate(asql: ASQL?, db: SQLiteDatabase?) {
            createFavoritesTable(db!!)
            createHistoryTable(db)
            createDownloadsTable(db)
        }

        override fun onUpgrade(asql: ASQL?, db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            var version = oldVersion
            while (version < newVersion) {
                when (version) {
                    1//1 to 2 version migration
                    -> createHistoryTable(db)
                    4//4 to 5 version migration
                    -> createDownloadsTable(db)
                    5 -> {//5 to 6 version migration
                        db.execSQL("ALTER TABLE history\n" +
                                "ADD favicon TEXT;")
                        db.execSQL("ALTER TABLE favorites\n" +
                                "ADD favicon TEXT;")
                    }
                }
                version++
            }
        }

        private fun createFavoritesTable(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE favorites ("
                    + "ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                    + "TITLE TEXT,"
                    + "URL TEXT,"
                    + "PARENT INTEGER,"
                    + "favicon TEXT"
                    + ");")
        }

        private fun createHistoryTable(db: SQLiteDatabase) {
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

        private fun createDownloadsTable(db: SQLiteDatabase) {
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

    })
}