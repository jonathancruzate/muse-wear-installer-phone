package com.jonathancruzate.metamusewearinstallerphone;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The command channel to Muse's own Cloudflare Worker — the same
 * endpoints and shapes as Muse Device Assistance's WorkerClient
 * (which this is adapted from), but with device identity
 * "wear-installer": the Worker routes that device to the wearbridge
 * lane (KV wearbridge:queue / wearbridge:cmd:<id> /
 * wearbridge:report), fully separate from the phonebridge lane.
 *
 * Protocol:
 *   GET  {base}/phone-bridge/commands?secret={secret}&device=wear-installer
 *        -> {"command": {"id","template","args":[...],"expires_at"}}
 *   POST {base}/phone-bridge/command-result?secret={secret}&device=wear-installer
 *        <- {"device":"wear-installer","id","ok","stdout_tail","ms","error"}
 *   POST {base}/phone-bridge/report?secret={secret}
 *        <- {"device":"wear-installer","kind","ts","plan",
 *            "wear_install": {last job mirror}}
 *        (stored server-side as wearbridge:report)
 *
 * The secret is entered on-device in the setup screen and stored
 * only in this app's private preferences. It is NEVER hardcoded in
 * this source and never written to the log. The Worker is a
 * transport only: WearGate gates every command on-device, and the
 * Worker cannot widen the gate.
 *
 * v1.0.2: any of these responses may carry a "transport" directive
 * ({"base","secret"}) — the Worker's per-device migration channel.
 * handleTransportDirective below probes the new base before
 * switching and persists the pair only on probe success (Device
 * Assistance v1.9 semantics; decisions in TransportDirective).
 * Every report also carries a "transport" object with the app's
 * current base (host+path only, never the secret) and the last
 * directive outcome, so the migration is observable server-side.
 */
public final class WorkerClient {
    private WorkerClient() {}

    /** This app's Worker device identity (the wearbridge lane). */
    public static final String DEVICE = "wear-installer";
    private static final String USER_AGENT = "MuseWearInstallerPhone/1.0.2";

    /** One structured command pulled from the Worker. */
    public static final class Command {
        public final String id;
        public final String template;
        public final java.util.List<String> args;
        public final long expiresAtMs;

        Command(String id, String template, java.util.List<String> args,
                long expiresAtMs) {
            this.id = id;
            this.template = template;
            this.args = args;
            this.expiresAtMs = expiresAtMs;
        }
    }

