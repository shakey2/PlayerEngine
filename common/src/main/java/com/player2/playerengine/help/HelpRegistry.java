package com.player2.playerengine.help;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The single shared static registry of {@link HelpEntry} metadata, keyed by root then canonical
 * path. Both mods ({@code /playerengine} and {@code /player2npc}) contribute to and read from the
 * <b>one</b> instance — this is sound because Player2NPC runs against the pinned PlayerEngine jar
 * under a single classloader, so {@code com.player2.playerengine.help} is shared, not duplicated.
 *
 * <p>Registration is idempotent put-by-canonical-path so re-firing command registration (e.g. a
 * singleplayer world reload) does not duplicate entries. A root bucket may be {@link #clear(String)
 * cleared} at the start of a re-registration pass to drop entries for leaves that were removed.</p>
 *
 * <p>All access is {@code synchronized} on the class — registration happens on the server thread
 * during command-registration events and reads happen on the command/render thread.</p>
 */
public final class HelpRegistry {

    /** rootId -> (canonical path -> entry), preserving insertion order within each bucket. */
    private static final LinkedHashMap<String, LinkedHashMap<String, HelpEntry>> BY_ROOT = new LinkedHashMap<>();

    private HelpRegistry() {
    }

    /** Idempotent put-by-canonical-path. The map key is the entry's already-canonical path. */
    public static synchronized void register(HelpEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("HelpRegistry.register: entry must not be null");
        }
        BY_ROOT.computeIfAbsent(entry.rootId(), k -> new LinkedHashMap<>())
            .put(entry.path(), entry);
    }

    /** Drop all entries for a single root bucket (call at the start of a re-registration pass). */
    public static synchronized void clear(String rootId) {
        BY_ROOT.remove(rootId);
    }

    /** True if an entry exists for the given root and (normalized) path. */
    public static synchronized boolean has(String rootId, String path) {
        LinkedHashMap<String, HelpEntry> bucket = BY_ROOT.get(rootId);
        return bucket != null && bucket.containsKey(HelpPath.normalize(path));
    }

    /** The entry for the given root and (normalized) path, or {@code null} if none. */
    public static synchronized HelpEntry get(String rootId, String path) {
        LinkedHashMap<String, HelpEntry> bucket = BY_ROOT.get(rootId);
        return bucket == null ? null : bucket.get(HelpPath.normalize(path));
    }

    /**
     * All entries for a root, grouped by {@code category} (blank/null categories last) with
     * insertion order preserved within each category. The sort is stable, so within one category
     * entries appear in registration order. Returns an empty list for an unknown root.
     */
    public static synchronized List<HelpEntry> entriesFor(String rootId) {
        LinkedHashMap<String, HelpEntry> bucket = BY_ROOT.get(rootId);
        if (bucket == null) {
            return List.of();
        }
        List<HelpEntry> list = new ArrayList<>(bucket.values());
        list.sort(Comparator.comparing(e -> categorySortKey(e.category())));
        return list;
    }

    /** All known root ids, in first-registration order. */
    public static synchronized Set<String> roots() {
        return new LinkedHashSet<>(BY_ROOT.keySet());
    }

    private static String categorySortKey(String category) {
        // Blank/null categories sort last (max-char sentinel, no literal high char in source).
        return (category == null || category.isBlank()) ? String.valueOf(Character.MAX_VALUE) : category;
    }
}
