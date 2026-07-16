package com.player2.playerengine.agentic.elliegps;

import com.player2.playerengine.retrieval.RetrievalHit;

import java.util.List;

/** Injectable token/health/synchronization/query seam for the derived waypoint index. */
public interface WaypointIndexBackend {
    boolean isHealthyFor(String expectedVersionToken);

    WaypointIndexUpdateStatus synchronize(EllieGPSStore store);

    List<RetrievalHit> query(String query, int limit) throws Exception;
}
