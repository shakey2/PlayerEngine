package com.player2.playerengine.memory.dense;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.memory.MemoryGraph;
import com.player2.playerengine.memory.MemoryNode;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A per-companion int8-quantized dense-vector sidecar next to {@code graph.json} (W8e).
 *
 * <p>The graph JSON remains the source of truth for node vectors (the codec round-trips them); this
 * {@code dense-index.bin} is a <b>fast-load quantized mirror</b> so retrieval does not re-parse /
 * re-quantize every load. It is an optimization, never a correctness dependency — an absent / corrupt
 * / version-mismatched {@code .bin} simply rebuilds in-memory from the JSON node vectors.
 *
 * <h3>Path</h3>
 * The {@code .bin} path is derived from {@code MemoryStore}'s already-resolved {@code graph.json} path
 * ({@code graphPath.resolveSibling(FILE_NAME)}), <b>never</b> a fresh {@code LevelResource} /
 * world-path call (the known 1.20.1↔1.21.1 world-path divergence risk — this keeps the class pure
 * common-module).
 *
 * <h3>Version token — model identity ONLY</h3>
 * The {@code versionToken} folds only {@link EmbeddingModel#vectorModelToken()} (frozen model + dim),
 * NOT the graph node set — folding the node set would make the token corpus-dependent and force a
 * rebuild every load. Node-set membership is tracked separately by the per-node {@code hasVector()} /
 * {@code vectorModel} scan (backfill). This is a DIFFERENT token from
 * {@code MemoryStore.computeVersionToken} (the graph-integrity SHA). On load, a {@code formatVer} /
 * {@code versionToken} / {@code dim} mismatch discards the {@code .bin} (rebuild + re-embed path).
 *
 * <h3>Binary layout (big-endian — Java {@link DataOutputStream} standard)</h3>
 * <pre>
 *   magic        : 4 bytes  "PDMI"
 *   formatVer    : int      1
 *   versionToken : UTF      "player2:" + FROZEN + ":" + FROZEN_DIMS  (model identity only)
 *   dim          : int      1536
 *   count        : int      N
 *   backfillDone : byte     0|1
 *   --- per node (N times) ---
 *   nodeId       : UTF
 *   quantScale   : float    symmetric int8 scale (maxAbs/127)
 *   qvector      : byte[dim] int8-quantized components
 * </pre>
 *
 * <h3>Off-tick</h3>
 * {@link #save} (quantize + write) and cosine scanning run off the tick (on
 * {@code MemoryLlmClient.MEMORY_EXECUTOR} / the flush cadence), never on the game thread.
 */
public final class DenseIndexState {

    /** Sibling file name next to {@code graph.json}. */
    public static final String FILE_NAME = "dense-index.bin";

    private static final int MAGIC = 0x50444D49; // "PDMI"
    private static final int FORMAT_VERSION = 1;

    /** nodeId -> dequantized float[] (dim == FROZEN_DIMS). Insertion-ordered for stable iteration. */
    private final Map<String, float[]> vectors;
    private boolean backfillComplete;

    private DenseIndexState(Map<String, float[]> vectors, boolean backfillComplete) {
        this.vectors = vectors;
        this.backfillComplete = backfillComplete;
    }

    /** An empty index (no vectors, backfill not yet complete). */
    public static DenseIndexState empty() {
        return new DenseIndexState(new LinkedHashMap<>(), false);
    }

    /**
     * A deep, independent copy — used to snapshot the index on the server thread so the actual
     * {@code .bin} write can run off-tick (on {@code MEMORY_EXECUTOR}) without aliasing live state.
     */
    public DenseIndexState copy() {
        Map<String, float[]> c = new LinkedHashMap<>(Math.max(16, vectors.size() * 2));
        for (Map.Entry<String, float[]> e : vectors.entrySet()) {
            c.put(e.getKey(), e.getValue().clone());
        }
        return new DenseIndexState(c, backfillComplete);
    }

    /** The stable model-identity version token this build writes into / expects from the {@code .bin}. */
    public static String modelIdentityToken() {
        return EmbeddingModel.vectorModelToken();
    }

    // -------------------------------------------------------------------------
    // Accessors / mutation (off-tick)
    // -------------------------------------------------------------------------

    /** True iff a full backfill of the graph's nodes has completed for the current frozen model. */
    public boolean backfillComplete() {
        return backfillComplete;
    }

    public void setBackfillComplete(boolean done) {
        this.backfillComplete = done;
    }

    /** Number of vectors held. */
    public int size() {
        return vectors.size();
    }

    /** The (dequantized) vector for a node id, or null if absent. */
    public float[] vector(String nodeId) {
        return vectors.get(nodeId);
    }

    /** Upserts a node's vector (dim must be {@link EmbeddingModel#FROZEN_DIMS}). */
    public void put(String nodeId, float[] vector) {
        if (nodeId == null || vector == null || vector.length != EmbeddingModel.FROZEN_DIMS) {
            return;
        }
        vectors.put(nodeId, vector.clone());
    }

    /** Drops a node's vector (e.g. on node removal / compaction). */
    public void remove(String nodeId) {
        vectors.remove(nodeId);
    }

    /** Removes any vectors whose node id is no longer present in the live graph (compaction hygiene). */
    public void pruneTo(MemoryGraph graph) {
        if (graph == null) return;
        vectors.keySet().removeIf(id -> graph.node(id) == null);
    }

    // -------------------------------------------------------------------------
    // Cosine retrieval (brute force — no ANN; ≤ MAX_NODES per companion)
    // -------------------------------------------------------------------------

    /**
     * Brute-force cosine of {@code queryVector} against every node that has a vector — preferring the
     * in-memory {@code .bin} mirror, falling back to the node's own {@code vector()} from the graph for
     * any node not yet mirrored. Returns node ids ranked by descending cosine, capped at {@code topK}.
     * Returns empty when {@code queryVector} is null (dense off / turn embed degraded) — the byte-identical
     * read-path no-op.
     */
    public List<String> cosineRankedIds(MemoryGraph graph, float[] queryVector, int topK) {
        if (graph == null || queryVector == null || queryVector.length != EmbeddingModel.FROZEN_DIMS) {
            return List.of();
        }
        double qNorm = norm(queryVector);
        if (qNorm <= 0.0) return List.of();

        List<double[]> scored = new ArrayList<>(); // [score, index-into-ids]
        List<String> ids = new ArrayList<>();
        for (MemoryNode n : graph.nodes()) {
            float[] v = vectors.get(n.id());
            if (v == null && n.hasVector()) {
                float[] raw = n.vector();
                if (raw != null && raw.length == EmbeddingModel.FROZEN_DIMS
                        && EmbeddingModel.vectorModelToken().equals(n.vectorModel())) {
                    v = raw;
                }
            }
            if (v == null) continue;
            double cos = cosine(queryVector, qNorm, v);
            scored.add(new double[]{cos, ids.size()});
            ids.add(n.id());
        }
        scored.sort((a, b) -> Double.compare(b[0], a[0]));
        int limit = Math.min(Math.max(0, topK), scored.size());
        List<String> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            out.add(ids.get((int) scored.get(i)[1]));
        }
        return out;
    }

    /** Cosine similarity of {@code q} (with precomputed {@code qNorm}) and {@code v}. */
    public static double cosine(float[] q, double qNorm, float[] v) {
        if (q == null || v == null || q.length != v.length) return 0.0;
        double dot = 0.0;
        double vNorm2 = 0.0;
        for (int i = 0; i < q.length; i++) {
            dot += (double) q[i] * v[i];
            vNorm2 += (double) v[i] * v[i];
        }
        double denom = qNorm * Math.sqrt(vNorm2);
        return denom <= 0.0 ? 0.0 : dot / denom;
    }

    private static double norm(float[] v) {
        double s = 0.0;
        for (float x : v) s += (double) x * x;
        return Math.sqrt(s);
    }

    // -------------------------------------------------------------------------
    // Persistence: load / save (atomic tmp-rename)
    // -------------------------------------------------------------------------

    /**
     * Loads the {@code .bin} at {@code path}. Returns null (caller rebuilds in-memory) when the file is
     * absent, unreadable, or its {@code magic}/{@code formatVer}/{@code versionToken}/{@code dim} does not
     * match this build's frozen model identity. Never throws.
     */
    public static DenseIndexState load(Path path) {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        try (DataInputStream din = new DataInputStream(Files.newInputStream(path))) {
            if (din.readInt() != MAGIC) {
                return null;
            }
            if (din.readInt() != FORMAT_VERSION) {
                return null;
            }
            String token = din.readUTF();
            if (!modelIdentityToken().equals(token)) {
                return null; // frozen model/dim changed → discard (triggers re-embed)
            }
            int dim = din.readInt();
            if (dim != EmbeddingModel.FROZEN_DIMS) {
                return null;
            }
            int count = din.readInt();
            boolean backfillDone = din.readByte() != 0;
            Map<String, float[]> loaded = new LinkedHashMap<>(Math.max(16, count * 2));
            for (int i = 0; i < count; i++) {
                String nodeId = din.readUTF();
                float scale = din.readFloat();
                byte[] q = new byte[dim];
                din.readFully(q);
                loaded.put(nodeId, dequantize(q, scale));
            }
            return new DenseIndexState(loaded, backfillDone);
        } catch (IOException | RuntimeException e) {
            PlayerEngine.LOGGER.warn("Memory: dense-index.bin unreadable ({}); rebuilding in-memory.",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Atomically writes this index to {@code path} ({@code .tmp} + rename, mirroring {@code graph.json}).
     * Off-tick (flush cadence). Never throws.
     */
    public void save(Path path) {
        if (path == null) return;
        EmbeddingProvider.assertOffTick();
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(FILE_NAME + ".tmp");
            try (DataOutputStream dout = new DataOutputStream(Files.newOutputStream(tmp))) {
                dout.writeInt(MAGIC);
                dout.writeInt(FORMAT_VERSION);
                dout.writeUTF(modelIdentityToken());
                dout.writeInt(EmbeddingModel.FROZEN_DIMS);
                dout.writeInt(vectors.size());
                dout.writeByte(backfillComplete ? 1 : 0);
                for (Map.Entry<String, float[]> e : vectors.entrySet()) {
                    float[] v = e.getValue();
                    float scale = quantScale(v);
                    dout.writeUTF(e.getKey());
                    dout.writeFloat(scale);
                    dout.write(quantize(v, scale));
                }
            }
            atomicReplace(tmp, path);
        } catch (IOException | RuntimeException e) {
            PlayerEngine.LOGGER.warn("Memory: failed to write dense-index.bin ({}).",
                    e.getClass().getSimpleName());
        }
    }

    /** Deletes the {@code .bin} (e.g. on a model/dim re-embed migration). Never throws. */
    public static void delete(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort; a stale .bin is discarded on next load by the version-token check anyway
        }
    }

    // -------------------------------------------------------------------------
    // int8 symmetric quantization
    // -------------------------------------------------------------------------

    /** Symmetric per-vector scale: {@code maxAbs / 127} (0 when the vector is all-zero). */
    static float quantScale(float[] v) {
        float maxAbs = 0f;
        for (float x : v) {
            float a = Math.abs(x);
            if (a > maxAbs) maxAbs = a;
        }
        return maxAbs == 0f ? 0f : maxAbs / 127f;
    }

    /** Quantizes to int8: {@code round(component / scale)}, clamped to [-127, 127]. */
    static byte[] quantize(float[] v, float scale) {
        byte[] out = new byte[v.length];
        if (scale <= 0f) return out; // all-zero vector
        for (int i = 0; i < v.length; i++) {
            int q = Math.round(v[i] / scale);
            if (q > 127) q = 127;
            if (q < -127) q = -127;
            out[i] = (byte) q;
        }
        return out;
    }

    /** Dequantizes int8 back to {@code float[]} for cosine ({@code component = q * scale}). */
    static float[] dequantize(byte[] q, float scale) {
        float[] out = new float[q.length];
        for (int i = 0; i < q.length; i++) {
            out[i] = q[i] * scale;
        }
        return out;
    }

    private static void atomicReplace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
