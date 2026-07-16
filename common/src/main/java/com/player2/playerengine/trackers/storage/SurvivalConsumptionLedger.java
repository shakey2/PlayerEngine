package com.player2.playerengine.trackers.storage;

import net.minecraft.world.item.Item;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Controller-lifetime ledger of confirmed item consumption by survival tasks.
 *
 * <p>The bounded event window lets a suspended mutation reconcile an inventory
 * delta without treating a known food use as part of its own receipt. A caller
 * must treat an empty result as an expired/invalid baseline, never as zero.</p>
 */
public final class SurvivalConsumptionLedger {
    private static final int DEFAULT_MAX_RETAINED_EVENTS = 256;

    private final int maxRetainedEvents;
    private final Deque<ConsumptionEvent> events = new ArrayDeque<>();
    private long epoch;
    private long sequence;

    public SurvivalConsumptionLedger() {
        this(DEFAULT_MAX_RETAINED_EVENTS);
    }

    SurvivalConsumptionLedger(int maxRetainedEvents) {
        if (maxRetainedEvents < 1) {
            throw new IllegalArgumentException("maxRetainedEvents must be positive");
        }
        this.maxRetainedEvents = maxRetainedEvents;
    }

    /** Captures a stable baseline before an inventory-sensitive mutation. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(epoch, sequence);
    }

    /** Records one confirmed vanilla item consumption. */
    public synchronized void recordConsumption(Item item) {
        Objects.requireNonNull(item, "item");
        if (sequence == Long.MAX_VALUE) {
            epoch = epoch == Long.MAX_VALUE ? 0L : epoch + 1L;
            sequence = 0L;
            events.clear();
        }
        sequence++;
        events.addLast(new ConsumptionEvent(sequence, item));
        while (events.size() > maxRetainedEvents) {
            events.removeFirst();
        }
    }

    /**
     * Returns the exact number of matching consumptions after {@code baseline},
     * or empty when the bounded history can no longer prove that count.
     */
    public synchronized OptionalInt consumedSince(Item item, Snapshot baseline) {
        Objects.requireNonNull(item, "item");
        if (baseline == null
                || baseline.epoch() != epoch
                || baseline.sequence() < 0L
                || baseline.sequence() > sequence) {
            return OptionalInt.empty();
        }
        ConsumptionEvent earliest = events.peekFirst();
        if (earliest != null && baseline.sequence() < earliest.sequence() - 1L) {
            return OptionalInt.empty();
        }

        int count = 0;
        for (ConsumptionEvent event : events) {
            if (event.sequence() > baseline.sequence() && event.item() == item) {
                count++;
            }
        }
        return OptionalInt.of(count);
    }

    public record Snapshot(long epoch, long sequence) {
    }

    private record ConsumptionEvent(long sequence, Item item) {
    }
}
