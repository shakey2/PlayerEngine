package com.player2.playerengine.agentic.elliegps;

import com.player2.playerengine.PlayerEngineController;

/** Pluggable live-world farm observer installed by the farm setup implementation. */
@FunctionalInterface
public interface FarmWaypointObservationProvider {
    FarmObservationResult observe(PlayerEngineController controller, WaypointRecord farm);
}
