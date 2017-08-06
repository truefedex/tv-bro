package com.phlox.tvwebbrowser.activity.main.dialogs;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;

import com.phlox.tvwebbrowser.R;
import com.phlox.tvwebbrowser.activity.main.MainActivity;

import java.util.Arrays;
import java.util.List;

/**
 * Created by fedex on 18.01.17.
 */

public final class SearchEngineConfigDialogFactory {
    private SearchEngineConfigDialogFactory() {
    }

    public interface Callback {
        void onDone(String url);
    }

    public static void show(final Context context, String selectedUrl, final SharedPreferences prefs, boolean cancellable, final Callback callback) {
        final String[] enginesTitles = {"Google", "Bing", "Yahoo!", "DuckDuckGo", "Yandex", "Custom"};
        final List<String> enginesURLs =  Arrays.asList("https://www.google.com/search?q=[query]", "https://www.bing.com/search?q=[query]",
                        "https://search.yahoo.com/search?p=[query]", "https://duckduckgo.com/?q=[query]",
                        "https://yandex.com/search/?text=[query]", "" );

        int selected = 0;
        if (!"".equals(selectedUrl)) {
            selected = enginesURLs.indexOf(selectedUrl);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_search_engine, null);
        final EditText etUrl = (EditText) view.findViewById(R.id.etUrl);
        final LinearLayout llUrl = (LinearLayout) view.findViewById(R.id.llURL);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, enginesTitles);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        Spinner spEngine = (Spinner) view.findViewById(R.id.spEngine);
        spEngine.setAdapter(adapter);

        if (selected != -1) {
            spEngine.setSelection(selected);
            etUrl.setText(enginesURLs.get(selected));
        } else {
            spEngine.setSelection(enginesTitles.length - 1);
            llUrl.setVisibility(View.VISIBLE);
            etUrl.setText(selectedUrl);
            etUrl.requestFocus();
        }
        spEngine.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == enginesTitles.length - 1 && llUrl.getVisibility() == View.GONE) {
                    llUrl.setVisibility(View.VISIBLE);
                    llUrl.startAnimation(AnimationUtils.loadAnimation(context, android.R.anim.fade_in));
                    etUrl.requestFocus();
                }
                etUrl.setText(enginesURLs.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        builder.setView(view)
                .setCancelable(cancellable)
                .setTitle(R.string.engine)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String url = etUrl.getText().toString();
                        SharedPreferences.Editor editor = prefs.edit();
                        editor.putString(MainActivity.SEARCH_ENGINE_URL_PREF_KEY, url);
                        editor.apply();
                        callback.onDone(url);
                    }
                })
                .show();
    }
}
