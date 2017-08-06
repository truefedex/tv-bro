package com.phlox.tvwebbrowser;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;

import com.phlox.asql.ASQL;

import java.net.CookieHandler;
import java.net.CookieManager;

/**
 * Created by PDT on 09.09.2016.
 */
public class TVBro extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CookieManager cookieManager = new CookieManager();
        CookieHandler.setDefault(cookieManager);

        ASQL.initDefaultInstance("main.db", 5, new ASQL.BaseCallback(){
            @Override
            public void onCreate(ASQL asql, SQLiteDatabase db) {
                createFavoritesTable(db);
                createHistoryTable(db);
                createDownloadsTable(db);
            }

            @Override
            public void onUpgrade(ASQL asql, SQLiteDatabase db, int oldVersion, int newVersion) {
                while (oldVersion < newVersion) {
                    switch (oldVersion) {
                        case 1://1 to 2 version migration
                            createHistoryTable(db);
                            break;
                        case 4://4 to 5 version migration
                            createDownloadsTable(db);
                            break;
                    }
                    oldVersion++;
                }
            }

            private void createFavoritesTable(SQLiteDatabase db) {
                db.execSQL("CREATE TABLE favorites ("
                        + "ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                        + "TITLE TEXT,"
                        + "URL TEXT,"
                        + "PARENT INTEGER"
                        + ");");
            }

            private void createHistoryTable(SQLiteDatabase db) {
                db.execSQL("CREATE TABLE history ("
                        + "id INTEGER PRIMARY KEY NOT NULL,"
                        + "time LONG NOT NULL,"
                        + "title TEXT NOT NULL,"
                        + "url TEXT NOT NULL"
                        + ");");
                db.execSQL("CREATE INDEX history_time_idx ON history(time);");
                db.execSQL("CREATE INDEX history_title_idx ON history(title);");
                db.execSQL("CREATE INDEX history_url_idx ON history(url);");
            }

            private void createDownloadsTable(SQLiteDatabase db) {
                db.execSQL("CREATE TABLE downloads ("
                        + "id INTEGER PRIMARY KEY NOT NULL,"
                        + "time LONG NOT NULL,"
                        + "filename TEXT NOT NULL,"
                        + "filepath TEXT NOT NULL,"
                        + "url TEXT NOT NULL,"
                        + "size LONG NOT NULL,"
                        + "bytes_received LONG NOT NULL"
                        + ");");
                db.execSQL("CREATE INDEX downloads_time_idx ON downloads(time);");
                db.execSQL("CREATE INDEX downloads_filename_idx ON downloads(filename);");
            }

        });
    }
}
