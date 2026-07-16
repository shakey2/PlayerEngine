package com.player2.playerengine.player2api;

import java.util.concurrent.atomic.AtomicLong;

/** Unique generation tickets that make superseded model responses stale before they can run side effects. */
final class ConversationTurnGate {
    private final AtomicLong generation = new AtomicLong();

    long issueTicket() {
        return generation.incrementAndGet();
    }

    boolean accepts(long ticket) {
        return generation.get() == ticket;
    }

    void invalidate() {
        generation.incrementAndGet();
    }
}
