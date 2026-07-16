package com.player2.playerengine.agentic.elliegps;

import com.player2.playerengine.retrieval.LexicalIndex;
import com.player2.playerengine.retrieval.MinHashIndex;
import com.player2.playerengine.retrieval.RetrievalHit;
import com.player2.playerengine.retrieval.RrfFusion;
import com.player2.playerengine.retrieval.ToolDocument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Structured, bounded waypoint retrieval over the authoritative EllieGPS store.
 *
 * <p>Relevance searches validate the derived index against the store before use and degrade to an
 * in-memory full-corpus rank when repair fails. Spatial searches never depend on index top-k output.
 * In both modes the complete authoritative corpus is bounded before any partial answer is returned,
 * and structured filters are applied before the caller's final limit.
 */
public final class WaypointSearchService {

    /** Hard bound for every authoritative-store search path. */
    public static final int MAX_AUTHORITATIVE_RECORDS = 4_096;

    private static final double RRF_K = 60.0;
    private static final long MAX_SQUARE_ROOT = 3_037_000_499L;

    private WaypointSearchService() {}

    /**
     * Searches the active world store using the frozen structured-search contract.
     *
     * @param query free-text relevance eligibility; blank means every structured-filter survivor
     * @param dimension required dimension, or null/blank for any dimension
     * @param allowedTypes allowed waypoint types, or null/empty for any type
     * @param includeStale whether stale records remain eligible
     * @param order relevance or nearest ordering
     * @param origin required for {@link WaypointSearchOrder#NEAREST}, forbidden for relevance
     * @param limit maximum records to return; non-positive returns an empty bounded result
     */
    public static WaypointSearchResult find(
            String query,
            String dimension,
            Set<String> allowedTypes,
            boolean includeStale,
            WaypointSearchOrder order,
            BlockPos origin,
            int limit) {
        return find(EllieGPSStore.get(), query, dimension, allowedTypes, includeStale, order, origin, limit);
    }

    /** Package-visible overload for deterministic store fixtures. */
    static WaypointSearchResult find(
            EllieGPSStore store,
            String query,
            String dimension,
            Set<String> allowedTypes,
            boolean includeStale,
            WaypointSearchOrder order,
            BlockPos origin,
            int limit) {
        Objects.requireNonNull(order, "order");
        if (order == WaypointSearchOrder.NEAREST && origin == null) {
            throw new IllegalArgumentException("origin is required for NEAREST waypoint search");
        }
        if (order == WaypointSearchOrder.RELEVANCE && origin != null) {
            throw new IllegalArgumentException("origin must be null for RELEVANCE waypoint search");
        }

        if (store == null) {
            return new WaypointSearchResult(
                    List.of(), WaypointSearchStatus.FAILED_STORE_UNAVAILABLE);
        }

        List<WaypointRecord> authoritative = store.publishedRecords();
        if (authoritative.size() > MAX_AUTHORITATIVE_RECORDS) {
            return new WaypointSearchResult(List.of(), WaypointSearchStatus.FAILED_SCAN_LIMIT);
        }

        String normalizedQuery = query == null ? "" : query.trim();
        String normalizedDimension = dimension == null ? "" : dimension.trim();
        Set<String> normalizedTypes = normalizeTypes(allowedTypes);
        int boundedLimit = Math.max(0, Math.min(limit, MAX_AUTHORITATIVE_RECORDS));

        try {
            if (order == WaypointSearchOrder.NEAREST) {
                return nearest(
                        authoritative,
                        normalizedQuery,
                        normalizedDimension,
                        normalizedTypes,
                        includeStale,
                        origin,
                        boundedLimit);
            }
            return relevance(
                    store,
                    authoritative,
                    normalizedQuery,
                    normalizedDimension,
                    normalizedTypes,
                    includeStale,
                    boundedLimit);
        } catch (RuntimeException e) {
            return new WaypointSearchResult(
                    List.of(), WaypointSearchStatus.FAILED_SEARCH_ERROR);
        }
    }

