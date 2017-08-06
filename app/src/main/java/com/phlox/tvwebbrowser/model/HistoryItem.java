package com.phlox.tvwebbrowser.model;

import com.phlox.asql.annotations.DBIgnore;
import com.phlox.asql.annotations.DBTable;
import com.phlox.asql.annotations.MarkMode;

import java.util.Calendar;
import java.util.GregorianCalendar;

/**
 * Created by fedex on 28.12.16.
 */

@DBTable(name = "history", markMode = MarkMode.ALL_EXCEPT_IGNORED)
public class HistoryItem {
    public long id;
    public long time;
    public String title;
    public String url;

    @DBIgnore
    public boolean isDateHeader = false;//used for displaying date headers inside list view
    @DBIgnore
    public boolean selected = false;

    public HistoryItem() {
    }

    public static HistoryItem createDateHeaderInfo(long time) {
        HistoryItem hi = new HistoryItem();
        hi.time = time;
        hi.isDateHeader = true;
        return hi;
    }
}
