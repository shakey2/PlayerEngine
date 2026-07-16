package com.player2.playerengine.tasks.farming;

import net.minecraft.core.BlockPos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable facts read for one candidate; also the exact pre-CLEAR revalidation contract. */
public final class FarmSiteSnapshot {
    private final BlockPos center;
    private final boolean explicit;
    private final FarmSiteWorldView.WorldObservation worldObservation;
    private final Map<BlockPos, FarmSiteWorldView.CellObservation> observations;
    private final String fingerprint;

    public FarmSiteSnapshot(
            BlockPos center,
            boolean explicit,
            FarmSiteWorldView.WorldObservation worldObservation,
            Map<BlockPos, FarmSiteWorldView.CellObservation> observations) {
        this.center = Objects.requireNonNull(center, "center").immutable();
        this.explicit = explicit;
        this.worldObservation = Objects.requireNonNull(worldObservation, "worldObservation");
        Objects.requireNonNull(observations, "observations");
        LinkedHashMap<BlockPos, FarmSiteWorldView.CellObservation> copy = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, FarmSiteWorldView.CellObservation> entry : observations.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "observation position").immutable(),
                    Objects.requireNonNull(entry.getValue(), "cell observation"));
        }
        List<BlockPos> expectedOrder = FarmPlotGeometry.scanEnvelope(this.center);
        if (copy.size() != expectedOrder.size()
                || !new ArrayList<>(copy.keySet()).equals(expectedOrder)) {
            throw new IllegalArgumentException("snapshot must contain the complete ordered scan envelope");
        }
        this.observations = Collections.unmodifiableMap(copy);
        this.fingerprint = fingerprint(this.center, explicit, worldObservation, copy);
    }

    public BlockPos center() {
        return center;
    }

    public boolean explicit() {
        return explicit;
    }

    public FarmSiteWorldView.WorldObservation worldObservation() {
        return worldObservation;
    }

    public FarmSiteWorldView.CellObservation observation(BlockPos position) {
        return observations.get(position);
    }

    public List<BlockPos> positions() {
        return List.copyOf(observations.keySet());
    }

    public Map<BlockPos, FarmSiteWorldView.CellObservation> observations() {
        return observations;
    }

    public String fingerprint() {
        return fingerprint;
    }

    /** Uses exact fact equality; the digest is diagnostic and never the sole safety decision. */
    public boolean sameFacts(FarmSiteSnapshot other) {
        return other != null
                && center.equals(other.center)
                && explicit == other.explicit
                && worldObservation.equals(other.worldObservation)
                && observations.equals(other.observations);
    }

    private static String fingerprint(
            BlockPos center,
            boolean explicit,
            FarmSiteWorldView.WorldObservation world,
            LinkedHashMap<BlockPos, FarmSiteWorldView.CellObservation> observations) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, center.getX() + "," + center.getY() + "," + center.getZ());
            update(digest, explicit ? "explicit" : "auto");
            update(digest, world.toString());
            for (Map.Entry<BlockPos, FarmSiteWorldView.CellObservation> entry : observations.entrySet()) {
                BlockPos position = entry.getKey();
                FarmSiteWorldView.CellObservation facts = entry.getValue();
                update(digest, position.getX() + "," + position.getY() + "," + position.getZ());
                update(digest, facts.toString());
            }
            byte[] bytes = digest.digest();
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                out.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                out.append(Character.forDigit(value & 0x0f, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