    private static WaypointSearchResult relevance(
            EllieGPSStore store,
            List<WaypointRecord> authoritative,
            String query,
            String dimension,
            Set<String> allowedTypes,
            boolean includeStale,
            int limit) {
        boolean indexHealthy = store.isIndexHealthy();
        if (!indexHealthy) {
            indexHealthy = store.synchronizeIndex() == WaypointIndexUpdateStatus.INDEX_COMMITTED
                    && store.isIndexHealthy();
        }

        if (query.isEmpty()) {
            List<WaypointRecord> records = structuredRecords(
                    authoritative, dimension, allowedTypes, includeStale);
            records.sort(Comparator.comparing(WaypointSearchService::stableId));
            return new WaypointSearchResult(
                    take(records, limit),
                    indexHealthy ? WaypointSearchStatus.INDEXED : WaypointSearchStatus.FULL_STORE_FALLBACK);
        }

        if (indexHealthy) {
            try {
                List<RetrievalHit> hits = store.queryIndex(query, authoritative.size());
                List<WaypointRecord> records = recordsForHits(
                        authoritative, hits, dimension, allowedTypes, includeStale, limit);
                return new WaypointSearchResult(records, WaypointSearchStatus.INDEXED);
            } catch (Exception ignored) {
                // The authoritative in-memory fallback below is deterministic and visibly degraded.
            }
        }

        List<RetrievalHit> fallbackHits = rankInMemory(authoritative, query);
        List<WaypointRecord> fallbackRecords = recordsForHits(
                authoritative, fallbackHits, dimension, allowedTypes, includeStale, limit);
        return new WaypointSearchResult(fallbackRecords, WaypointSearchStatus.FULL_STORE_FALLBACK);
    }

    private static WaypointSearchResult nearest(
            List<WaypointRecord> authoritative,
            String query,
            String dimension,
            Set<String> allowedTypes,
            boolean includeStale,
            BlockPos origin,
            int limit) {
        List<WaypointRecord> candidates = structuredRecords(
                authoritative, dimension, allowedTypes, includeStale);
        candidates.removeIf(record -> record.canonicalBlockPos() == null);

        if (!query.isEmpty()) {
            Set<String> eligibleIds = new HashSet<>();
            for (RetrievalHit hit : rankInMemory(candidates, query)) {
                if (hit.toolId() != null) {
                    eligibleIds.add(hit.toolId());
                }
            }
            candidates.removeIf(record -> !eligibleIds.contains(record.id));
        }

        candidates.sort(Comparator
                .comparingLong((WaypointRecord record) -> squaredDistanceSaturated(record, origin))
                .thenComparing(WaypointSearchService::stableId));
        return new WaypointSearchResult(
                take(candidates, limit), WaypointSearchStatus.AUTHORITATIVE_SPATIAL);
    }

