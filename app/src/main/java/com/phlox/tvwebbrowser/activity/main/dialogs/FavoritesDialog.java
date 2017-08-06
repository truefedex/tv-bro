package com.phlox.tvwebbrowser.activity.main.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.database.SQLException;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.phlox.asql.ASQL;
import com.phlox.tvwebbrowser.R;
import com.phlox.tvwebbrowser.activity.main.adapter.FavoritesListAdapter;
import com.phlox.tvwebbrowser.activity.main.view.FavoriteItemView;
import com.phlox.tvwebbrowser.model.FavoriteItem;
import com.phlox.tvwebbrowser.utils.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by PDT on 09.09.2016.
 */
public class FavoritesDialog extends Dialog implements FavoriteItemView.Listener {
    private final String currentPageTitle;
    private final String currentPageUrl;
    private List<FavoriteItem> items = new ArrayList<>();
    private FavoritesListAdapter adapter;
    private Callback callback;
    private ASQL asql;

    private final TextView tvPlaceholder;
    private final ListView listView;
    private final Button btnAdd;
    private final Button btnEdit;
    private final ProgressBar pbLoading;

    public interface Callback {
        void onFavoriteChoosen(FavoriteItem item);
    }

    public FavoritesDialog(Context context, final Callback callback, String currentPageTitle, String currentPageUrl) {
        super(context);
        this.callback = callback;
        this.currentPageTitle = currentPageTitle;
        this.currentPageUrl = currentPageUrl;
        setCancelable(true);
        setContentView(R.layout.dialog_favorites);
        setTitle(R.string.bookmarks);

        tvPlaceholder = (TextView)findViewById(R.id.tvPlaceholder);
        listView = (ListView)findViewById(R.id.listView);
        btnAdd = (Button)findViewById(R.id.btnAdd);
        btnEdit = (Button)findViewById(R.id.btnEdit);
        pbLoading = (ProgressBar)findViewById(R.id.pbLoading);

        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showAddItemDialog();
            }
        });

        btnEdit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                adapter.setEditMode(!adapter.isEditMode());
            }
        });

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                FavoriteItem item = ((FavoriteItemView) view).getFavorite();
                if (item.isFolder()) {

                } else {
                    callback.onFavoriteChoosen(item);
                    dismiss();
                }
            }
        });

        pbLoading.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);
        tvPlaceholder.setVisibility(View.GONE);
        adapter = new FavoritesListAdapter(items, this);
        listView.setAdapter(adapter);

        asql = ASQL.getDefault(getContext());

        asql.queryAll(FavoriteItem.class, "SELECT * FROM favorites WHERE parent=0 ORDER BY id DESC", new ASQL.ResultCallback<List<FavoriteItem>>() {
            @Override
            public void onDone(List<FavoriteItem> result, Exception error) {
                pbLoading.setVisibility(View.GONE);
                if (result != null) {
                    items.addAll(result);
                    onItemsChanged();
                } else {
                    items = new ArrayList<>();
                    Utils.showToast(getContext(), R.string.error);
                    dismiss();
                }
            }
        });

    }

    private void showAddItemDialog() {
        FavoriteItem newItem = new FavoriteItem();
        newItem.title = currentPageTitle;
        newItem.url = currentPageUrl;
        new FavoriteEditorDialog(getContext(), new FavoriteEditorDialog.Callback() {
            @Override
            public void onDone(FavoriteItem item) {
                onItemEdited(item);
            }
        }, newItem).show();
    }

    private void onItemEdited(final FavoriteItem item) {
        pbLoading.setVisibility(View.VISIBLE);
        listView.setVisibility(View.GONE);
        tvPlaceholder.setVisibility(View.GONE);
        if (item.id == 0) {
            asql.execInsert("INSERT INTO favorites (title, url, parent) VALUES (:title, :url, :parent)", item, new ASQL.InsertResultCallback() {
                @Override
                public void onDone(long lastInsertRowId, SQLException exception) {
                    if (exception != null) {
                        Utils.showToast(getContext(), R.string.error);
                    } else {
                        item.id = lastInsertRowId;
                        items.add(0, item);
                        onItemsChanged();
                    }
                }
            });
        } else {
            asql.execUpdateDelete("UPDATE favorites SET title=:title, url=:url, parent=:parent WHERE id=:id", item, new ASQL.AffectedRowsResultCallback() {
                @Override
                public void onDone(int affectedRowsCount, SQLException exception) {
                    if (exception != null || affectedRowsCount == 0) {
                        Utils.showToast(getContext(), R.string.error);
                    } else {
                        onItemsChanged();
                    }
                }
            });
        }

    }

    private void onItemsChanged() {
        adapter.notifyDataSetChanged();
        pbLoading.setVisibility(View.GONE);
        listView.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        tvPlaceholder.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onDeleteClick(final FavoriteItem favorite) {
        asql.execUpdateDelete("DELETE FROM favorites WHERE id=:id", favorite, new ASQL.AffectedRowsResultCallback() {
            @Override
            public void onDone(int affectedRowsCount, SQLException exception) {
                if (exception != null || affectedRowsCount == 0) {
                    Utils.showToast(getContext(), R.string.error);
                } else {
                    items.remove(favorite);
                    onItemsChanged();
                }
            }
        });
    }

    @Override
    public void onEditClick(FavoriteItem favorite) {
        new FavoriteEditorDialog(getContext(), new FavoriteEditorDialog.Callback() {
            @Override
            public void onDone(FavoriteItem item) {
                onItemEdited(item);
            }
        }, favorite).show();
    }
}
