package com.phlox.tvwebbrowser.activity.history;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.TranslateAnimation;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SearchView;
import android.widget.Toast;

import com.phlox.asql.ASQL;
import com.phlox.tvwebbrowser.R;
import com.phlox.tvwebbrowser.model.FavoriteItem;
import com.phlox.tvwebbrowser.model.HistoryItem;
import com.phlox.tvwebbrowser.utils.BaseAnimationListener;
import com.phlox.tvwebbrowser.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by fedex on 29.12.16.
 */

public class HistoryActivity extends ListActivity implements AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {
    private static final int VOICE_SEARCH_REQUEST_CODE = 10001;

    private ImageButton ibDelete;

    public static final String KEY_URL = "url";
    private HistoryAdapter adapter;
    private ASQL asql;
    private boolean loading = false;
    private String searchQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        ibDelete = findViewById(R.id.ibDelete);

        adapter = new HistoryAdapter();
        setListAdapter(adapter);
        asql = ASQL.getDefault(this);

        getListView().setOnScrollListener(onListScrollListener);
        getListView().setOnItemClickListener(this);
        getListView().setOnItemLongClickListener(this);

        ibDelete.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean focused) {
                if (focused) {
                    showDeleteDialog();
                }
            }
        });

        loadItems(false);
    }

    private void showDeleteDialog() {
        final List<HistoryItem> selectedItems = adapter.getSelectedItems();
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete)
                .setMessage(R.string.msg_delete_history)
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        asql.delete(selectedItems, new ASQL.ResultCallback<Integer>() {
                            @Override
                            public void onDone(Integer result, Exception exception) {
                                if (exception != null) {
                                    Utils.showToast(HistoryActivity.this, R.string.error);
                                } else {
                                    adapter.remove(selectedItems);
                                }
                            }
                        });
                    }
                })
                .setNeutralButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) { }
                })
                .show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_SEARCH:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    //nop
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    initiateVoiceSearch();
                }
                return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case VOICE_SEARCH_REQUEST_CODE: {
                if (resultCode == RESULT_OK) {
                    // Populate the wordsList with the String values the recognition engine thought it heard
                    ArrayList<String> matches = data.getStringArrayListExtra(
                            RecognizerIntent.EXTRA_RESULTS);
                    if (matches == null || matches.isEmpty()) {
                        Utils.showToast(this, getString(R.string.can_not_recognize));
                        return;
                    }
                    searchQuery = matches.get(0);
                    loadItems(true);
                }
                break;
            }

            default:
                super.onActivityResult(requestCode, resultCode, data);
        }

    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        HistoryItem hi = ((HistoryItemView) view).historyItem;
        if (hi.isDateHeader) return;
        if (adapter.isMultiselectMode()) {
            ((HistoryItemView) view).setSelection(!hi.selected);
            updateMenu();
        } else {
            Intent resultIntent = new Intent();
            resultIntent.putExtra(KEY_URL, hi.url);
            setResult(RESULT_OK, resultIntent);
            finish();
        }
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> adapterView, View view, int i, long l) {
        if (adapter.isMultiselectMode()) return false;
        adapter.setMultiselectMode(true);
        HistoryItemView v = (HistoryItemView) view;
        v.setSelection(true);
        updateMenu();
        return true;
    }

    private void updateMenu() {
        List<HistoryItem> selection = adapter.getSelectedItems();
        if (selection.isEmpty()) {
            if (ibDelete.getVisibility() == View.GONE) return;
            Animation anim = AnimationUtils.loadAnimation(this, R.anim.right_menu_out_anim);
            anim.setAnimationListener(new BaseAnimationListener() {
                @Override
                public void onAnimationEnd(Animation animation) {
                    ibDelete.setVisibility(View.GONE);
                }
            });
            ibDelete.startAnimation(anim);
        } else {
            if (ibDelete.getVisibility() == View.VISIBLE) return;
            ibDelete.setVisibility(View.VISIBLE);
            ibDelete.startAnimation(AnimationUtils.loadAnimation(this, R.anim.right_menu_in_anim));
        }
    }

    @Override
    public void onBackPressed() {
        if (adapter.isMultiselectMode()) {
            adapter.setMultiselectMode(false);
            updateMenu();
            return;
        }
        super.onBackPressed();
    }

    private void showItemOptionsPopup(final HistoryItemView v) {
        PopupMenu pm = new PopupMenu(this, v, Gravity.BOTTOM);
        pm.getMenu().add(R.string.delete);
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                asql.delete(v.historyItem, new ASQL.ResultCallback<Integer>() {
                    @Override
                    public void onDone(Integer result, Exception exception) {
                        if (exception != null) {
                            Utils.showToast(HistoryActivity.this, R.string.error);
                        } else {
                            adapter.remove(v.historyItem);
                        }
                    }
                });
                return true;
            }
        });
        pm.show();
    }

    private void initiateVoiceSearch() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speak));
        startActivityForResult(intent, VOICE_SEARCH_REQUEST_CODE);
    }

    private void loadItems(boolean eraseOldResults) {
        if (loading) {
            return;
        }
        loading = true;
        if (eraseOldResults) {
            adapter.erase();
        }
        if ("".equals(searchQuery)) {
            asql.queryAll(HistoryItem.class, "SELECT * FROM history ORDER BY time DESC LIMIT 100 OFFSET ?",
                    sqlCallback, Long.toString(adapter.getRealCount()));
        } else {
            String search = "%" + searchQuery + "%";
            asql.queryAll(HistoryItem.class, "SELECT * FROM history WHERE (title LIKE ?) OR (url LIKE ?) ORDER BY time DESC LIMIT 100",
                    sqlCallback, search, search);
        }
    }

    private ASQL.ResultCallback<List<HistoryItem>> sqlCallback = new ASQL.ResultCallback<List<HistoryItem>>() {
        @Override
        public void onDone(List<HistoryItem> result, Exception error) {
            loading = false;
            if (result != null) {
                adapter.addItems(result);
                getListView().requestFocus();
            } else {
                Utils.showToast(HistoryActivity.this, R.string.error);
            }
        }
    };

    AbsListView.OnScrollListener onListScrollListener = new AbsListView.OnScrollListener() {
        @Override
        public void onScrollStateChanged(AbsListView view, int scrollState) {

        }

        @Override
        public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
            if(totalItemCount != 0 && firstVisibleItem+visibleItemCount >= totalItemCount - 1 && "".equals(searchQuery)) {
                loadItems(false);
            }
        }
    };
}
