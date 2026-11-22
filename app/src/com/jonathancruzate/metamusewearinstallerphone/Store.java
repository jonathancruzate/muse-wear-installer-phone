package com.jonathancruzate.metamusewearinstallerphone;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * All app state in one SharedPreferences file: worker settings
 * (entered on-device in the setup screen, NEVER hardcoded — no
 * secret ships in this APK and none is ever logged), the service
 * switch, and the last wear-install job's mirrored stage (the same
 * K_WEAR_* shape Device Assistance used, so the wearbridge report
 * object is unchanged).
 */
public final class Store {
    private static final String PREFS = "wearinstallerphone";

    // Settings
    public static final String K_BASE_URL = "base_url";   // e.g. https://jonathancruzate.com/mode-sync
    public static final String K_SECRET = "secret";
    public static final String K_SERVICE_ON = "service_on";

    // Transport migration (v1.0.2) — the Worker can hand the app a
    // new base URL + secret (the /bridge base + the wear lane
    // secret) inside a command-poll / report / result response
    // (WorkerClient.handleTransportDirective; decisions in
    // TransportDirective). The previous pair is kept here so the
    // probation fallback can revert if the new base proves
    // unreachable. Secrets live only in these prefs — never in the
    // APK, never in the log. Same key names as Device Assistance's
    // Store, by design.
    public static final String K_BASE_URL_PREV = "base_url_prev";
    public static final String K_SECRET_PREV = "secret_prev";
    public static final String K_TRANSPORT_SWITCHED_AT = "transport_switched_at";         // epoch ms of the switch
    public static final String K_TRANSPORT_PROBATION_UNTIL = "transport_probation_until"; // epoch ms probation ends
    // Last directive outcome ("switched" | "probe_failed" |
    // "invalid" | "reverted" | ""), mirrored into every report's
    // transport object so the migration is observable server-side.
    public static final String K_TRANSPORT_STATE = "transport_state";
    public static final String K_TRANSPORT_AT = "transport_at"; // epoch ms of that outcome

    // Last report
    public static final String K_LAST_REPORT_AT = "last_report_at";
    public static final String K_LAST_REPORT_CODE = "last_report_code";

    // Last command handled
    public static final String K_LAST_CMD_ID = "last_cmd_id";
    public static final String K_LAST_CMD_TEMPLATE = "last_cmd_template";
    public static final String K_LAST_CMD_OK = "last_cmd_ok";
    public static final String K_LAST_CMD_AT = "last_cmd_at";

    // Wear-install job mirror (reported as the wear_install object)
    public static final String K_WEAR_JOB_ID = "wear_job_id";
    public static final String K_WEAR_LABEL = "wear_label";
    public static final String K_WEAR_STAGE = "wear_stage";
    public static final String K_WEAR_DETAIL = "wear_detail";
    public static final String K_WEAR_AT = "wear_at";

    private Store() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String get(Context ctx, String key, String def) {
        return prefs(ctx).getString(key, def);
    }

    public static long getLong(Context ctx, String key, long def) {
        return prefs(ctx).getLong(key, def);
    }

    public static boolean getBool(Context ctx, String key, boolean def) {
        return prefs(ctx).getBoolean(key, def);
    }

    public static void put(Context ctx, String key, String value) {
        prefs(ctx).edit().putString(key, value).apply();
    }

    public static void put(Context ctx, String key, long value) {
        prefs(ctx).edit().putLong(key, value).apply();
    }

    public static void put(Context ctx, String key, boolean value) {
        prefs(ctx).edit().putBoolean(key, value).apply();
    }

    /** Worker configured = base URL and secret both present. */
    public static boolean workerConfigured(Context ctx) {
        return !get(ctx, K_BASE_URL, "").trim().isEmpty()
                && !get(ctx, K_SECRET, "").trim().isEmpty();
    }
}
