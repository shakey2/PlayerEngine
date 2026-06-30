package com.player2.playerengine.help;

import java.util.List;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Leaf factory: returns a brigadier {@link LiteralArgumentBuilder} for a command leaf AND, as a
 * side effect, contributes the matching {@link HelpEntry} to the shared {@link HelpRegistry}. This
 * is the recommended path for a <b>literal-executable</b> leaf — you cannot obtain the builder
 * without supplying a non-blank {@code shortDescKey}, so help coverage cannot silently rot.
 *
 * <p>The returned builder is the leaf's own literal node (named from the last segment of
 * {@code helpPath}) with {@code .requires(hasPermission(permLevel))} pre-applied; the callsite
 * chains {@code .executes(...)} (and any argument children) onto it. For <b>argument-node</b> leaves
 * (where the executable node is an argument, e.g. {@code tpto <username>}) the callsite instead does
 * an explicit {@link HelpRegistry#register(HelpEntry)} on the parent literal.</p>
 *
 * <p><b>Authoring constraint (Layer-1 lint):</b> {@code helpPath}, {@code usage}, {@code shortDescKey},
 * {@code longDescKey}, {@code permNoteKey}, {@code category} and every {@link ArgNote} key must be
 * passed as plain double-quoted String literals at the callsite — no variables or concatenation.</p>
 */
public final class HelpfulCommand {

    private HelpfulCommand() {
    }

    /**
     * Minimal leaf: literal-executable, single-line summary, no detail paragraph / arg notes /
     * perm caveat / category.
     *
     * @param rootId       {@code "playerengine"} | {@code "player2npc"}.
     * @param helpPath     canonical English literal chain after the root (e.g. {@code "rag reload"}).
     * @param usage        full English usage including any argument placeholders. Never translated.
     * @param shortDescKey REQUIRED non-blank translatable key — the one-line index summary.
     * @param permLevel    {@code 0} | {@code 2}.
     * @return the leaf literal builder, with the {@link HelpEntry} already registered.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> leaf(
        String rootId,
        String helpPath,
        String usage,
        String shortDescKey,
        int permLevel
    ) {
        return leaf(rootId, helpPath, usage, shortDescKey, null, List.of(), permLevel, null, null);
    }

    /**
     * Full leaf: literal-executable with optional detail paragraph, per-argument notes, mixed-perm
     * caveat, and grouping category.
     *
     * @param rootId       {@code "playerengine"} | {@code "player2npc"}.
     * @param helpPath     canonical English literal chain after the root.
     * @param usage        full English usage including argument placeholders. Never translated.
     * @param shortDescKey REQUIRED non-blank translatable key — the one-line index summary.
     * @param longDescKey  {@code @Nullable} translatable detail paragraph key (falls back to short).
     * @param argNotes     per-argument notes ({@code List.of()} when none; never null).
     * @param permLevel    {@code 0} | {@code 2}.
     * @param permNoteKey  {@code @Nullable} translatable mixed-context caveat key.
     * @param category     {@code @Nullable} English grouping token (section/sort only).
     * @return the leaf literal builder, with the {@link HelpEntry} already registered.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> leaf(
        String rootId,
        String helpPath,
        String usage,
        String shortDescKey,
        String longDescKey,
        List<ArgNote> argNotes,
        int permLevel,
        String permNoteKey,
        String category
    ) {
        if (shortDescKey == null || shortDescKey.isBlank()) {
            throw new IllegalArgumentException(
                "HelpfulCommand.leaf: shortDescKey must be a non-blank translatable key (path=" + helpPath + ")");
        }
        String canonical = HelpPath.normalize(helpPath);
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException("HelpfulCommand.leaf: helpPath must be non-blank");
        }
        String[] segments = canonical.split(" ");
        String literalName = segments[segments.length - 1];

        HelpRegistry.register(new HelpEntry(
            rootId, canonical, usage, shortDescKey, longDescKey, argNotes, permLevel, permNoteKey, category));

        return Commands.literal(literalName).requires(source -> source.hasPermission(permLevel));
    }
}
