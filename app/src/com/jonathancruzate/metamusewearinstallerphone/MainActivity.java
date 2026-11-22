package com.jonathancruzate.metamusewearinstallerphone;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Setup + status screen. This is where the Worker base URL and
 * secret are entered — they live only in this app's private
 * preferences, are never hardcoded, never logged, and the secret
 * field is a password field. Below the setup: the lane switch, a
 * battery-optimization shortcut, the last report / last job mirror,
 * and the on-device log tail.
 */
public final class MainActivity extends Activity {
    private EditText baseField;
    private EditText secretField;
    private TextView statusView;
    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Muse Wear Installer Phone");
        title.setTextSize(20);
        root.addView(title);

        TextView intro = new TextView(this);
        intro.setText("The phone half of the Muse Wear Installer lane: polls the "
                + "Muse Worker as device \"wear-installer\", accepts only "
                + "wear_install_face, verifies the face APK's SHA-256, relays it "
                + "to Muse Wear Installer on the watch, and reports the verdict.");
        root.addView(intro);

        baseField = new EditText(this);
        baseField.setHint("Worker base URL (e.g. https://jonathancruzate.com/mode-sync)");
        baseField.setSingleLine(true);
        baseField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        baseField.setText(Store.get(this, Store.K_BASE_URL, ""));
        root.addView(baseField);

        secretField = new EditText(this);
        secretField.setHint("Worker secret");
        secretField.setSingleLine(true);
        secretField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secretField.setText(Store.get(this, Store.K_SECRET, ""));
        root.addView(secretField);

        Button save = new Button(this);
        save.setText("Save & start lane");
        save.setOnClickListener(v -> saveAndStart());
        root.addView(save);

        Button stop = new Button(this);
        stop.setText("Stop lane");
        stop.setOnClickListener(v -> {
            Store.put(this, Store.K_SERVICE_ON, false);
            CommandService.stop(this);
            AppLog.log(this, "UI", "lane stopped from UI");
            refresh();
        });
        root.addView(stop);

        Button battery = new Button(this);
        battery.setText("Exclude from battery optimization");
        battery.setOnClickListener(v -> {
            try {
                Intent i = new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                AppLog.log(this, "UI", "battery-optimization intent failed: "
                        + e.getClass().getSimpleName());
            }
        });
        root.addView(battery);

        Button refreshBtn = new Button(this);
        refreshBtn.setText("Refresh status");
        refreshBtn.setOnClickListener(v -> refresh());
        root.addView(refreshBtn);

        statusView = new TextView(this);
        root.addView(statusView);

        logView = new TextView(this);
        logView.setTextSize(11);
        root.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void saveAndStart() {
        String base = baseField.getText().toString().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String secret = secretField.getText().toString().trim();
        if (base.isEmpty() || secret.isEmpty()) {
            AppLog.log(this, "UI", "save refused: base or secret empty");
            refresh();
            return;
        }
        Store.put(this, Store.K_BASE_URL, base);
        Store.put(this, Store.K_SECRET, secret);
        Store.put(this, Store.K_SERVICE_ON, true);
        AppLog.log(this, "UI", "worker configured (base set; secret stored, not logged)");
        CommandService.start(this);
        refresh();
    }

    private void refresh() {
        if (statusView == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("Configured: ").append(Store.workerConfigured(this) ? "yes" : "no").append("\n");
        sb.append("Lane switch: ")
                .append(Store.getBool(this, Store.K_SERVICE_ON, false) ? "on" : "off")
                .append("\n");
        long repAt = Store.getLong(this, Store.K_LAST_REPORT_AT, 0);
        sb.append("Last report: ")
                .append(repAt > 0
                        ? fmt(repAt) + " (HTTP "
                                + Store.getLong(this, Store.K_LAST_REPORT_CODE, 0) + ")"
                        : "never")
                .append("\n");
        long cmdAt = Store.getLong(this, Store.K_LAST_CMD_AT, 0);
        if (cmdAt > 0) {
            sb.append("Last command: ")
                    .append(Store.get(this, Store.K_LAST_CMD_TEMPLATE, ""))
                    .append(" ok=").append(Store.getBool(this, Store.K_LAST_CMD_OK, false))
                    .append(" at ").append(fmt(cmdAt)).append("\n");
        }
        long wearAt = Store.getLong(this, Store.K_WEAR_AT, 0);
        if (wearAt > 0) {
            sb.append("Last wear job: ")
                    .append(Store.get(this, Store.K_WEAR_LABEL, ""))
                    .append(" — ").append(Store.get(this, Store.K_WEAR_STAGE, ""))
                    .append(" (").append(Store.get(this, Store.K_WEAR_DETAIL, ""))
                    .append(") at ").append(fmt(wearAt)).append("\n");
        }
        statusView.setText(sb.toString());

        List<String> tail = AppLog.tail(this, 30);
        StringBuilder lb = new StringBuilder("— log —\n");
        for (String line : tail) lb.append(line).append("\n");
        logView.setText(lb.toString());
    }

    private static String fmt(long epochMs) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date(epochMs));
    }
}
