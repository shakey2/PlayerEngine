package com.player2.playerengine.help;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.tree.CommandNode;

import com.player2.playerengine.automaton.api.command.helpers.Paginator;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;

/**
 * Renders the help index and per-command detail views against a {@link CommandSourceStack} (so the
 * server console can run help — never {@code getPlayerOrException}). Reads from the shared
 * {@link HelpRegistry}; translates only human prose (description / arg-note / perm-note keys) and
 * keeps usage strings and command tokens English.
 *
 * <p>The index uses {@link Paginator#display} (accepting its {@code "--"} pad rows and always-on
 * nav row — the intended index UX). Detail renders flat with NO padding and NO nav row, falling
 * back to a paginator only for a genuinely overflowing family list.</p>
 *
 * <p>Egress invariant: the "unknown subcommand" reply is a short bounded translatable message and
 * never echoes raw user input.</p>
 */
public final class HelpRenderer {

    /** The single help page size for both roots and both MC lines (NOT a config knob). */
    public static final int PAGE_SIZE = 7;

    private HelpRenderer() {
    }

    /**
     * Render the paginated command index for a root, filtered to the leaves the caller can actually
     * run (by the live brigadier requirement, falling back to the static {@code permLevel}).
     *
     * @param rootId {@code "playerengine"} | {@code "player2npc"}.
     * @param page   1-based page number (clamped into range).
     * @param source the command source to render to (player or console).
     */
    public static void index(String rootId, int page, CommandSourceStack source) {
        List<HelpEntry> visible = new ArrayList<>();
        for (HelpEntry entry : HelpRegistry.entriesFor(rootId)) {
            if (canRun(source, entry)) {
                visible.add(entry);
            }
        }

        if (visible.isEmpty()) {
            // Nothing this source may run — send one informative line instead of a page of "--" pads.
            source.sendSuccess(
                () -> Component.translatable("help." + rootId + ".index_empty").withStyle(ChatFormatting.GRAY),
                false);
            return;
        }

        source.sendSuccess(
            () -> Component.translatable("help." + rootId + ".index_header").withStyle(ChatFormatting.GOLD),
            false);

        Paginator<HelpEntry> paginator = new Paginator<>(source, visible).setPageSize(PAGE_SIZE);
        int clamped = Math.max(1, Math.min(page, paginator.getMaxPage()));
        paginator.skipPages(clamped - 1);
        paginator.display(HelpRenderer::rowComponent, "/" + rootId + " help");
    }

    /**
     * Render detail for a command path. An exact path match shows that leaf flat; a path that is the
     * prefix of several leaves shows the family list (paginated only on overflow); no match sends a
     * short bounded "unknown subcommand" message.
     *
     * @param rootId {@code "playerengine"} | {@code "player2npc"}.
     * @param path   the English command path the player typed (greedy {@code <command>} argument).
     * @param page   1-based page number for the overflow family case (clamped into range).
     * @param source the command source to render to (player or console).
     */
    public static void detail(String rootId, String path, int page, CommandSourceStack source) {
        String canonical = HelpPath.normalize(path);

        HelpEntry exact = HelpRegistry.get(rootId, canonical);
        if (exact != null) {
            renderLeaf(exact, source);
            return;
        }

        // Family match: every entry whose path is the requested prefix.
        String prefix = canonical + " ";
        List<HelpEntry> family = new ArrayList<>();
        for (HelpEntry entry : HelpRegistry.entriesFor(rootId)) {
            if (entry.path().startsWith(prefix) && canRun(source, entry)) {
                family.add(entry);
            }
        }

        if (family.isEmpty()) {
            source.sendFailure(Component.translatable("help." + rootId + ".unknown"));
            return;
        }

        if (family.size() == 1) {
            renderLeaf(family.get(0), source);
            return;
        }

        if (family.size() <= PAGE_SIZE) {
            // Flat: no padding, no nav row.
            for (HelpEntry entry : family) {
                source.sendSuccess(() -> rowComponent(entry), false);
            }
            return;
        }

        // Genuine overflow: paginate the family list.
        Paginator<HelpEntry> paginator = new Paginator<>(source, family).setPageSize(PAGE_SIZE);
        int clamped = Math.max(1, Math.min(page, paginator.getMaxPage()));
        paginator.skipPages(clamped - 1);
        paginator.display(HelpRenderer::rowComponent, "/" + rootId + " help " + canonical);
    }

    /** A single index/family row: English usage (gold) + " - " + translated short summary (gray). */
    private static Component rowComponent(HelpEntry entry) {
        return Component.literal(entry.usage()).withStyle(ChatFormatting.GOLD)
            .append(Component.literal(" - ").withStyle(ChatFormatting.GRAY))
            .append(Component.translatable(entry.shortDescKey()).withStyle(ChatFormatting.GRAY));
    }

    /** Render a single leaf flat: usage headline + paragraph + arg notes + perm caveat. */
    private static void renderLeaf(HelpEntry entry, CommandSourceStack source) {
        source.sendSuccess(
            () -> Component.literal(entry.usage()).withStyle(ChatFormatting.GOLD),
            false);

        String descKey = (entry.longDescKey() != null && !entry.longDescKey().isBlank())
            ? entry.longDescKey()
            : entry.shortDescKey();
        source.sendSuccess(
            () -> Component.translatable(descKey).withStyle(ChatFormatting.GRAY),
            false);

        for (ArgNote note : entry.argNotes()) {
            source.sendSuccess(() -> {
                MutableComponent line = Component.literal(note.argName() + ": ").withStyle(ChatFormatting.YELLOW);
                line.append(Component.translatable(note.noteKey()).withStyle(ChatFormatting.GRAY));
                return line;
            }, false);
        }

        if (entry.permNoteKey() != null && !entry.permNoteKey().isBlank()) {
            source.sendSuccess(
                () -> Component.translatable(entry.permNoteKey()).withStyle(ChatFormatting.RED),
                false);
        }
    }

    /**
     * Whether the caller may run this leaf. Walks the full path root -&gt; leaf and ANDs every node's
     * live brigadier requirement, because access restrictions commonly sit on a PARENT literal (e.g.
     * {@code rag}, {@code routing}, {@code capability}, {@code memory}, {@code storage op} all carry
     * {@code .requires(hasPermission(2))} while their executable leaf carries only the Brigadier
     * default predicate). Testing the leaf alone would let a non-OP see OP-restricted commands in the
     * index. Falls back to the static {@code permLevel} only when the live tree is unavailable.
     */
    private static boolean canRun(CommandSourceStack source, HelpEntry entry) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return source.hasPermission(entry.permLevel());
        }
        CommandNode<CommandSourceStack> node =
            server.getCommands().getDispatcher().getRoot().getChild(entry.rootId());
        if (node == null) {
            return source.hasPermission(entry.permLevel());
        }
        if (!testRequirement(node, source)) {
            return false;
        }
        String canonical = HelpPath.normalize(entry.path());
        if (!canonical.isEmpty()) {
            for (String segment : canonical.split(" ")) {
                CommandNode<CommandSourceStack> child = node.getChild(segment);
                if (child == null) {
                    // Arg-node leaf: the deepest resolvable literal (and all of its ancestors) has
                    // already been requirement-tested above; the executable arg child carries no
                    // stricter requirement, so stop here.
                    break;
                }
                node = child;
                if (!testRequirement(node, source)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Test a single node's brigadier requirement; a requirement that throws is treated as not runnable. */
    private static boolean testRequirement(CommandNode<CommandSourceStack> node, CommandSourceStack source) {
        try {
            return node.getRequirement().test(source);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
