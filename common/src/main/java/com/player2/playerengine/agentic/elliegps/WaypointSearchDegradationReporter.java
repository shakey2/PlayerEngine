package com.player2.playerengine.agentic.elliegps;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.player2api.AiConversationFeedback;
import java.util.EnumSet;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.network.chat.Component;

/** Reports each typed EllieGPS search degradation once per controller and status. */
public final class WaypointSearchDegradationReporter {

    private static final Map<PlayerEngineController, EnumSet<WaypointSearchStatus>> REPORTED =
            new WeakHashMap<>();

    private WaypointSearchDegradationReporter() {}

    /**
     * Sends a localized player line and bounded raw-English model feedback for a degraded status.
     * Clean statuses are ignored. Weak controller keys prevent this process-wide dedupe from
     * retaining stopped bots.
     */
    public static void reportOnce(PlayerEngineController controller, WaypointSearchStatus status) {
        if (controller == null || !isDegraded(status)) {
            return;
        }

        synchronized (REPORTED) {
            EnumSet<WaypointSearchStatus> statuses = REPORTED.computeIfAbsent(
                    controller, ignored -> EnumSet.noneOf(WaypointSearchStatus.class));
            if (!statuses.add(status)) {
                return;
            }
        }

        Component playerMessage;
        String modelMessage;
        switch (status) {
            case FULL_STORE_FALLBACK -> {
                playerMessage = WaypointReportFormatter.searchFallbackComponent();
                modelMessage = WaypointReportFormatter.searchFallbackModel();
            }
            case FAILED_SCAN_LIMIT -> {
                playerMessage = WaypointReportFormatter.searchScanLimitComponent();
                modelMessage = WaypointReportFormatter.searchScanLimitModel();
            }
            case FAILED_STORE_UNAVAILABLE -> {
                playerMessage = WaypointReportFormatter.searchStoreUnavailableComponent();
                modelMessage = WaypointReportFormatter.searchStoreUnavailableModel();
            }
            case FAILED_SEARCH_ERROR -> {
                playerMessage = WaypointReportFormatter.searchFailedComponent();
                modelMessage = WaypointReportFormatter.searchFailedModel();
            }
            default -> {
                return;
            }
        }

        controller.reportAgenticProgress(playerMessage, true);
        AiConversationFeedback.enqueueInfo(
                controller, WaypointReportFormatter.boundModel(modelMessage));
    }

    private static boolean isDegraded(WaypointSearchStatus status) {
        return status == WaypointSearchStatus.FULL_STORE_FALLBACK
                || status == WaypointSearchStatus.FAILED_SCAN_LIMIT
                || status == WaypointSearchStatus.FAILED_STORE_UNAVAILABLE
                || status == WaypointSearchStatus.FAILED_SEARCH_ERROR;
    }
}
