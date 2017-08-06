package com.phlox.tvwebbrowser.activity.singleton.shortcuts;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import com.phlox.tvwebbrowser.activity.main.MainActivity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by PDT on 06.08.2017.
 */

public class ShortcutMgr {
    public static final String PREFS_SHORTCUTS = "shortcuts";
    private static ShortcutMgr instance;
    private final Context ctx;
    private Map<Integer, Shortcut> shortcuts;

    public static ShortcutMgr getInstance(Context context) {
        if (instance == null) {
            instance = new ShortcutMgr(context.getApplicationContext());
        }
        return instance;
    }

    private ShortcutMgr(Context applicationContext) {
        this.ctx = applicationContext;
        shortcuts = new HashMap<>();
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_SHORTCUTS, Context.MODE_PRIVATE);
        for (Shortcut shortcut: Shortcut.values()) {
            shortcut.keyCode = prefs.getInt(shortcut.prefsKey, shortcut.keyCode);
            if (shortcut.keyCode != 0) {
                shortcuts.put(shortcut.keyCode, shortcut);
            }
        }
    }

    public void save(Shortcut shortcut) {
        int oldKey = 0;
        for (Map.Entry<Integer, Shortcut> entry: shortcuts.entrySet()) {
            if (entry.getValue().equals(shortcut)) {
                oldKey = entry.getKey();
            }
        }
        if (oldKey != 0) {
            shortcuts.remove(oldKey);
        }
        if (shortcut.keyCode != 0) {
            shortcuts.put(shortcut.keyCode, shortcut);
        }
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_SHORTCUTS, Context.MODE_PRIVATE);
        prefs.edit()
                .putInt(shortcut.prefsKey, shortcut.keyCode)
                .apply();
    }

    public Shortcut findForMenu(int menuId) {
        for (Map.Entry<Integer, Shortcut> entry: shortcuts.entrySet()) {
            if (entry.getValue().menuItemId == menuId) {
                return entry.getValue();
            }
        }
        return Shortcut.findForMenu(menuId);
    }

    public boolean process(int keyCode, MainActivity mainActivity) {
        Shortcut shortcut = shortcuts.get(keyCode);
        if (shortcut == null) {
            return false;
        }
        switch (shortcut) {
            case MENU:
                mainActivity.toggleMenu();
                return true;
            case NAVIGATE_BACK:
                mainActivity.navigateBack();
                return true;
            case NAVIGATE_HOME:
                mainActivity.navigate(MainActivity.HOME_URL);
                return true;
            case REFRESH_PAGE:
                mainActivity.refresh();
                return true;
            case VOICE_SEARCH:
                mainActivity.initiateVoiceSearch();
                return true;
        }
        return false;
    }

    public boolean canProcessKeyCode(int keyCode) {
        return shortcuts.get(keyCode) != null;
    }
}
