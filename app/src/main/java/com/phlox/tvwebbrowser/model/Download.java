package com.phlox.tvwebbrowser.model;

import com.phlox.asql.annotations.DBColumn;
import com.phlox.asql.annotations.DBIgnore;
import com.phlox.asql.annotations.DBTable;
import com.phlox.asql.annotations.MarkMode;

/**
 * Created by PDT on 23.01.2017.
 */

@DBTable(name = "downloads", markMode = MarkMode.ALL_EXCEPT_IGNORED)
public class Download {
    public static final long BROKEN_MARK = -2;
    public static final long CANCELLED_MARK = -3;
    @DBColumn(primaryKey = true)
    public long id;
    public long time;
    public String filename;
    public String filepath;
    public String url;
    public volatile long size;
    public volatile long bytesReceived;

    //non-db fields
    @DBIgnore
    public volatile boolean cancelled;
    @DBIgnore
    public boolean isDateHeader = false;//user for displaying date headers inside list view

    public static Download createDateHeaderInfo(long time) {
        Download download = new Download();
        download.time = time;
        download.isDateHeader = true;
        return download;
    }
}
