package com.jonathancruzate.metamusewearinstallerphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * CommandService — the wear-install command lane (the standalone
 * equivalent of the fetch→gate→execute→report tick that ran inside
 * Device Assistance's ShellBridge).
 *
 * A single worker thread polls the Worker every 30 s as device
 * "wear-installer". Per cycle it pulls at most one command, gates it
 * through WearGate (only wear_install_face can pass), executes it
 * via WearInstallRouter (which blocks for the watch's verdict, up to
 * 300 s), posts the result back, and posts a wearbridge report.
 * Heartbeat reports go out every ~5 min when idle. Foreground
 * service type specialUse — see the manifest note (the dataSync
 * time-budget lesson from Device Assistance v1.4.2).
 */
public final class CommandService extends Service {
    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_ID = "command_lane";
    private static final long POLL_MS = 30_000;
    private static final long HEARTBEAT_MS = 300_000;

    private volatile boolean running;
    private Thread thread;
    private long lastReportAt;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, CommandService.class);
        ctx.startForegroundService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, CommandService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Wear install lane",
                    NotificationManager.IMPORTANCE_LOW));
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, buildNotification("starting"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, buildNotification("starting"));
            }
        } catch (Exception e) {
            // Fleet lesson (Device Assistance v1.4.2): an unguarded
            // typed startForeground can throw and crash-loop the
            // service; fall back to the untyped call, and log it.
            AppLog.log(this, "SERVICE", "typed startForeground refused: "
                    + e.getClass().getSimpleName() + "; retrying untyped");
            startForeground(NOTIF_ID, buildNotification("starting"));
        }
        running = true;
        thread = new Thread(this::loop, "wear-cmd");
        thread.start();
        AppLog.log(this, "SERVICE", "command lane started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (thread != null) thread.interrupt();
        AppLog.log(this, "SERVICE", "command lane stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Muse Wear Installer Phone")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    private void loop() {
        WorkerClient.postReport(this, "heartbeat");
        lastReportAt = System.currentTimeMillis();
        while (running) {
            try {
                tick();
            } catch (Exception e) {
                AppLog.log(this, "SERVICE", "tick failed: "
                        + e.getClass().getSimpleName() + " " + e.getMessage());
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                if (!running) break;
            }
        }
    }

    private void tick() {
        if (!Store.workerConfigured(this)) {
            updateNotification("Not configured — open the app to set the Worker base + secret");
            return;
        }
        WorkerClient.Command cmd = WorkerClient.fetchCommand(this);
        if (cmd != null) {
            handle(cmd);
            lastReportAt = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - lastReportAt >= HEARTBEAT_MS) {
            boolean reportOk = WorkerClient.postReport(this, "heartbeat");
            lastReportAt = System.currentTimeMillis();
            // v1.0.2 probation fallback: nothing fetched AND the
            // report failed against the current base — give the
            // transport probation a chance to revert to the previous
            // pair (no-op outside the 24 h probation window).
            if (!reportOk) WorkerClient.maybeRevertTransport(this);
        }
        String stage = Store.get(this, Store.K_WEAR_STAGE, "");
        updateNotification(stage.isEmpty()
                ? "Lane active — waiting for install commands"
                : "Lane active — last job stage: " + stage);
    }

    /** One command, end to end (ShellBridge.tick's wear path). */
    private void handle(WorkerClient.Command cmd) {
        if (cmd.expiresAtMs > 0 && System.currentTimeMillis() > cmd.expiresAtMs) {
            AppLog.log(this, "CMD", cmd.id + " expired before execution; reporting");
            WorkerClient.postCommandResult(this, cmd.id, false, "", 0, "expired");
            record(cmd, false);
            WorkerClient.postReport(this, "change");
            return;
        }
        WearGate.Gate gate = WearGate.check(cmd.template, cmd.args);
        if (!gate.allowed) {
            AppLog.log(this, "CMD", cmd.id + " REFUSED template=" + cmd.template
                    + ": " + gate.refusal);
            WorkerClient.postCommandResult(this, cmd.id, false, "", 0,
                    "refused: " + gate.refusal);
            record(cmd, false);
            WorkerClient.postReport(this, "change");
            return;
        }
        WearInstallRouter.Result r = WearInstallRouter.execute(this, gate.args);
        AppLog.log(this, "CMD", cmd.id + " " + cmd.template
                + " -> ok=" + r.ok + " " + r.ms + "ms"
                + (r.error != null ? " error=" + r.error : ""));
        WorkerClient.postCommandResult(this, cmd.id, r.ok, r.stdoutTail, r.ms, r.error);
        record(cmd, r.ok);
        WorkerClient.postReport(this, "change");
    }

    private void record(WorkerClient.Command cmd, boolean ok) {
        Store.put(this, Store.K_LAST_CMD_ID, cmd.id);
        Store.put(this, Store.K_LAST_CMD_TEMPLATE, cmd.template);
        Store.put(this, Store.K_LAST_CMD_OK, ok);
        Store.put(this, Store.K_LAST_CMD_AT, System.currentTimeMillis());
    }
}
