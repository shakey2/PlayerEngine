package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.agentic.elliegps.FarmObservationResult;
import com.player2.playerengine.agentic.elliegps.FarmObservationStatus;
import com.player2.playerengine.agentic.elliegps.FarmWaypointObservationProvider;
import com.player2.playerengine.agentic.elliegps.WaypointRecord;
import net.minecraft.server.level.ServerLevel;

/** Stateless production bridge from typed farm waypoints to the live farm scanner. */
public final class FarmWorldObservationProvider implements FarmWaypointObservationProvider {
    public static final FarmWorldObservationProvider INSTANCE = new FarmWorldObservationProvider();

    private FarmWorldObservationProvider() {
    }

    @Override
    public FarmObservationResult observe(PlayerEngineController controller, WaypointRecord farm) {
        if (controller == null) {
            return new FarmObservationResult(
                    FarmObservationStatus.HANDLER_UNAVAILABLE,
                    null,
                    "farm controller is unavailable");
        }
        ServerLevel level = controller.getWorld();
        if (level == null) {
            return new FarmObservationResult(
                    FarmObservationStatus.HANDLER_UNAVAILABLE,
                    null,
                    "farm world is unavailable");
        }
        return FarmCropScanner.observe(level, farm);
    }
}
