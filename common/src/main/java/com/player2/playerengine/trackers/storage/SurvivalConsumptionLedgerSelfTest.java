package com.player2.playerengine.trackers.storage;

import net.minecraft.world.item.Items;

/** Deterministic coverage for exact per-item counts and bounded-history expiry. */
public final class SurvivalConsumptionLedgerSelfTest {
    private SurvivalConsumptionLedgerSelfTest() {
    }

    public static void runAll() {
        countsOnlyTheRequestedItem();
        expiresBaselinesOutsideTheBoundedWindow();
    }

    private static void countsOnlyTheRequestedItem() {
        SurvivalConsumptionLedger ledger = new SurvivalConsumptionLedger(8);
        SurvivalConsumptionLedger.Snapshot baseline = ledger.snapshot();
        ledger.recordConsumption(Items.CARROT);
        ledger.recordConsumption(Items.POTATO);
        ledger.recordConsumption(Items.CARROT);

        require(ledger.consumedSince(Items.CARROT, baseline).orElse(-1) == 2,
                "carrot baseline counts exactly two confirmed carrot consumptions");
        require(ledger.consumedSince(Items.POTATO, baseline).orElse(-1) == 1,
                "potato baseline excludes carrot consumption events");
    }

    private static void expiresBaselinesOutsideTheBoundedWindow() {
        SurvivalConsumptionLedger ledger = new SurvivalConsumptionLedger(2);
        SurvivalConsumptionLedger.Snapshot expired = ledger.snapshot();
        ledger.recordConsumption(Items.CARROT);
        SurvivalConsumptionLedger.Snapshot retained = ledger.snapshot();
        ledger.recordConsumption(Items.POTATO);
        ledger.recordConsumption(Items.CARROT);

        require(ledger.consumedSince(Items.CARROT, expired).isEmpty(),
                "a baseline older than the retained history is explicitly unknown");
        require(ledger.consumedSince(Items.CARROT, retained).orElse(-1) == 1,
                "a retained baseline still yields an exact per-item count");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
