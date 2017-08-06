package com.phlox.tvwebbrowser.activity.main.dialogs;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import com.phlox.asql.ASQL;
import com.phlox.tvwebbrowser.R;
import com.phlox.tvwebbrowser.activity.main.view.WebViewEx;

import java.util.Arrays;
import java.util.List;

/**
 * Created by PDT on 22.01.2017.
 */

public class UserAgentConfigDialogFactory {
    private UserAgentConfigDialogFactory() {
    }

    public interface Callback {
        void onDone(String defaultUAString);
    }

    public static void show(final Context context, String selectedUAString, final Callback callback) {
        final String[] titles = {"TV Bro", "Chrome (Desktop)", "Chrome (Mobile)", "Chrome (Tablet)", "Firefox (Desktop)", "Firefox (Tablet)", "Edge (Desktop)", "Safari (Desktop)", "Safari (iPad)", "Apple TV", "Custom"};
        final List<String> uaStrings =  Arrays.asList("",
                "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/55.0.2883.87 Safari/537.36",
                "Mozilla/5.0 (Linux; Android 6.0.1; SM-G920V Build/MMB29K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/52.0.2743.98 Mobile Safari/537.36",
                "Mozilla/5.0 (Linux; Android 7.0; Pixel C Build/NRD90M; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/52.0.2743.98 Safari/537.36",
                "Mozilla/5.0 (Windows NT 10.0; WOW64; rv:50.0) Gecko/20100101 Firefox/50.0",
                "Mozilla/5.0 (Android 4.4; Tablet; rv:41.0) Gecko/41.0 Firefox/41.0",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/42.0.2311.135 Safari/537.36 Edge/12.246",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_11_2) AppleWebKit/601.3.9 (KHTML, like Gecko) Version/9.0.2 Safari/601.3.9",
                "Mozilla/5.0 (iPad; CPU OS 7_0 like Mac OS X) AppleWebKit/537.51.1 (KHTML, like Gecko) Version/7.0 Mobile/11A465 Safari/9537.53",
                "AppleTV5,3/9.1.1",
                "" );

        int selected = 0;
        if (!"".equals(selectedUAString)) {
            selected = uaStrings.indexOf(selectedUAString);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_user_agent_config, null);
        final EditText etUAString = (EditText) view.findViewById(R.id.etUAString);
        final LinearLayout llUAString = (LinearLayout) view.findViewById(R.id.llUAString);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, titles);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        Spinner spTitles = (Spinner) view.findViewById(R.id.spTitles);
        spTitles.setAdapter(adapter);

        if (selected != -1) {
            spTitles.setSelection(selected, false);
            etUAString.setText(uaStrings.get(selected));
        } else {
            spTitles.setSelection(titles.length - 1, false);
            llUAString.setVisibility(View.VISIBLE);
            etUAString.setText(selectedUAString);
            etUAString.requestFocus();
        }
        spTitles.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == titles.length - 1 && llUAString.getVisibility() == View.GONE) {
                    llUAString.setVisibility(View.VISIBLE);
                    llUAString.startAnimation(AnimationUtils.loadAnimation(context, android.R.anim.fade_in));
                    etUAString.requestFocus();
                }
                etUAString.setText(uaStrings.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        builder.setView(view)
                .setCancelable(true)
                .setTitle(R.string.user_agent_string)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final String userAgent = etUAString.getText().toString().trim();
                        if ("".equals(userAgent)) {
                            callback.onDone(WebViewEx.defaultUAString);
                        } else {
                            callback.onDone(userAgent);
                        }

                    }
                })
                .show();
    }
}