    private static List<WaypointRecord> recordsForHits(
            List<WaypointRecord> authoritative,
            List<RetrievalHit> hits,
            String dimension,
            Set<String> allowedTypes,
            boolean includeStale,
            int limit) {
        if (limit == 0 || hits == null || hits.isEmpty()) {
            return List.of();
        }

        Map<String, WaypointRecord> recordsById = new LinkedHashMap<>();
        for (WaypointRecord record : authoritative) {
            if (record != null && record.id != null) {
                recordsById.put(record.id, record);
            }
        }

        List<RetrievalHit> stableHits = new ArrayList<>(hits);
        stableHits.sort(Comparator
                .comparingDouble(RetrievalHit::score)
                .reversed()
                .thenComparing(hit -> hit.toolId() == null ? "" : hit.toolId()));

        List<WaypointRecord> records = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RetrievalHit hit : stableHits) {
            String id = hit.toolId();
            if (id == null || !seen.add(id)) {
                continue;
            }
            WaypointRecord record = recordsById.get(id);
            if (!matchesStructuredFilters(record, dimension, allowedTypes, includeStale)) {
                continue;
            }
            records.add(record);
            if (records.size() >= limit) {
                break;
            }
        }
        return records;
    }

    /** Full-corpus, disk-free relevance ranking used for degraded search and spatial eligibility. */
    static List<RetrievalHit> rankInMemory(List<WaypointRecord> records, String query) {
        if (query == null || query.isBlank() || records.isEmpty()) {
            return List.of();
        }

        List<ToolDocument> documents = new ArrayList<>(records.size());
        for (WaypointRecord record : records) {
            if (record == null || record.id == null) {
                continue;
            }
            try {
                documents.add(new WaypointDocument(record).toToolDocument());
            } catch (RuntimeException ignored) {
                // Unsupported/malformed forward-compatible records are not relevance-eligible.
            }
        }
        if (documents.isEmpty()) {
            return List.of();
        }

        int fullCorpusLimit = documents.size();
        List<RetrievalHit> lexical = LexicalIndex.build(documents).query(query, fullCorpusLimit);
        List<RetrievalHit> minHash = MinHashIndex.build(documents).query(query, fullCorpusLimit);
        List<RetrievalHit> fused = new ArrayList<>(
                RrfFusion.fuse(lexical, minHash, fullCorpusLimit, RRF_K));
        fused.sort(Comparator
                .comparingDouble(RetrievalHit::score)
                .reversed()
                .thenComparing(hit -> hit.toolId() == null ? "" : hit.toolId()));
        return fused;
    }

    private static List<WaypointRecord> structuredRecords(
            List<WaypointRecord> authoritative,
            String dimension,
            Set<String> allowedTypes,
            boolean includeStale) {
        List<WaypointRecord> records = new ArrayList<>();
        for (WaypointRecord record : authoritative) {
            if (matchesStructuredFilters(record, dimension, allowedTypes, includeStale)) {
                records.add(record);
            }
        }
        return records;
    }

    private static boolean matchesStructuredFilters(
            WaypointRecord record,
            String dimension,
            Set<String> allowedTypes,
            boolean includeStale) {
        if (record == null || record.id == null) {
            return false;
        }
        if (!allowedTypes.isEmpty() && !allowedTypes.contains(record.type)) {
            return false;
        }
        if (!dimension.isEmpty() && !dimension.equals(record.dimension)) {
            return false;
        }
        return includeStale || !record.stale;
    }

    private static Set<String> normalizeTypes(Set<String> allowedTypes) {
        if (allowedTypes == null || allowedTypes.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new HashSet<>();
        for (String type : allowedTypes) {
            if (type != null && !type.isBlank()) {
                normalized.add(type);
            }
        }
        return Set.copyOf(normalized);
    }

    private static List<WaypointRecord> take(List<WaypointRecord> records, int limit) {
        if (limit <= 0 || records.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(records.subList(0, Math.min(limit, records.size())));
    }

    private static long squaredDistanceSaturated(WaypointRecord record, BlockPos origin) {
        BlockPos pos = record.canonicalBlockPos();
        if (pos == null) {
            return Long.MAX_VALUE;
        }
        long dx = (long) pos.getX() - origin.getX();
        long dy = (long) pos.getY() - origin.getY();
        long dz = (long) pos.getZ() - origin.getZ();
        return saturatingAdd(saturatingAdd(saturatingSquare(dx), saturatingSquare(dy)), saturatingSquare(dz));
    }

    private static long saturatingSquare(long value) {
        if (value > MAX_SQUARE_ROOT || value < -MAX_SQUARE_ROOT) {
            return Long.MAX_VALUE;
        }
        return value * value;
    }

    private static long saturatingAdd(long left, long right) {
        if (left == Long.MAX_VALUE || right == Long.MAX_VALUE || Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static String stableId(WaypointRecord record) {
        return record == null || record.id == null ? "" : record.id;
    }

}
