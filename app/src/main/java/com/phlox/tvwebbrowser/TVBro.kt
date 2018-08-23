package com.phlox.tvwebbrowser

import android.app.Application
import android.database.sqlite.SQLiteDatabase

import com.phlox.asql.ASQL

import java.net.CookieHandler
import java.net.CookieManager

/**
 * Created by PDT on 09.09.2016.
 */
class TVBro : Application() {
    override fun onCreate() {
        super.onCreate()
        val cookieManager = CookieManager()
        CookieHandler.setDefault(cookieManager)

        ASQL.initDefaultInstance("main.db", 5, object : ASQL.BaseCallback() {
            override fun onCreate(asql: ASQL?, db: SQLiteDatabase?) {
                createFavoritesTable(db!!)
                createHistoryTable(db)
                createDownloadsTable(db)
            }

            override fun onUpgrade(asql: ASQL?, db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
                var oldVersion = oldVersion
                while (oldVersion < newVersion) {
                    when (oldVersion) {
                        1//1 to 2 version migration
                        -> createHistoryTable(db!!)
                        4//4 to 5 version migration
                        -> createDownloadsTable(db!!)
                    }
                    oldVersion++
                }
            }

            private fun createFavoritesTable(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE favorites ("
                        + "ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                        + "TITLE TEXT,"
                        + "URL TEXT,"
                        + "PARENT INTEGER"
                        + ");")
            }

            private fun createHistoryTable(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE history ("
                        + "id INTEGER PRIMARY KEY NOT NULL,"
                        + "time LONG NOT NULL,"
                        + "title TEXT NOT NULL,"
                        + "url TEXT NOT NULL"
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
}
