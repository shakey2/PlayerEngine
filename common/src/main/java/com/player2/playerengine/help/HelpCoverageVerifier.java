package com.player2.playerengine.help;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

import com.player2.playerengine.util.Debug;

import net.minecraft.commands.CommandSourceStack;

/**
 * Walks the live merged brigadier dispatcher at server start to guarantee every user-facing leaf of
 * {@code /playerengine} and {@code /player2npc} has a curated {@link HelpEntry} — the no-backlog
 * authority (the pre-build PowerShell lint is only a cheap early gate).
 *
 * <p><b>Traversal rule (full recursion, count-on-literal):</b> the walk ALWAYS recurses into the
 * children of every node, including every {@link ArgumentCommandNode}. It only ever COUNTS a node as
 * a coverage-required leaf when that node is a {@link LiteralCommandNode} meeting the leaf rule:</p>
 * <ul>
 *   <li>a literal with {@code getCommand() != null}, OR</li>
 *   <li>a literal whose every direct child is an executable {@link ArgumentCommandNode}
 *       ({@code getCommand() != null}).</li>
 * </ul>
 *
 * <p>Argument nodes are never counted, so the greedy {@code <command>} relay (an arg node with no
 * literal descendants) is excluded automatically — without halting traversal, so executable literal
 * leaves nested <i>beneath</i> an argument node (e.g.
 * {@code /player2npc storage op delete id <arg> player}) are still reached and counted.</p>
 *
 * <p><b>Known structural exceptions (see {@code EXPLICIT_REQUIRED}):</b> a few entries whose head
 * literal mixes an executable arg child with literal children ({@code rag retrieve},
 * {@code routing probe}) or whose executable node is two arg hops down ({@code capability inspect})
 * have no literal the walk can count at the collapsed entry's path. Those are intentional
 * variant-collapse exceptions (plan decision 6) and are verified by explicit path assertion instead,
 * so the no-backlog guarantee still holds for them.</p>
 *
 * <p>Under {@code -Dplayerengine.help.strict} (default ON in dev/CI/test-bed launch) any miss throws
 * {@link IllegalStateException}, crashing server start so gaps cannot ship. Without the flag
 * (production) each miss is a {@link Debug#logWarning} and the server continues.</p>
 */
public final class HelpCoverageVerifier {

    private static final String[] ROOTS = {"playerengine", "player2npc"};

    /**
     * Entries whose head literal the structural walk cannot count as a leaf, so they are verified by
     * explicit path assertion instead. Two shapes land here:
     * <ul>
     *   <li><b>2-hop argument tail</b> — {@code capability inspect <kind> <id>}: the executable node
     *       is two argument hops below the literal, so {@code inspect}'s only direct child is a
     *       non-executable arg and the leaf rule's "every direct child is an executable arg" test
     *       fails — the walk finds no countable literal at {@code capability inspect}.</li>
     *   <li><b>Mixed arg+literal children (variant collapse, plan decision 6)</b> —
     *       {@code rag retrieve} (direct {@code <goal>} arg PLUS a {@code --category} literal chain)
     *       and {@code routing probe} (direct {@code <taskClass>} arg PLUS {@code --simulate-*}
     *       literal flags): the head literal has both an executable arg child and literal children, so
     *       neither leaf rule applies and the walk finds no countable literal at the collapsed
     *       entry's path.</li>
     * </ul>
     * Listing these here keeps the no-backlog guarantee — deleting one of their {@link HelpEntry}
     * contributions still fails startup — WITHOUT distorting the structural walk, which the nested
     * {@code --category} / {@code --simulate-joules} chains depend on for their own counted leaves
     * (extending the leaf rule to recurse arg tails would spuriously count those nested flag literals
     * at unregistered paths). Add any future mixed/2-hop-arg collapsed entry here.
     */
    private static final Map<String, List<String>> EXPLICIT_REQUIRED = Map.of(
        "playerengine", List.of("capability inspect", "rag retrieve", "routing probe"));

    private HelpCoverageVerifier() {
    }

    /**
     * Verify coverage of both command roots against the live dispatcher. Skips a root that is absent
     * (e.g. running PlayerEngine without Player2NPC).
     */
    public static void verify(CommandDispatcher<CommandSourceStack> dispatcher) {
        boolean strict = Boolean.getBoolean("playerengine.help.strict");
        List<String> misses = new ArrayList<>();

        for (String rootId : ROOTS) {
            CommandNode<CommandSourceStack> rootNode = dispatcher.getRoot().getChild(rootId);
            if (rootNode == null) {
                continue;
            }
            if (strict) {
                // Classloader-split canary: in dev/CI/test-bed (strict) surface the per-root registry
                // size so a split HelpRegistry (entries invisible to this verifier) is immediately
                // visible. Suppressed in production to avoid two warning-level lines on every clean
                // boot — the per-miss warnings below still fire if an actual gap exists. logWarning is
                // the correct level here (Debug level-0 logs are suppressed).
                Debug.logWarning("HelpCoverageVerifier: root '%s' has %d registered help entries",
                    rootId, HelpRegistry.entriesFor(rootId).size());
            }
            for (CommandNode<CommandSourceStack> child : rootNode.getChildren()) {
                walk(rootId, child, new ArrayList<>(), misses);
            }
        }

        // Explicit-required leaves the structural walk cannot count (2-hop-arg / mixed variant-collapse
        // heads). Verified by direct registry assertion so deleting their HelpEntry still fails startup.
        for (Map.Entry<String, List<String>> required : EXPLICIT_REQUIRED.entrySet()) {
            String rootId = required.getKey();
            if (dispatcher.getRoot().getChild(rootId) == null) {
                continue;
            }
            for (String path : required.getValue()) {
                if (!HelpRegistry.has(rootId, path)) {
                    misses.add(rootId + " " + path);
                }
            }
        }

        if (misses.isEmpty()) {
            return;
        }

        if (strict) {
            throw new IllegalStateException(
                "Help coverage gaps (" + misses.size() + " undocumented leaf/leaves): " + String.join("; ", misses));
        }
        for (String miss : misses) {
            Debug.logWarning("HelpCoverageVerifier: undocumented command leaf: %s", miss);
        }
    }

    /**
     * Recursively walk a node, accumulating the literal-name trail after the root. Always recurses;
     * only counts literal leaves meeting the leaf rule.
     */
    private static void walk(String rootId, CommandNode<CommandSourceStack> node, List<String> parentTrail, List<String> misses) {
        List<String> trail = parentTrail;
        if (node instanceof LiteralCommandNode) {
            trail = new ArrayList<>(parentTrail);
            trail.add(node.getName());
            if (isCountedLeaf(node)) {
                String path = HelpPath.of(trail);
                if (!HelpRegistry.has(rootId, path)) {
                    misses.add(rootId + " " + path);
                }
            }
        }
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            walk(rootId, child, trail, misses);
        }
    }

    /** The leaf rule: an executable literal, or a literal whose every direct child is an executable arg node. */
    private static boolean isCountedLeaf(CommandNode<CommandSourceStack> node) {
        if (!(node instanceof LiteralCommandNode)) {
            return false;
        }
        if (node.getCommand() != null) {
            return true;
        }
        List<CommandNode<CommandSourceStack>> children = new ArrayList<>(node.getChildren());
        if (children.isEmpty()) {
            return false;
        }
        for (CommandNode<CommandSourceStack> child : children) {
            if (!(child instanceof ArgumentCommandNode) || child.getCommand() == null) {
                return false;
            }
        }
        return true;
    }
}
