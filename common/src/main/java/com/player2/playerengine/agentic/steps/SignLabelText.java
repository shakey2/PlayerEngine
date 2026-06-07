package com.player2.playerengine.agentic.steps;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.sign.SignTextFormatting;
import net.minecraft.network.chat.Component;

/**
 * Builds the four-line {@code Component[]} front face for a chest label sign from plain strings.
 *
 * <p>This isolates the ONE cross-branch divergence in C3's label code: in 1.21.1
 * {@code SignTextFormatting.fourLinesFromStrings(String[], net.minecraft.core.HolderLookup.Provider)}
 * requires the world's registry access; in 1.20.1 the same helper takes only the {@code String[]}.
 * Keeping the call here means the per-branch difference lives in exactly one place.
 */
public final class SignLabelText {

    private SignLabelText() {}

    /**
     * Convert up to four label strings into sign {@link Component} lines. Missing entries become
     * empty lines. 1.20.1 uses the no-registries overload.
     */
    public static Component[] frontLines(PlayerEngineController mod, String[] lines) {
        String[] four = new String[] {
            safe(lines, 0),
            safe(lines, 1),
            safe(lines, 2),
            safe(lines, 3)
        };
        // 1.20.1 overload: SignTextFormatting.fourLinesFromStrings takes only the String[] (no
        // HolderLookup.Provider). The 1.21.1 sibling passes mod.getWorld().registryAccess() here.
        return SignTextFormatting.fourLinesFromStrings(four);
    }

    /** Empty back face (labels are written front-only). */
    public static Component[] emptyBack() {
        return new Component[] {Component.empty(), Component.empty(), Component.empty(), Component.empty()};
    }

    private static String safe(String[] lines, int i) {
        if (lines == null || i >= lines.length || lines[i] == null) {
            return "";
        }
        return lines[i];
    }
}
