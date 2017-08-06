package com.phlox.tvwebbrowser.activity.main.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import com.phlox.tvwebbrowser.R;
import com.phlox.tvwebbrowser.model.FavoriteItem;

/**
 * Created by PDT on 13.09.2016.
 */
public class FavoriteEditorDialog extends Dialog {
    private final FavoriteItem item;
    private final TextView tvTitle;
    private final TextView tvUrl;
    private final EditText etTitle;
    private final EditText etUrl;
    private final ImageButton ibTitle;
    private final ImageButton ibUrl;
    private final Button btnDone;
    private final Button btnCancel;
    private Callback callback;

    public interface Callback {
        void onDone(FavoriteItem item);
    }

    public FavoriteEditorDialog(Context context, final Callback callback, FavoriteItem item) {
        super(context);
        this.callback = callback;
        this.item = item;
        setCancelable(true);
        setTitle(item.id == 0 ? R.string.new_bookmark : R.string.edit);
        setContentView(R.layout.dialog_new_favorite_item);
        tvTitle = (TextView) findViewById(R.id.tvTitle);
        tvUrl = (TextView) findViewById(R.id.tvUrl);
        etTitle = (EditText) findViewById(R.id.etTitle);
        etUrl = (EditText) findViewById(R.id.etUrl);
        ibTitle = (ImageButton) findViewById(R.id.ibTitle);
        ibUrl = (ImageButton) findViewById(R.id.ibUrl);
        btnDone = (Button) findViewById(R.id.btnDone);
        btnCancel = (Button) findViewById(R.id.btnCancel);

        ibTitle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ibTitle.setVisibility(View.GONE);
                tvTitle.setVisibility(View.GONE);
                etTitle.setVisibility(View.VISIBLE);
                etTitle.requestFocus();
            }
        });

        ibUrl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ibUrl.setVisibility(View.GONE);
                tvUrl.setVisibility(View.GONE);
                etUrl.setVisibility(View.VISIBLE);
                etUrl.requestFocus();
            }
        });

        btnDone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FavoriteEditorDialog.this.item.title = etTitle.getText().toString();
                FavoriteEditorDialog.this.item.url = etUrl.getText().toString();
                callback.onDone(FavoriteEditorDialog.this.item);
                dismiss();
            }
        });
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dismiss();
            }
        });

        tvTitle.setText(item.title);
        etTitle.setText(item.title);
        tvUrl.setText(item.url);
        etUrl.setText(item.url);
    }
}