    /** Fetch at most one pending command; null when none / endpoint absent. */
    public static Command fetchCommand(Context ctx) {
        if (!Store.workerConfigured(ctx)) return null;
        String base = Store.get(ctx, Store.K_BASE_URL, "").trim();
        String secret = Store.get(ctx, Store.K_SECRET, "").trim();
        try {
            URL url = new URL(base + "/phone-bridge/commands?secret="
                    + URLEncoder.encode(secret, "UTF-8")
                    + "&device=" + URLEncoder.encode(DEVICE, "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            int code = c.getResponseCode();
            if (code == 404 || code == 204) return null; // channel not deployed yet
            if (code != 200) {
                AppLog.log(ctx, "CMD", "commands GET -> HTTP " + code);
                return null;
            }
            String body = readAll(c.getInputStream());
            if (body == null || body.trim().isEmpty()) return null;
            JSONObject root = new JSONObject(body);
            // v1.0.2: a transport directive rides this response and
            // is processed FIRST, whether or not a command came with
            // it (the directive is valid on its own — it is probed
            // before it is taken, inside the handler).
            handleTransportDirective(ctx, root, base, secret);
            JSONObject cmdObj = root.optJSONObject("command");
            if (cmdObj == null && root.optJSONArray("commands") != null
                    && root.optJSONArray("commands").length() > 0) {
                cmdObj = root.optJSONArray("commands").optJSONObject(0);
            }
            if (cmdObj == null && root.has("template")) cmdObj = root;
            if (cmdObj == null) return null;
            String id = cmdObj.optString("id", "");
            String template = cmdObj.optString("template", "");
            if (id.isEmpty() || template.isEmpty()) return null;
            java.util.List<String> args = new java.util.ArrayList<>();
            org.json.JSONArray arr = cmdObj.optJSONArray("args");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) args.add(arr.optString(i, ""));
            }
            long expires = cmdObj.optLong("expires_at", 0);
            return new Command(id, template, args, expires);
        } catch (Exception e) {
            AppLog.log(ctx, "CMD", "commands GET failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        }
    }

    /** Post one command's result back over the same channel. */
    public static boolean postCommandResult(Context ctx, String id, boolean ok,
                                            String stdoutTail, long ms, String error) {
        if (!Store.workerConfigured(ctx)) return false;
        String base = Store.get(ctx, Store.K_BASE_URL, "").trim();
        String secret = Store.get(ctx, Store.K_SECRET, "").trim();
        try {
            JSONObject body = new JSONObject();
            body.put("device", DEVICE);
            body.put("id", id);
            body.put("ok", ok);
            body.put("stdout_tail", stdoutTail != null ? stdoutTail : "");
            body.put("ms", ms);
            if (error != null) body.put("error", error);
            URL url = new URL(base + "/phone-bridge/command-result?secret="
                    + URLEncoder.encode(secret, "UTF-8")
                    + "&device=" + URLEncoder.encode(DEVICE, "UTF-8"));
            HttpResult r = postJson(url, body);
            AppLog.log(ctx, "CMD", "command-result " + id + " ok=" + ok
                    + " -> HTTP " + r.code);
            // v1.0.2: the result response may carry a directive too.
            handleTransportDirective(ctx, r.json(), base, secret);
            return r.code >= 200 && r.code < 300;
        } catch (Exception e) {
            AppLog.log(ctx, "CMD", "command-result POST failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
    }

    /**
     * Post a state report. The Worker stores it as wearbridge:report.
     * The wear_install object mirrors the last job's stage so Muse
     * can read job state server-side between command results.
     */
    public static boolean postReport(Context ctx, String kind) {
        if (!Store.workerConfigured(ctx)) return false;
        String base = Store.get(ctx, Store.K_BASE_URL, "").trim();
        String secret = Store.get(ctx, Store.K_SECRET, "").trim();
        try {
            JSONObject body = new JSONObject();
            body.put("device", DEVICE);
            body.put("kind", kind);
            body.put("ts", System.currentTimeMillis());
            String stage = Store.get(ctx, Store.K_WEAR_STAGE, "");
            body.put("plan", stage.isEmpty()
                    ? "wear install lane idle"
                    : "wear install lane: last job stage " + stage);
            body.put("wear_install", WearInstallRouter.snapshot(ctx));
            // v1.0.2: the transport object — current base as
            // host+path (never the secret), the last directive
            // outcome, and when it happened. The Worker whitelists
            // exactly these three fields into wearbridge:report.
            JSONObject transport = new JSONObject();
            transport.put("base", hostPath(base));
            transport.put("state", Store.get(ctx, Store.K_TRANSPORT_STATE, ""));
            transport.put("at", Store.getLong(ctx, Store.K_TRANSPORT_AT, 0));
            body.put("transport", transport);
            URL url = new URL(base + "/phone-bridge/report?secret="
                    + URLEncoder.encode(secret, "UTF-8"));
            HttpResult r = postJson(url, body);
            Store.put(ctx, Store.K_LAST_REPORT_AT, System.currentTimeMillis());
            Store.put(ctx, Store.K_LAST_REPORT_CODE, r.code);
            AppLog.log(ctx, "WORKER", "report (" + kind + ") -> HTTP " + r.code);
            // v1.0.2: the report response may carry a directive too
            // (reports flow even when no command does).
            handleTransportDirective(ctx, r.json(), base, secret);
            return r.code >= 200 && r.code < 300;
        } catch (Exception e) {
            AppLog.log(ctx, "WORKER", "report POST failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
    }

    /** POST result: HTTP code plus the parsed JSON body (empty object when unparseable). */
    private static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }

        JSONObject json() {
            try {
                return body != null && !body.trim().isEmpty()
                        ? new JSONObject(body) : new JSONObject();
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    private static HttpResult postJson(URL url, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setDoOutput(true);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) {
            os.write(payload);
        }
        int code = c.getResponseCode();
        String resp = "";
        try {
            InputStream in = code >= 200 && code < 400
                    ? c.getInputStream() : c.getErrorStream();
            if (in != null) resp = readAll(in);
        } catch (Exception ignored) {
            // A response body we cannot read never blocks the caller;
            // the code alone still carries the verdict.
        }
        return new HttpResult(code, resp);
    }

    // ---- v1.0.2 transport migration -------------------------------------
    // Any Worker response on this lane may carry a top-level
    // "transport" object {"base","secret"} — emitted by the Worker
    // only while the migration flag is armed — directing the app to a
    // new base + secret with NO on-device step. Decisions live in
    // TransportDirective (pure, unit-tested); this section does the
    // network probe and the Store writes, porting Device Assistance
    // v1.9's semantics: probe BEFORE switching, keep the previous
    // pair for the 24 h probation fallback, never log a secret.

    /**
     * Handle a transport directive riding a Worker response. No-ops
     * when the directive is absent. currentBase/currentSecret are
     * the pair the just-finished call used.
     */
    private static void handleTransportDirective(Context ctx, JSONObject root,
                                                 String currentBase, String currentSecret) {
        if (root == null) return;
        JSONObject t = root.optJSONObject("transport");
        if (t == null) return;
        String newBase = t.optString("base", "");
        String newSecret = t.optString("secret", "");
        TransportDirective.Decision d = TransportDirective.decide(
                currentBase, currentSecret, newBase, newSecret, null);
        if (d == TransportDirective.Decision.SAME) return;
        if (d == TransportDirective.Decision.INVALID) {
            recordTransportState(ctx, "invalid");
            AppLog.log(ctx, "TRANSPORT", "directive invalid, ignored");
            return;
        }
        // NEEDS_PROBE: the new pair must answer a harmless
        // authenticated read before it is persisted.
        boolean probeOk = probeTransport(newBase.trim(), newSecret.trim());
        d = TransportDirective.decide(
                currentBase, currentSecret, newBase, newSecret, probeOk);
        if (d != TransportDirective.Decision.SWITCH) {
            recordTransportState(ctx, "probe_failed");
            AppLog.log(ctx, "TRANSPORT", "probe failed for "
                    + hostPath(newBase) + ", staying on current base");
            return;
        }
        long now = System.currentTimeMillis();
        Store.put(ctx, Store.K_BASE_URL_PREV, currentBase);
        Store.put(ctx, Store.K_SECRET_PREV, currentSecret);
        Store.put(ctx, Store.K_BASE_URL, newBase.trim());
        Store.put(ctx, Store.K_SECRET, newSecret.trim());
        Store.put(ctx, Store.K_TRANSPORT_SWITCHED_AT, now);
        Store.put(ctx, Store.K_TRANSPORT_PROBATION_UNTIL, now + 24L * 60 * 60 * 1000);
        recordTransportState(ctx, "switched");
        AppLog.log(ctx, "TRANSPORT", "switched to " + hostPath(newBase)
                + " (probation 24 h)");
    }

    /**
     * Probation fallback, called by CommandService when a cycle
     * fetched no command AND its report failed against the current
     * base. While probation runs and a previous pair is stored,
     * probe the previous base once; on success restore it and end
     * probation (the PREV values themselves are kept). Returns true
     * when a revert happened. After probation expiry this never
     * probes and never reverts — the prev values stay stored, inert.
     */
    public static boolean maybeRevertTransport(Context ctx) {
        long until = Store.getLong(ctx, Store.K_TRANSPORT_PROBATION_UNTIL, 0);
        if (until <= 0 || System.currentTimeMillis() >= until) return false;
        String prevBase = Store.get(ctx, Store.K_BASE_URL_PREV, "").trim();
        String prevSecret = Store.get(ctx, Store.K_SECRET_PREV, "").trim();
        if (prevBase.isEmpty() || prevSecret.isEmpty()) return false;
        if (!probeTransport(prevBase, prevSecret)) return false;
        Store.put(ctx, Store.K_BASE_URL, prevBase);
        Store.put(ctx, Store.K_SECRET, prevSecret);
        Store.put(ctx, Store.K_TRANSPORT_SWITCHED_AT, 0L);
        Store.put(ctx, Store.K_TRANSPORT_PROBATION_UNTIL, 0L);
        recordTransportState(ctx, "reverted");
        AppLog.log(ctx, "TRANSPORT", "reverted to previous base "
                + hostPath(prevBase) + " (new base unreachable)");
        return true;
    }

    /**
     * Harmless authenticated read used as the switch probe: GET the
     * lane's own stored report on the candidate base with the
     * candidate secret. True only on HTTP 200 + a parseable JSON
     * body. A pure read — consumes no command, writes nothing.
     */
    private static boolean probeTransport(String base, String secret) {
        try {
            URL url = new URL(base + "/phone-bridge/report?secret="
                    + URLEncoder.encode(secret, "UTF-8")
                    + "&device=" + URLEncoder.encode(DEVICE, "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            if (c.getResponseCode() != 200) return false;
            String body = readAll(c.getInputStream());
            if (body == null || body.trim().isEmpty()) return false;
            new JSONObject(body);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void recordTransportState(Context ctx, String state) {
        Store.put(ctx, Store.K_TRANSPORT_STATE, state);
        Store.put(ctx, Store.K_TRANSPORT_AT, System.currentTimeMillis());
    }

    /** Base URL as host+path ("jonathancruzate.com/bridge") — safe to log and report; never carries a secret. */
    static String hostPath(String base) {
        if (base == null) return "";
        String b = base.trim();
        int scheme = b.indexOf("://");
        if (scheme >= 0) b = b.substring(scheme + 3);
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
