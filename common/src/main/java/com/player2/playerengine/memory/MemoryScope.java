package com.player2.playerengine.memory;

import java.util.Objects;
import java.util.UUID;

/**
 * Opaque per-companion memory-store key (Phase D, W2).
 *
 * <p>v1 is strictly <b>private</b>: a memory graph belongs to one {@code (ownerUuid, companionId)}
 * pair. The scope is the seam that lets a future shared-lore graph be an <em>additive</em> scope
 * (a different scope kind) without changing any call site that already keys on this type.
 *
 * <p>{@code ownerUuid} may be {@code null} when the owner UUID is unresolvable; in that case the
 * store falls back to the entity-scoped path layout (mirroring conversation history). The
 * {@code companionId} (character id) is always required.
 *
 * <p>No Minecraft, loader, or I/O dependency.
 */
public final class MemoryScope {

    private final UUID ownerUuid;     // nullable — see fallback layout in MemoryStore
    private final UUID entityUuid;    // fallback key when ownerUuid is null
    private final String companionId;

    private MemoryScope(UUID ownerUuid, UUID entityUuid, String companionId) {
        this.ownerUuid = ownerUuid;
        this.entityUuid = entityUuid;
        this.companionId = Objects.requireNonNull(companionId, "companionId");
    }

    /** Owner-resolved scope: {@code owners/<ownerUuid>/<companionId>/memory/}. */
    public static MemoryScope of(UUID ownerUuid, String companionId) {
        return new MemoryScope(Objects.requireNonNull(ownerUuid, "ownerUuid"), null, companionId);
    }

    /**
     * Owner-unresolvable fallback scope: {@code <entityUuid>/<companionId>/memory/}
     * (mirrors the conversation-history fallback layout).
     */
    public static MemoryScope ofEntityFallback(UUID entityUuid, String companionId) {
        return new MemoryScope(null, Objects.requireNonNull(entityUuid, "entityUuid"), companionId);
    }

    /** True iff this scope resolved a real owner UUID (canonical layout). */
    public boolean hasOwner() {
        return ownerUuid != null;
    }

    /** The owner UUID, or {@code null} when this is an entity-fallback scope. */
    public UUID ownerUuid() {
        return ownerUuid;
    }

    /** The entity UUID used for the fallback layout, or {@code null} when owner-resolved. */
    public UUID entityUuid() {
        return entityUuid;
    }

    public String companionId() {
        return companionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MemoryScope)) return false;
        MemoryScope that = (MemoryScope) o;
        return Objects.equals(ownerUuid, that.ownerUuid)
                && Objects.equals(entityUuid, that.entityUuid)
                && companionId.equals(that.companionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ownerUuid, entityUuid, companionId);
    }

    @Override
    public String toString() {
        return "MemoryScope{" + (ownerUuid != null ? "owner=" + ownerUuid : "entity=" + entityUuid)
                + ", companionId='" + companionId + "'}";
    }
}
