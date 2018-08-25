package com.phlox.tvwebbrowser.activity.main.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.support.annotation.NonNull;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.phlox.tvwebbrowser.R;
import com.phlox.tvwebbrowser.singleton.shortcuts.Shortcut;
import com.phlox.tvwebbrowser.singleton.shortcuts.ShortcutMgr;

/**
 * Created by PDT on 06.08.2017.
 */

public class ShortcutDialog extends Dialog {
    private final TextView tvActionTitle;
    private final TextView tvActionKey;
    private final Button btnSetKey;
    private final Button btnClearKey;
    private Shortcut shortcut;
    private boolean keyListenMode = false;

    public ShortcutDialog(@NonNull Context context, Shortcut shortcut) {
        super(context);
        this.shortcut = shortcut;
        setCancelable(true);
        setContentView(R.layout.dialog_shortcut);
        setTitle(R.string.shortcut);

        tvActionTitle = findViewById(R.id.tvActionTitle);
        tvActionKey = findViewById(R.id.tvActionKey);
        btnSetKey = findViewById(R.id.btnSetKey);
        btnClearKey = findViewById(R.id.btnClearKey);

        tvActionTitle.setText(shortcut.getTitleResId());
        updateShortcutNameDisplay();
        btnSetKey.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleKeyListenState();
            }
        });

        btnClearKey.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                clearKey();
            }
        });
    }

    private void clearKey() {
        if (keyListenMode) {
            toggleKeyListenState();
        }
        shortcut.setKeyCode(0);
        ShortcutMgr.Companion.getInstance(getContext()).save(shortcut);
        updateShortcutNameDisplay();
    }

    private void updateShortcutNameDisplay() {
        tvActionKey.setText(shortcut.getKeyCode() == 0 ?
                getContext().getString(R.string.not_set) : KeyEvent.keyCodeToString(shortcut.getKeyCode()));
    }

    private void toggleKeyListenState() {
        keyListenMode = !keyListenMode;
        btnSetKey.setText(keyListenMode ? R.string.press_eny_key :  R.string.set_key_for_action);
    }

    @Override
    public boolean onKeyUp(int keyCode, @NonNull KeyEvent event) {
        if (!keyListenMode) {
            return super.onKeyUp(keyCode, event);
        }
        shortcut.setKeyCode(keyCode != 0 ? keyCode : event.getScanCode());
        ShortcutMgr.Companion.getInstance(getContext()).save(shortcut);
        toggleKeyListenState();
        updateShortcutNameDisplay();
        return true;
    }
}
