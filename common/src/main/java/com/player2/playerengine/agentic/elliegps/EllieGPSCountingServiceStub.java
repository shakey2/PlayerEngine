package com.player2.playerengine.agentic.elliegps;

import com.player2.playerengine.util.ItemTarget;
import net.minecraft.world.phys.Vec3;

/**
 * No-op {@link EllieGPSCountingService} for the documented-seam round (Phase C, Part C5 placeholder).
 *
 * <p>Always returns {@code 0}; never blocks, never throws, makes no network/model call. The
 * material-availability counter holds this stub directly at its {@code waypointStub} term (no
 * controller/context accessor is wired this round &mdash; see {@link EllieGPSCountingService} and
 * {@code masterplan/mine-collect-aggregate-count-and-wander-bound-plan.md} WS2). C5 replaces this
 * stub with a real MinHash-index-backed implementation at startup, honoring the snapshot-only /
 * keyword-only contract documented on {@link EllieGPSCountingService}.
 */
public final class EllieGPSCountingServiceStub implements EllieGPSCountingService {
    /** Shared no-op instance; safe to hold as a constant (stateless, deterministic). */
    public static final EllieGPSCountingServiceStub INSTANCE = new EllieGPSCountingServiceStub();

    @Override
    public int estimateNearbyWaypointItems(ItemTarget target, Vec3 origin, double radiusBlocks) {
        return 0;
    }
}
