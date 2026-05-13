package com.player2.playerengine.player2api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * Parses a user chat message for bot mentions and resolves which nearby automatons should receive it.
 *
 * Semantics when call-by-name is enabled:
 * - Message is delivered to 0+ automatons depending on mentions.
 * - If no resolvable mentions are found, deliver to nobody.
 *
 * Mention forms supported:
 * - Unqualified: Ellie   (anywhere; boundary-aware)
 * - Qualified:  Rick's Ellie   or  Rick’s Ellie
 * - Explicit:   @Ellie or @"Chat GPT"
 * - Quoted:     "Chat GPT" (treated as a mention candidate in addressing contexts)
 */
public final class CallByNameMentionRouter {
    private CallByNameMentionRouter() {
    }

    public record ResolvedTargets(Set<AgentConversationData> targets, @Nullable Event.UserMessage cleanedMessage) {
    }

    public static ResolvedTargets resolveTargets(Event.UserMessage msg, String speakerUsername,
            List<AgentConversationData> candidates) {
        if (msg == null || candidates == null || candidates.isEmpty()) {
            return new ResolvedTargets(Collections.emptySet(), null);
        }
        String raw = msg.message();
        if (raw == null || raw.isBlank()) {
            return new ResolvedTargets(Collections.emptySet(), null);
        }

        // Index candidates by bot-name keys (full + short) and owner username.
        Map<String, List<AgentConversationData>> byBotKey = new HashMap<>();
        Map<String, List<AgentConversationData>> byOwnerKey = new HashMap<>();
        for (AgentConversationData d : candidates) {
            if (d == null) {
                continue;
            }
            Character ch = d.getCharacter();
            if (ch == null) {
                continue;
            }
            addKey(byBotKey, normalizeKey(ch.name()), d);
            addKey(byBotKey, normalizeKey(ch.shortName()), d);
            addKey(byOwnerKey, normalizeKey(d.getMod().getOwnerUsername()), d);
        }

        CallByNameMentionParser.MentionParseResult parsed = CallByNameMentionParser.parse(raw, byBotKey.keySet(),
                byOwnerKey.keySet());
        if (parsed.intents().isEmpty()) {
            return new ResolvedTargets(Collections.emptySet(), null);
        }

        Set<AgentConversationData> out = new HashSet<>();
        for (CallByNameMentionParser.MentionIntent intent : parsed.intents()) {
            if (intent instanceof CallByNameMentionParser.MentionIntent.Qualified q) {
                String ownerKey = normalizeKey(q.owner());
                String botKey = normalizeKey(q.bot());
                if (ownerKey == null || botKey == null) {
                    continue;
                }
                List<AgentConversationData> ownerCandidates = byOwnerKey.getOrDefault(ownerKey, List.of());
                for (AgentConversationData d : ownerCandidates) {
                    if (d == null) {
                        continue;
                    }
                    if (candidateMatchesBotKey(d, botKey)) {
                        out.add(d);
                    }
                }
            } else if (intent instanceof CallByNameMentionParser.MentionIntent.Unqualified u) {
                String botKey = normalizeKey(u.bot());
                if (botKey == null) {
                    continue;
                }
                List<AgentConversationData> matches = byBotKey.getOrDefault(botKey, List.of());
                if (matches.isEmpty()) {
                    continue;
                }
                List<AgentConversationData> owned = new ArrayList<>();
                for (AgentConversationData d : matches) {
                    if (d != null && ownerEquals(d, speakerUsername)) {
                        owned.add(d);
                    }
                }
                if (!owned.isEmpty()) {
                    out.add(pickClosestStable(owned, speakerUsername));
                } else {
                    out.add(pickClosestStable(matches, speakerUsername));
                }
            }
        }

        if (out.isEmpty()) {
            return new ResolvedTargets(Collections.emptySet(), null);
        }

        // Optional: strip leading addressing segment only (e.g. "Ellie: hi" -> "hi").
        String cleaned = parsed.stripLeadingAddressing()
                .flatMap(prefixLen -> stripLeadingAddressing(raw, prefixLen))
                .orElse(raw);
        if (cleaned == null || cleaned.isBlank()) {
            return new ResolvedTargets(Collections.emptySet(), null);
        }
        Event.UserMessage cleanedMsg = cleaned.equals(raw) ? msg : new Event.UserMessage(cleaned, msg.userName());
        return new ResolvedTargets(out, cleanedMsg);
    }

    private static boolean ownerEquals(AgentConversationData d, String speakerUsername) {
        String owner = d.getMod().getOwnerUsername();
        if (owner == null || speakerUsername == null) {
            return false;
        }
        return owner.equalsIgnoreCase(speakerUsername);
    }

    private static boolean candidateMatchesBotKey(AgentConversationData d, String botKey) {
        if (d == null || botKey == null) {
            return false;
        }
        Character ch = d.getCharacter();
        if (ch == null) {
            return false;
        }
        return Objects.equals(normalizeKey(ch.name()), botKey) || Objects.equals(normalizeKey(ch.shortName()), botKey);
    }

    private static AgentConversationData pickClosestStable(List<AgentConversationData> candidates, String speakerUsername) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        // Prefer closeness to speaker if speaker is online; otherwise fall back to stable UUID ordering.
        // Distance lookup is relative to the speaker player UUID (resolved by username) in StatusUtils, so we use that
        // path rather than requiring server player resolution here.
        List<AgentConversationData> copy = new ArrayList<>(candidates);
        copy.sort(Comparator
                .comparingDouble((AgentConversationData d) -> safeDistanceToSpeaker(d, speakerUsername))
                .thenComparing(d -> safeUuid(d)));
        return copy.get(0);
    }

    private static double safeDistanceToSpeaker(AgentConversationData d, String speakerUsername) {
        try {
            if (d == null || speakerUsername == null) {
                return Double.POSITIVE_INFINITY;
            }
            // ConversationManager.isCloseToPlayer uses StatusUtils.getDistanceToUsername(mod, userName); reuse that
            // indirect path for ordering by distance to speaker.
            return com.player2.playerengine.player2api.status.StatusUtils.getDistanceToUsername(d.getMod(),
                    speakerUsername);
        } catch (Exception e) {
            return Double.POSITIVE_INFINITY;
        }
    }

    private static UUID safeUuid(AgentConversationData d) {
        try {
            return d.getUUID();
        } catch (Exception e) {
            return new UUID(0L, 0L);
        }
    }

    private static Optional<String> stripLeadingAddressing(String raw, int prefixLen) {
        if (raw == null) {
            return Optional.empty();
        }
        if (prefixLen <= 0 || prefixLen > raw.length()) {
            return Optional.empty();
        }
        String rest = raw.substring(prefixLen).trim();
        while (!rest.isEmpty() && isHailSeparator(rest.charAt(0))) {
            rest = rest.substring(1).trim();
        }
        if (rest.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(rest);
    }

    private static boolean isHailSeparator(char c) {
        return c == ':' || c == ',' || c == ';' || java.lang.Character.isWhitespace(c);
    }

    private static void addKey(Map<String, List<AgentConversationData>> map, @Nullable String key,
            AgentConversationData d) {
        if (key == null || key.isBlank() || d == null) {
            return;
        }
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(d);
    }

    @Nullable
    private static String normalizeKey(@Nullable String s) {
        return CallByNameMentionParser.normalizeKey(s);
    }
}

