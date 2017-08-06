package com.phlox.tvwebbrowser.model;

import com.phlox.asql.annotations.DBTable;
import com.phlox.asql.annotations.MarkMode;

/**
 * Created by PDT on 09.09.2016.
 */
@DBTable(name = "favorites", markMode = MarkMode.ALL_EXCEPT_IGNORED)
public class FavoriteItem {
    public long id;
    public String title;
    public String url;
    public long parent;

    public boolean isFolder() {
        return url == null;
    }
}
