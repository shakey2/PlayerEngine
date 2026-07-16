package com.player2.playerengine.player2api;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Regression checks for passive model context that must not start a conversation turn. */
public final class PassiveConversationInfoSelfTest {
    private PassiveConversationInfoSelfTest() {
    }

    public static void runAll() {
        Deque<Event> realEvents = new ArrayDeque<>();
        Deque<Event.InfoMessage> deferred = new ArrayDeque<>();

        require(AgentConversationData.addDeferredInfo(deferred, new Event.InfoMessage("first")),
                "bounded passive note should be accepted");
        require(!AgentConversationData.hasDispatchableEvents(realEvents),
                "passive-only context must not make a model turn dispatchable");
        require(AgentConversationData.mergeDeferredInfoForProcessing(realEvents, deferred) == 0,
                "passive context must not drain without a real trigger");
        require(deferred.size() == 1, "passive context must remain queued without a trigger");

        require(AgentConversationData.addDeferredInfo(deferred, new Event.InfoMessage("second")),
                "second passive note should be accepted");
        require(AgentConversationData.addDeferredInfo(deferred, new Event.InfoMessage("second")),
                "duplicate passive note should be a harmless no-op");
        require(deferred.size() == 2, "duplicate passive context must be coalesced");
        require(!AgentConversationData.addDeferredInfo(deferred, new Event.InfoMessage("x".repeat(513))),
                "unbounded passive context must be rejected");

        Event.UserMessage trigger = new Event.UserMessage("continue", "owner");
        realEvents.addLast(trigger);
        require(AgentConversationData.mergeDeferredInfoForProcessing(realEvents, deferred) == 2,
                "all passive context should join a real turn exactly once");
        List<Event> merged = List.copyOf(realEvents);
        require(merged.get(0).equals(new Event.InfoMessage("first")), "passive notes must preserve FIFO order");
        require(merged.get(1).equals(new Event.InfoMessage("second")), "passive notes must preserve FIFO order");
        require(merged.get(2) == trigger, "the independently dispatchable event must remain last");
        require(deferred.isEmpty(), "merged passive context must be consumed");

        for (int i = 0; i < 6; i++) {
            require(AgentConversationData.addDeferredInfo(deferred, new Event.InfoMessage("note-" + i)),
                    "bounded queue insertion should succeed");
        }
        require(deferred.size() == 4, "passive queue must stay bounded");
        require(deferred.peekFirst().message().equals("note-2"), "oldest passive context must be evicted first");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("PassiveConversationInfoSelfTest failed: " + message);
        }
    }
}
