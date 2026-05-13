package com.player2.playerengine.player2api;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure string parser for call-by-name mention extraction.
 *
 * Does not depend on Minecraft classes; safe to unit-test in isolation.
 */
public final class CallByNameMentionParser {
    private CallByNameMentionParser() {
    }

    public sealed interface MentionIntent permits MentionIntent.Qualified, MentionIntent.Unqualified {
        record Qualified(String owner, String bot) implements MentionIntent {
        }

        record Unqualified(String bot) implements MentionIntent {
        }
    }

    public record MentionParseResult(List<MentionIntent> intents, Optional<Integer> stripLeadingAddressing) {
    }

    // Owner-possessive qualifier: Rick's Ellie, Rick’s Ellie
    private static final Pattern POSSESSIVE = Pattern.compile(
            "(?iu)(^|\\b)(?<owner>[\\p{L}\\p{N}_-]{1,32})\\s*(?:'s|’s)\\s*(?<bot>[^\\s\"].*?)\\b");

    private static final Pattern AT_QUOTED = Pattern.compile("(?iu)@\\s*\"(?<name>[^\"]+)\"");
    private static final Pattern AT_BARE = Pattern.compile("(?iu)@\\s*(?<name>[^\\s,;:!?]+)");

    public static MentionParseResult parse(String raw, Set<String> candidateBotKeys) {
        return parse(raw, candidateBotKeys, Set.of());
    }

    public static MentionParseResult parse(String raw, Set<String> candidateBotKeys, Set<String> candidateOwnerKeys) {
        if (raw == null) {
            return new MentionParseResult(List.of(), Optional.empty());
        }

        String normalizedRaw = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        List<MentionIntent> out = new ArrayList<>();

        // 1) Explicit @mentions (quoted and bare)
        Matcher m = AT_QUOTED.matcher(normalizedRaw);
        while (m.find()) {
            String name = m.group("name");
            if (name != null && !name.isBlank()) {
                out.add(new MentionIntent.Unqualified(name));
            }
        }
        m = AT_BARE.matcher(normalizedRaw);
        while (m.find()) {
            String name = m.group("name");
            if (name != null && !name.isBlank()) {
                out.add(new MentionIntent.Unqualified(name));
            }
        }

        // 2) Qualified mentions (possessive owner)
        m = POSSESSIVE.matcher(normalizedRaw);
        while (m.find()) {
            String owner = m.group("owner");
            String bot = m.group("bot");
            if (owner == null || bot == null) {
                continue;
            }
            bot = trimTrailingPunctuation(bot);
            if (!owner.isBlank() && !bot.isBlank()) {
                out.add(new MentionIntent.Qualified(owner, bot));
            }
        }

        // 3) Candidate-driven unqualified scan (boundary-aware)
        if (candidateBotKeys != null && !candidateBotKeys.isEmpty()) {
            String lower = normalizedRaw.toLowerCase(Locale.ROOT);
            for (String botKey : candidateBotKeys) {
                if (botKey == null || botKey.isBlank()) {
                    continue;
                }
                if (containsNameWithBoundaries(lower, botKey)) {
                    out.add(new MentionIntent.Unqualified(botKey));
                }
            }
        }

        Optional<Integer> strip = computeLeadingStripLength(normalizedRaw, out);
        return new MentionParseResult(out, strip);
    }

    private static Optional<Integer> computeLeadingStripLength(String raw, List<MentionIntent> intents) {
        if (raw == null || raw.isBlank() || intents == null || intents.isEmpty()) {
            return Optional.empty();
        }
        // If message begins with "@Name" or "@\"Name\"" then strip that segment.
        Matcher m = AT_QUOTED.matcher(raw);
        if (m.find() && m.start() == 0) {
            return Optional.of(m.end());
        }
        m = AT_BARE.matcher(raw);
        if (m.find() && m.start() == 0) {
            return Optional.of(m.end());
        }
        // If message begins with "Name:" / "Name," / "Name;" then strip that segment.
        int firstSep = indexOfFirst(raw, ':', ',', ';');
        if (firstSep > 0) {
            String possibleName = raw.substring(0, firstSep).trim();
            if (!possibleName.isBlank()) {
                String possibleKey = normalizeKey(possibleName);
                for (MentionIntent i : intents) {
                    if (i instanceof MentionIntent.Unqualified u
                            && Objects.equals(normalizeKey(u.bot()), possibleKey)) {
                        return Optional.of(firstSep + 1);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static int indexOfFirst(String s, char... chars) {
        int best = -1;
        for (char c : chars) {
            int idx = s.indexOf(c);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }

    private static String trimTrailingPunctuation(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        int end = t.length();
        while (end > 0) {
            char c = t.charAt(end - 1);
            if (c == ',' || c == ':' || c == ';' || c == '!' || c == '?' || c == '.'
                    || java.lang.Character.isWhitespace(c)) {
                end--;
                continue;
            }
            break;
        }
        return t.substring(0, end).trim();
    }

    static boolean containsNameWithBoundaries(String lowerMsg, String lowerNeedle) {
        if (lowerMsg == null || lowerNeedle == null || lowerNeedle.isBlank()) {
            return false;
        }
        int from = 0;
        while (true) {
            int idx = lowerMsg.indexOf(lowerNeedle, from);
            if (idx < 0) {
                return false;
            }
            int end = idx + lowerNeedle.length();
            if (isBoundary(lowerMsg, idx - 1) && isBoundary(lowerMsg, end)) {
                return true;
            }
            from = idx + 1;
        }
    }

    private static boolean isBoundary(String s, int idx) {
        if (idx < 0 || idx >= s.length()) {
            return true;
        }
        char c = s.charAt(idx);
        return !java.lang.Character.isLetterOrDigit(c);
    }

    static String normalizeKey(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return Normalizer.normalize(trimmed, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}

