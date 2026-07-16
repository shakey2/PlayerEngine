package com.player2.playerengine.player2api;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** Pure stable-identity classification for the authenticated owner stop lane. */
public final class OwnerStopTargetResolution {
    public enum Kind {
        NONE,
        UNIQUE,
        AMBIGUOUS
    }

    public record Resolution(Kind kind, String characterId) {
    }

    private OwnerStopTargetResolution() {
    }

    public static Resolution resolve(Collection<String> characterIds) {
        Set<String> distinct = new LinkedHashSet<>();
        if (characterIds != null) {
            for (String id : characterIds) {
                if (id != null && !id.isBlank()) {
                    distinct.add(id);
                }
            }
        }
        if (distinct.isEmpty()) {
            return new Resolution(Kind.NONE, null);
        }
        if (distinct.size() > 1) {
            return new Resolution(Kind.AMBIGUOUS, null);
        }
        return new Resolution(Kind.UNIQUE, distinct.iterator().next());
    }
}
