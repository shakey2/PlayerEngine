package com.player2.playerengine.modintelligence.ingest;

import com.player2.playerengine.PlayerEngine;
import com.player2.playerengine.modintelligence.capability.CapabilityMap;
import com.player2.playerengine.modintelligence.capability.CapabilitySchema;
import com.player2.playerengine.modintelligence.capability.CapabilityStatus;
import com.player2.playerengine.modintelligence.capability.CapabilitySubjectKind;
import com.player2.playerengine.modintelligence.enrich.CapabilityEnrichment;
import com.player2.playerengine.modintelligence.inspect.CapabilityHeuristics;
import com.player2.playerengine.modintelligence.inspect.CapabilityInspectors;
import com.player2.playerengine.modintelligence.inspect.InspectionContext;
import com.player2.playerengine.modintelligence.inspect.ModBlockInspector;
import com.player2.playerengine.modintelligence.inspect.ModEntityTypeInspector;
import com.player2.playerengine.modintelligence.inspect.ModItemInspector;
import com.player2.playerengine.modintelligence.inspect.SanitizedNbtSnapshot;
import com.player2.playerengine.modintelligence.inspect.StablePropertyCollector;
import com.player2.playerengine.modintelligence.inspect.TagCollector;
import com.player2.playerengine.modintelligence.query.CapabilityIndex;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import dev.architectury.platform.Platform;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ModMetadataIngestionPipeline {
    private static final Logger LOGGER = PlayerEngine.LOGGER;

    public record IngestSummary(
            int mods,
            int entries,
            int unchanged,
            int changed,
            int newly,
            int deleted,
            int inspected,
            int queuedEnrichment,
            int failed
    ) {}

    private final MinecraftServer server;
    private final String minecraftVersion;
    private final String loader;
    private final HolderLookup.Provider registryAccess;

    public ModMetadataIngestionPipeline(MinecraftServer server) {
        this.server = server;
        this.minecraftVersion = Platform.getMinecraftVersion();
        this.loader = detectLoader();
        this.registryAccess = server.registryAccess();
    }

    public IngestSummary run(boolean forceRebuild) throws IOException {
        Player2ServerRuntimeConfig cfg = Player2ServerConfigHolder.get();
        if (!cfg.isModIntelligenceEnabled()) {
            return new IngestSummary(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        ModIntelligencePaths.rootDir().mkdirs();
        if (!acquireLock()) {
            LOGGER.info("ModIntelligence: ingestion already in progress, skipping");
            return new IngestSummary(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        try {
            cleanupStaleStaging();
            CapabilityStore store = new CapabilityStore();
            store.loadFromDisk();

            Map<String, ModFingerprint> modFingerprints = buildModFingerprints();
            Map<String, EntryFingerprint> previousEntries = store.getManifest() == null
                    ? Map.of() : new HashMap<>(store.getManifest().getEntries());

            InspectionContext ctx = new InspectionContext(minecraftVersion, loader,
                    CapabilityInspectors.INSPECTOR_VERSION, registryAccess);
            ModItemInspector itemInspector = new ModItemInspector();
            ModBlockInspector blockInspector = new ModBlockInspector();
            ModEntityTypeInspector entityInspector = new ModEntityTypeInspector();

            Map<String, CapabilityMap> nextMaps = new LinkedHashMap<>();
            Map<String, EntryFingerprint> nextEntries = new LinkedHashMap<>();
            List<String> enrichmentQueue = new ArrayList<>();
            List<CapabilityMap> tombstones = new ArrayList<>();

            int unchanged = 0, changed = 0, newly = 0, deleted = 0, inspected = 0, failed = 0;

            Set<String> currentKeys = new HashSet<>();
            List<RegistryEntry> registryEntries = collectRegistryEntries();
            int inspectBudget = cfg.getModIntelligenceMaxInspectEntriesPerLaunch();
            if (inspectBudget <= 0) {
                inspectBudget = Integer.MAX_VALUE;
            }

            for (RegistryEntry re : registryEntries) {
                currentKeys.add(re.entryKey());
                String modHash = modFingerprints.containsKey(re.sourceModId())
                        ? modFingerprints.get(re.sourceModId()).getSourceHash() : "unknown";

                List<String> tags = re.tags();
                Map<String, String> props = re.stableProperties();
                Map<String, String> nbt = re.sanitizedNbt();
                String detHash = CapabilityFingerprintHasher.deterministicInputHash(
                        re.kind(), re.subjectId(), re.sourceModId(), modHash,
                        minecraftVersion, loader, tags, props, nbt);

                EntryFingerprint prev = previousEntries.get(re.entryKey());
                CapabilityMap existing = store.get(re.entryKey());

                boolean needInspect = forceRebuild || prev == null
                        || prev.getDeterministicInputHash() == null
                        || !detHash.equals(prev.getDeterministicInputHash())
                        || store.getManifest() == null
                        || store.getManifest().getInspectorVersion() != CapabilityInspectors.INSPECTOR_VERSION
                        || store.getManifest().getHeuristicVersion() != CapabilityHeuristics.HEURISTIC_VERSION
                        || store.getManifest().getSchemaVersion() != CapabilitySchema.VERSION;

                CapabilityMap map;
                if (needInspect && inspected < inspectBudget) {
                    map = inspectEntry(ctx, re, itemInspector, blockInspector, entityInspector);
                    inspected++;
                    if (prev == null) {
                        newly++;
                    } else {
                        changed++;
                    }
                } else if (existing != null && !needInspect) {
                    map = existing;
                    unchanged++;
                } else if (existing != null) {
                    map = existing;
                    unchanged++;
                } else {
                    map = inspectEntry(ctx, re, itemInspector, blockInspector, entityInspector);
                    inspected++;
                    newly++;
                }

                String enrHash = CapabilityFingerprintHasher.enrichmentInputHash(detHash, map);
                String fp = CapabilityFingerprintHasher.entryFingerprint(detHash, enrHash);
                map.setEntryFingerprint(fp);

                EntryFingerprint ef = new EntryFingerprint();
                ef.setSubjectKind(re.kind().name());
                ef.setSubjectId(re.subjectId());
                ef.setSourceModId(re.sourceModId());
                ef.setFingerprint(fp);
                ef.setDeterministicInputHash(detHash);
                ef.setEnrichmentInputHash(enrHash);
                ef.setCapabilityStatus(map.getStatus().name());
                ef.setEnriched(map.getEnrichment() != null);

                nextMaps.put(re.entryKey(), map);
                nextEntries.put(re.entryKey(), ef);

                if (cfg.isModIntelligenceEnrichmentEnabled()
                        && shouldQueueEnrichment(map, prev, ef, detHash, needInspect)) {
                    enrichmentQueue.add(re.entryKey());
                }
            }

            for (Map.Entry<String, EntryFingerprint> e : previousEntries.entrySet()) {
                if (!currentKeys.contains(e.getKey())) {
                    deleted++;
                    CapabilityMap tomb = store.get(e.getKey());
                    if (tomb != null) {
                        tomb.setStatus(CapabilityStatus.TOMBSTONED);
                        tombstones.add(tomb);
                    }
                }
            }

            String packFp = CapabilityFingerprintHasher.packFingerprint(minecraftVersion, loader, modFingerprints);
            ModIntelligenceManifest manifest = new ModIntelligenceManifest();
            manifest.setSchemaVersion(CapabilitySchema.VERSION);
            manifest.setInspectorVersion(CapabilityInspectors.INSPECTOR_VERSION);
            manifest.setHeuristicVersion(CapabilityHeuristics.HEURISTIC_VERSION);
            manifest.setMinecraftVersion(minecraftVersion);
            manifest.setLoader(loader);
            manifest.setPackFingerprint(packFp);
            manifest.setMods(modFingerprints);
            manifest.setEntries(nextEntries);
            manifest.setUpdatedAtEpochMillis(System.currentTimeMillis());

            String runId = UUID.randomUUID().toString().substring(0, 8);
            File staging = ModIntelligencePaths.stagingDir(runId);
            staging.mkdirs();
            CapabilityStoreWriter.writeJsonl(new File(staging, "capability_maps.jsonl"), nextMaps.values());
            if (!tombstones.isEmpty()) {
                CapabilityStoreWriter.writeJsonl(new File(staging, "deleted_entries.jsonl"), tombstones);
            }
            CapabilityStoreWriter.writeJson(new File(staging, "manifest.json"), manifest);
            CapabilityStoreWriter.promoteStaging(runId, manifest);

            CapabilityIndex.buildAndPersist(nextMaps.values(), packFp);

            java.util.LinkedHashMap<String, CapabilityMap> queueMaps = new java.util.LinkedHashMap<>();
            for (CapabilityMap pending : com.player2.playerengine.modintelligence.enrich.CapabilityEnrichmentQueue.loadQueued()) {
                queueMaps.put(CapabilityStore.entryKey(pending), pending);
            }
            for (String key : enrichmentQueue) {
                CapabilityMap m = nextMaps.get(key);
                if (m != null) {
                    queueMaps.put(key, m);
                }
            }
            if (!queueMaps.isEmpty()) {
                CapabilityStoreWriter.writeJsonl(ModIntelligencePaths.enrichmentQueueFile(), queueMaps.values());
            }

            LOGGER.info("ModIntelligence: mods={} entries={} unchanged={} changed={} new={} deleted={} inspected={} queuedEnrichment={} failed={}",
                    modFingerprints.size(), nextMaps.size(), unchanged, changed, newly, deleted,
                    inspected, enrichmentQueue.size(), failed);

            return new IngestSummary(modFingerprints.size(), nextMaps.size(), unchanged, changed, newly,
                    deleted, inspected, enrichmentQueue.size(), failed);
        } finally {
            releaseLock();
        }
    }

    private boolean shouldQueueEnrichment(CapabilityMap map, EntryFingerprint prev,
                                          EntryFingerprint next, String detHash,
                                          boolean deterministicChanged) {
        if (map.getStatus() == CapabilityStatus.FAILED || map.getStatus() == CapabilityStatus.TOMBSTONED) {
            return false;
        }

        if (prev != null && prev.isEnriched() && map.getEnrichment() != null
                && detHash.equals(prev.getDeterministicInputHash())
                && prev.getEnrichmentInputHash() != null
                && prev.getEnrichmentInputHash().equals(next.getEnrichmentInputHash())) {
            return false;
        }

        boolean enrichmentPromptChanged = prev != null
                && prev.getEnrichmentInputHash() != null
                && !prev.getEnrichmentInputHash().equals(next.getEnrichmentInputHash())
                && detHash.equals(prev.getDeterministicInputHash());

        if (prev == null || deterministicChanged || enrichmentPromptChanged) {
            return meetsEnrichmentCriteria(map, true);
        }

        if (prev != null && !prev.isEnriched() && map.getEnrichment() == null) {
            return meetsEnrichmentCriteria(map, false);
        }

        return false;
    }

    private static boolean meetsEnrichmentCriteria(CapabilityMap map, boolean afterDataChange) {
        if (map.getStatus() == CapabilityStatus.FAILED || map.getStatus() == CapabilityStatus.TOMBSTONED) {
            return false;
        }
        if (afterDataChange) {
            if ("minecraft".equals(map.getSourceModId())) {
                return false;
            }
            if (map.getStatus() == CapabilityStatus.PARTIAL || map.getStatus() == CapabilityStatus.UNKNOWN) {
                return true;
            }
            return map.getEnrichment() == null;
        }
        if ("minecraft".equals(map.getSourceModId())) {
            return false;
        }
        return map.getStatus() == CapabilityStatus.PARTIAL || map.getStatus() == CapabilityStatus.UNKNOWN;
    }

    private CapabilityMap inspectEntry(InspectionContext ctx, RegistryEntry re,
                                        ModItemInspector itemInspector,
                                        ModBlockInspector blockInspector,
                                        ModEntityTypeInspector entityInspector) {
        ResourceLocation id = ResourceLocation.parse(re.subjectId());
        return switch (re.kind()) {
            case ITEM -> {
                Item item = BuiltInRegistries.ITEM.get(id);
                yield itemInspector.inspect(ctx, id, item);
            }
            case BLOCK -> {
                Block block = BuiltInRegistries.BLOCK.get(id);
                yield blockInspector.inspect(ctx, id, block);
            }
            case ENTITY_TYPE -> {
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
                yield entityInspector.inspect(ctx, id, type);
            }
        };
    }

    private static String detectLoader() {
        if (Platform.isFabric()) {
            return "fabric";
        }
        if (Platform.isNeoForge()) {
            return "neoforge";
        }
        if (Platform.isForge()) {
            return "forge";
        }
        return "unknown";
    }

    private Map<String, ModFingerprint> buildModFingerprints() {
        Map<String, ModFingerprint> out = new LinkedHashMap<>();
        for (LoadedModMetadata m : LoadedModMetadataProvider.loadedMods()) {
            ModFingerprint fp = new ModFingerprint();
            fp.setModId(m.getModId());
            fp.setDisplayName(m.getDisplayName());
            fp.setVersion(m.getVersion());
            fp.setSourceHash(m.getSourceHash());
            fp.setMetadataHash(m.getSourceHash());
            fp.setNamespaces(m.getNamespaces());
            out.put(m.getModId(), fp);
        }
        return out;
    }

    private List<RegistryEntry> collectRegistryEntries() {
        List<RegistryEntry> entries = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            entries.add(RegistryEntry.item(id));
        }
        for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet()) {
            entries.add(RegistryEntry.block(id, registryAccess));
        }
        for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            entries.add(RegistryEntry.entity(id));
        }
        return entries;
    }

    private boolean acquireLock() throws IOException {
        File lock = ModIntelligencePaths.ingestLockFile();
        if (lock.exists()) {
            String pid = Files.readString(lock.toPath(), StandardCharsets.UTF_8).trim();
            if (pid.equals(String.valueOf(ProcessHandle.current().pid()))) {
                return false;
            }
            LOGGER.warn("ModIntelligence: replacing stale ingest lock (was pid {})", pid);
        }
        Files.writeString(lock.toPath(), String.valueOf(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
        return true;
    }

    private void releaseLock() {
        File lock = ModIntelligencePaths.ingestLockFile();
        if (lock.exists()) {
            lock.delete();
        }
    }

    private void cleanupStaleStaging() {
        File root = ModIntelligencePaths.rootDir();
        File stagingParent = new File(root, ".staging");
        if (!stagingParent.exists()) {
            return;
        }
        File[] runs = stagingParent.listFiles();
        if (runs == null) {
            return;
        }
        for (File run : runs) {
            CapabilityStoreWriter.deleteRecursive(run);
        }
    }

    private record RegistryEntry(
            CapabilitySubjectKind kind,
            String subjectId,
            String sourceModId,
            List<String> tags,
            Map<String, String> stableProperties,
            Map<String, String> sanitizedNbt
    ) {
        String entryKey() {
            return kind.name() + "|" + subjectId;
        }

        static RegistryEntry item(ResourceLocation id) {
            List<String> tags = TagCollector.collectTagIds(BuiltInRegistries.ITEM, id);
            Item item = BuiltInRegistries.ITEM.get(id);
            return new RegistryEntry(CapabilitySubjectKind.ITEM, id.toString(), id.getNamespace(),
                    tags, StablePropertyCollector.forItem(item), Map.of());
        }

        static RegistryEntry block(ResourceLocation id, HolderLookup.Provider access) {
            List<String> tags = TagCollector.collectTagIds(BuiltInRegistries.BLOCK, id);
            Block block = BuiltInRegistries.BLOCK.get(id);
            return new RegistryEntry(CapabilitySubjectKind.BLOCK, id.toString(), id.getNamespace(),
                    tags, StablePropertyCollector.forBlock(block), Map.of());
        }

        static RegistryEntry entity(ResourceLocation id) {
            List<String> tags = TagCollector.collectTagIds(BuiltInRegistries.ENTITY_TYPE, id);
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
            return new RegistryEntry(CapabilitySubjectKind.ENTITY_TYPE, id.toString(), id.getNamespace(),
                    tags, StablePropertyCollector.forEntityType(type), Map.of());
        }
    }
}
