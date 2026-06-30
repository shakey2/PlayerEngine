package com.player2.playerengine.help;

import java.util.List;

/**
 * Immutable curated help metadata for a single brigadier slash-command leaf.
 *
 * <p>Contributed at the leaf's registration site (via {@link HelpfulCommand#leaf} for literal-
 * executable leaves, or via an explicit {@link HelpRegistry#register(HelpEntry)} for argument-node
 * leaves), stored in the shared {@link HelpRegistry}, rendered by {@link HelpRenderer}, and checked
 * by {@link HelpCoverageVerifier}.</p>
 *
 * <p><b>Localization split:</b> only {@code shortDescKey}, {@code longDescKey},
 * {@code argNotes[].noteKey} and {@code permNoteKey} are human prose behind
 * {@code Component.translatable} keys. {@code rootId}, {@code path}, {@code usage} and
 * {@code category} are English structural/command tokens and are NEVER translated.</p>
 *
 * <p>{@code path} is canonicalized through {@link HelpPath} (space-joined English literal chain
 * after the root) by the compact constructor, so a registration site and a lookup can never drift.</p>
 *
 * @param rootId      {@code "playerengine"} | {@code "player2npc"} — registry bucket, help command
 *                    prefix, and which mod's lang file the keys resolve against. English.
 * @param path        canonical space-joined literal chain AFTER the root (e.g. {@code "rag retrieve"},
 *                    {@code "user-settings auto-respawn on"}). English; canonicalized via HelpPath.
 * @param usage       full English usage including argument placeholders (e.g.
 *                    {@code "rag retrieve <query> [--category <cat>]"}). NEVER translated.
 * @param shortDescKey REQUIRED non-blank translatable key — the one-line index summary.
 * @param longDescKey {@code @Nullable} translatable key — the detail paragraph; falls back to
 *                    {@code shortDescKey} when null/blank.
 * @param argNotes    per-argument notes (never null; empty list when none).
 * @param permLevel   {@code 0} | {@code 2} strict baseline permission level; the index also filters
 *                    by the live brigadier requirement.
 * @param permNoteKey {@code @Nullable} translatable caveat for mixed-context leaves (e.g.
 *                    dedicated + server-override refusal).
 * @param category    {@code @Nullable} English grouping token — section/sort only, never translated.
 */
public record HelpEntry(
    String rootId,
    String path,
    String usage,
    String shortDescKey,
    String longDescKey,
    List<ArgNote> argNotes,
    int permLevel,
    String permNoteKey,
    String category
) {

    public HelpEntry {
        if (rootId == null || rootId.isBlank()) {
            throw new IllegalArgumentException("HelpEntry.rootId must be non-blank");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("HelpEntry.path must be non-blank");
        }
        if (usage == null || usage.isBlank()) {
            throw new IllegalArgumentException("HelpEntry.usage must be non-blank (English, never translated)");
        }
        if (shortDescKey == null || shortDescKey.isBlank()) {
            throw new IllegalArgumentException("HelpEntry.shortDescKey must be a non-blank translatable key");
        }
        path = HelpPath.normalize(path);
        argNotes = (argNotes == null) ? List.of() : List.copyOf(argNotes);
    }
}
