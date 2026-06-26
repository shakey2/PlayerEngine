package com.player2.playerengine.structureprotection;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.automaton.api.BaritoneAPI;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.BlockEvent;

import net.minecraft.server.level.ServerPlayer;

/**
 * Common-side Architectury event listeners that populate and prune
 * {@link PlayerPlacedBlockStore} (Workstream 2). Registered from
 * {@code PlayerEngine.onInitialize()} alongside the other Architectury event registrations.
 *
 * <p>No loader-specific imports: {@link BlockEvent} fires for real-player hand placement on all
 * four loaders (Fabric via a {@code BlockItem.place} mixin; Forge/NeoForge via the native
 * {@code EntityPlaceEvent}). The Fabric mixin runs on both the client and the server, so the place
 * listener must early-return on {@code level.isClientSide()} to record server-side only.
 *
 * <p>Both listeners observe only — they never cancel placement/break — and are no-ops when the
 * {@code respectStructuresEnabled} toggle is off. Nothing here touches a model-facing surface.
 */
public final class StructureProtectionEvents {

    private StructureProtectionEvents() {
    }

    /** Whether the feature is on. When off, both listeners no-op and the store is never populated. */
    private static boolean enabled() {
        return BaritoneAPI.getGlobalSettings().respectStructuresEnabled.get();
    }

    /**
     * Registers the place + break listeners. Call once from {@code PlayerEngine.onInitialize()}.
     */
    public static void register() {
        // PLACE: record a confirmed real-player placement. placer is @Nullable Entity (null for
        // dispensers/pistons); a bot is an AutomatoneEntity (never a ServerPlayer), and the
        // staticControllers check is defense-in-depth against any future ServerPlayer-extending
        // fake player. Always returns pass() — observe, never cancel.
        BlockEvent.PLACE.register((level, pos, state, placer) -> {
            if (!enabled()) {
                return EventResult.pass();
            }
            if (level.isClientSide()) {
                return EventResult.pass();
            }
            if (!(placer instanceof ServerPlayer sp)) {
                return EventResult.pass();
            }
            if (PlayerEngineController.staticControllers.containsKey(sp.getUUID())) {
                return EventResult.pass();
            }
            PlayerPlacedBlockStore store = PlayerPlacedBlockStore.get();
            if (store != null) {
                // No immutable() copy needed: add() extracts pos.asLong() synchronously and never retains the ref.
                store.add(level.dimension().location().toString(), pos);
            }
            return EventResult.pass();
        });

        // BREAK: purge the position so the store never accumulates stale entries. Best-effort —
        // fires for player breaks only; non-player removal (explosion/fluid/piston) is covered by
        // the store's cap+eviction, and a stale entry on now-air is harmless for traversal. player
        // is a ServerPlayer (server-side by construction). Always returns pass().
        BlockEvent.BREAK.register((level, pos, state, player, xp) -> {
            if (!enabled()) {
                return EventResult.pass();
            }
            PlayerPlacedBlockStore store = PlayerPlacedBlockStore.get();
            if (store != null) {
                // No immutable() copy needed: remove() extracts pos.asLong() synchronously and never retains the ref.
                store.remove(level.dimension().location().toString(), pos);
            }
            return EventResult.pass();
        });
    }
}
