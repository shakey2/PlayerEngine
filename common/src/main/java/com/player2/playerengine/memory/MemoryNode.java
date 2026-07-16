package com.player2.playerengine.memory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Immutable value type for a single memory-graph node — the frozen schema contract every
 * downstream workstream binds to.
 *
 * <p><b>Field-union ownership</b> (Phase D plan §"Frozen MemoryNode field union"): W1 declares
 * the complete record up front so the JSON shape is frozen; W2 adds the graph mutation/store
 * mechanics around it and W6 the importance/retrieval-recency scoring. The full set:
 * <ul>
 *   <li>{@code id}              — stable opaque id; graph map key (W1)</li>
 *   <li>{@code content}         — hard-capped at WRITE TIME (the egress boundary) (W1)</li>
 *   <li>{@code type}            — lenient CHARACTER/PLACE/EVENT/FACTION/ITEM/FACT/PREFERENCE string (W2)</li>
 *   <li>{@code canonicalName}   — dedupe/merge key (W2)</li>
 *   <li>{@code aliases}         — merged alias set (W2)</li>
 *   <li>{@code tags}            — topic/entity-type tags (W1)</li>
 *   <li>{@code importance}      — 1–10, 0 = unscored (W1/W6)</li>
 *   <li>{@code createdTick} / {@code timestampMs} — provenance/recency (W1/W2)</li>
 *   <li>{@code lastSeenTick}    — recency basis (W2)</li>
 *   <li>{@code lastRetrievedTick} — retrieval-recency basis (W5 stamps, W6 scores)</li>
 *   <li>{@code mentionCount}    — compaction signal (W2)</li>
 *   <li>{@code vector} / {@code vectorModel} — additive embeddings seam; null/omitted in v1 (W1/W8)</li>
 *   <li>{@code schemaVersion}   — node-level forward-compat (W1)</li>
 * </ul>
 *
 * <p>This type carries <b>no</b> Minecraft, loader, or I/O dependency. String capping is the
 * responsibility of the write path (W2's {@code MemoryCaps} at merge time) and of
 * {@link MemoryNodeCodec} at serialize time; this value type stores whatever it is handed.
 * Array-typed fields are defensively copied in and out; accessor lists are unmodifiable.
 */
public final class MemoryNode {

    /** Current node-level schema version. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final String id;
    private final String content;
    private final String type;
    private final String canonicalName;
    private final String[] aliases;
    private final String[] tags;
    private final int importance;
    private final long createdTick;
    private final long timestampMs;
    private final long lastSeenTick;
    private final long lastRetrievedTick;
    private final int mentionCount;
    private final float[] vector;       // nullable — omitted from JSON when null
    private final String vectorModel;   // nullable
    private final int schemaVersion;

    /**
     * Full canonical constructor. {@code aliases}/{@code tags} null is treated as empty;
     * {@code vector}/{@code vectorModel} null means "no embedding" (v1 default). Array inputs
     * are defensively copied.
     */
    public MemoryNode(String id,
                      String content,
                      String type,
                      String canonicalName,
                      String[] aliases,
                      String[] tags,
                      int importance,
                      long createdTick,
                      long timestampMs,
                      long lastSeenTick,
                      long lastRetrievedTick,
                      int mentionCount,
                      float[] vector,
                      String vectorModel,
                      int schemaVersion) {
        this.id = id;
        this.content = content;
        this.type = type;
        this.canonicalName = canonicalName;
        this.aliases = aliases == null ? new String[0] : aliases.clone();
        this.tags = tags == null ? new String[0] : tags.clone();
        this.importance = importance;
        this.createdTick = createdTick;
        this.timestampMs = timestampMs;
        this.lastSeenTick = lastSeenTick;
        this.lastRetrievedTick = lastRetrievedTick;
        this.mentionCount = mentionCount;
        this.vector = vector == null ? null : vector.clone();
        this.vectorModel = vectorModel;
        this.schemaVersion = schemaVersion;
    }

    public String id()                 { return id; }
    public String content()            { return content; }
    public String type()               { return type; }
    public String canonicalName()      { return canonicalName; }
    public List<String> aliases()      { return Collections.unmodifiableList(Arrays.asList(aliases)); }
    public List<String> tags()         { return Collections.unmodifiableList(Arrays.asList(tags)); }
    public int importance()            { return importance; }
    public long createdTick()          { return createdTick; }
    public long timestampMs()          { return timestampMs; }
    public long lastSeenTick()         { return lastSeenTick; }
    public long lastRetrievedTick()    { return lastRetrievedTick; }
    public int mentionCount()          { return mentionCount; }
    /** Nullable embedding vector (always null in v1); defensively copied. */
    public float[] vector()            { return vector == null ? null : vector.clone(); }
    /** True iff this node carries a non-null embedding vector. */
    public boolean hasVector()         { return vector != null; }
    public String vectorModel()        { return vectorModel; }
    public int schemaVersion()         { return schemaVersion; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MemoryNode)) return false;
        MemoryNode that = (MemoryNode) o;
        return importance == that.importance
                && createdTick == that.createdTick
                && timestampMs == that.timestampMs
                && lastSeenTick == that.lastSeenTick
                && lastRetrievedTick == that.lastRetrievedTick
                && mentionCount == that.mentionCount
                && schemaVersion == that.schemaVersion
                && java.util.Objects.equals(id, that.id)
                && java.util.Objects.equals(content, that.content)
                && java.util.Objects.equals(type, that.type)
                && java.util.Objects.equals(canonicalName, that.canonicalName)
                && Arrays.equals(aliases, that.aliases)
                && Arrays.equals(tags, that.tags)
                && Arrays.equals(vector, that.vector)
                && java.util.Objects.equals(vectorModel, that.vectorModel);
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(id, content, type, canonicalName, importance,
                createdTick, timestampMs, lastSeenTick, lastRetrievedTick, mentionCount,
                vectorModel, schemaVersion);
        result = 31 * result + Arrays.hashCode(aliases);
        result = 31 * result + Arrays.hashCode(tags);
        result = 31 * result + Arrays.hashCode(vector);
        return result;
    }

    @Override
    public String toString() {
        return "MemoryNode{id='" + id + "', canonicalName='" + canonicalName
                + "', type='" + type + "', importance=" + importance
                + ", mentionCount=" + mentionCount + ", hasVector=" + (vector != null) + "}";
    }
}
