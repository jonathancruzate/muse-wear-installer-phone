package com.jonathancruzate.metamusewearinstallerphone;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * WearStatusService — receives Muse Wear Installer's job status
 * messages (PATH_STATUS) from the watch and hands them to
 * WearInstallRouter, which unblocks the waiting command execution
 * and mirrors the stage into the report. (Ported from Muse Device
 * Assistance v1.13.)
 */
public final class WearStatusService extends WearableListenerService {

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (!WearProtocol.PATH_STATUS.equals(event.getPath())) return;
        String json = new String(event.getData(), StandardCharsets.UTF_8);
        Map<String, String> m = WearProtocol.parseFlat(json);
        String jobId = m.get("job_id");
        String stage = m.get("stage");
        if (jobId == null || jobId.isEmpty() || stage == null) return;
        WearInstallRouter.deliver(this, jobId,
                m.getOrDefault("label", ""), stage,
                m.getOrDefault("detail", ""));
    }
}
