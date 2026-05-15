package com.player2.playerengine.util.sign;

import com.google.gson.JsonElement;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Serialize {@link Component} lines for LLM payloads: legacy-style § codes where practical plus
 * JSON from {@link Component.Serializer} as a lossless fallback.
 */
public final class SignTextFormatting {
    private static final Pattern AI_LINE_KEY_EQ = Pattern.compile("^(?i)(f[0-3]|b[0-3])=(.*)$");
    private static final Pattern AI_LINE_KEY_ONLY = Pattern.compile("^(?i)(f[0-3]|b[0-3])$");

    private SignTextFormatting() {
    }

    public static String componentToJson(Component c) {
        JsonElement el = Component.Serializer.toJsonTree(c);
        return el != null ? el.toString() : "\"\"";
    }

    public static String componentToLegacySection(Component component) {
        if (component == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        component.visit(
                (style, text) -> {
                    out.append(styleToLegacyPrefix(style));
                    out.append(text);
                    return Optional.empty();
                },
                Style.EMPTY);
        return out.toString();
    }

    private static String styleToLegacyPrefix(Style s) {
        StringBuilder p = new StringBuilder();
        TextColor tc = s.getColor();
        if (tc != null) {
            ChatFormatting lf = legacyColorForRgb(tc.getValue());
            if (lf != null) {
                p.append(lf);
            } else {
                p.append("§#").append(String.format("%06X", tc.getValue() & 0xFFFFFF));
            }
        }
        if (Boolean.TRUE.equals(s.isBold())) {
            p.append(ChatFormatting.BOLD);
        }
        if (Boolean.TRUE.equals(s.isItalic())) {
            p.append(ChatFormatting.ITALIC);
        }
        if (Boolean.TRUE.equals(s.isUnderlined())) {
            p.append(ChatFormatting.UNDERLINE);
        }
        if (Boolean.TRUE.equals(s.isStrikethrough())) {
            p.append(ChatFormatting.STRIKETHROUGH);
        }
        if (Boolean.TRUE.equals(s.isObfuscated())) {
            p.append(ChatFormatting.OBFUSCATED);
        }
        return p.toString();
    }

    /**
     * Parse a sign line for placement: tries JSON component first (starts with '[' or '{'),
     * otherwise parses inline § / §#RRGGBB codes into a {@link Component}.
     */
    public static Component parseLineToComponent(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        String t = raw.trim();
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                MutableComponent c = Component.Serializer.fromJson(t);
                return c != null ? c : Component.literal(raw);
            } catch (Exception ignored) {
                return Component.literal(raw);
            }
        }
        return parseLegacySectionToComponent(raw);
    }

    private static Component parseLegacySectionToComponent(String s) {
        MutableComponent root = Component.literal("");
        StringBuilder textBuf = new StringBuilder();
        final Style[] current = {Style.EMPTY};
        boolean[] had = {false};

        Runnable flush = () -> {
            if (textBuf.length() > 0) {
                MutableComponent piece = Component.literal(textBuf.toString());
                piece.withStyle(current[0]);
                root.append(piece);
                had[0] = true;
                textBuf.setLength(0);
            }
        };

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '§' && i + 1 < s.length()) {
                i++;
                char code = s.charAt(i);
                flush.run();
                if (code == 'r' || code == 'R') {
                    current[0] = Style.EMPTY;
                } else if (code == '#') {
                    if (i + 6 < s.length()) {
                        String hex = s.substring(i + 1, i + 7);
                        try {
                            int rgb = Integer.parseInt(hex, 16);
                            current[0] = current[0].withColor(TextColor.fromRgb(rgb));
                            i += 6;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                } else {
                    ChatFormatting cf = ChatFormatting.getByCode(code);
                    if (cf != null) {
                        current[0] = applyChatFormatting(current[0], cf);
                    }
                }
            } else {
                textBuf.append(ch);
            }
        }
        flush.run();
        return had[0] ? root : Component.empty();
    }

    private static ChatFormatting legacyColorForRgb(int rgb) {
        int rgb24 = rgb & 0xFFFFFF;
        for (ChatFormatting cf : ChatFormatting.values()) {
            if (!cf.isColor()) {
                continue;
            }
            Integer c = cf.getColor();
            if (c != null && (c & 0xFFFFFF) == rgb24) {
                return cf;
            }
        }
        return null;
    }

    private static Style applyChatFormatting(Style base, ChatFormatting cf) {
        if (cf == ChatFormatting.RESET) {
            return Style.EMPTY;
        }
        if (cf.isColor()) {
            Integer c = cf.getColor();
            if (c != null) {
                return base.withColor(TextColor.fromRgb(c));
            }
            return base;
        }
        if (cf == ChatFormatting.BOLD) {
            return base.withBold(true);
        }
        if (cf == ChatFormatting.ITALIC) {
            return base.withItalic(true);
        }
        if (cf == ChatFormatting.UNDERLINE) {
            return base.withUnderlined(true);
        }
        if (cf == ChatFormatting.STRIKETHROUGH) {
            return base.withStrikethrough(true);
        }
        if (cf == ChatFormatting.OBFUSCATED) {
            return base.withObfuscated(true);
        }
        return base;
    }

    public static Component[] fourLinesFromStrings(String[] lines) {
        Component[] out = new Component[4];
        for (int i = 0; i < 4; i++) {
            String line = i < lines.length ? lines[i] : "";
            out[i] = parseLineToComponent(normalizeAiLineSlotPrefix(line));
        }
        return out;
    }

    /**
     * Strips mistaken {@code f0=}/{@code f0} prefixes models sometimes leave in the line body (so §/JSON
     * formatting still applies to real text).
     */
    public static String normalizeAiLineSlotPrefix(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String t = raw.trim();
        Matcher m = AI_LINE_KEY_EQ.matcher(t);
        if (m.matches()) {
            return m.group(2).trim();
        }
        if (AI_LINE_KEY_ONLY.matcher(t).matches()) {
            return "";
        }
        return t;
    }
}
