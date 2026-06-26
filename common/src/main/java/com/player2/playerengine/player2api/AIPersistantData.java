
package com.player2.playerengine.player2api;
import java.util.Deque;
import java.util.Optional;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Objects;
import java.util.UUID;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.player2api.Event.InfoMessage;
import com.player2.playerengine.automaton.utils.DirUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;


public class AIPersistantData {
    // contains data relating to AI processing, only including data that is
    // permanent,
    // and persists across game state (not queue stuff)

    private ConversationHistory conversationHistory;
    private Character character;
    private PlayerEngineController mod;
    private String characterId;
    private Path conversationHistoryFile;

    public AIPersistantData(PlayerEngineController mod, Character character) {
        this.character = character;
        this.mod = mod;
        String systemPrompt = Prompts.getAINPCSystemPrompt(character, mod.getCommandExecutor().allCommands(), mod.getOwnerUsername());
        this.characterId = character == null ? null : character.id();
        Path worldRoot = resolveWorldRootOrNull(mod);
        this.conversationHistoryFile = getConversationHistoryFileOrNull(mod, worldRoot, this.characterId);
        String basePrompt = withRelationshipSuffix(systemPrompt);
        if (this.conversationHistoryFile != null) {
            migrateLegacyHistoryIfPresent(character, this.characterId, worldRoot, this.conversationHistoryFile, mod);
            this.conversationHistory = new ConversationHistory(basePrompt, this.conversationHistoryFile);
        } else {
            // Fallback to non-persistent history if we can't resolve world root or characterId.
            this.conversationHistory = new ConversationHistory(basePrompt);
        }
    }

    /**
     * Phase D (W6) — the SINGLE combine point for "base prompt + optional {@code [Relationship]} suffix".
     * EVERY system-prompt rebuild path ({@link #updateSystemPrompt}, {@link #updateSystemPromptWithBlock},
     * {@link #updateSystemPromptStatic}, and the constructor) routes the freshly-built base prompt through
     * here so the relationship-summary suffix is <b>byte-identical across all rebuild paths</b> for a given
     * {@code summaryVersion} — the prefix-cache stability invariant (plan §W6). The summary changes only on
     * reflection; between reflections this suffix is constant, so two consecutive turns taking different
     * rebuild paths produce the same system block.
     *
     * <p>Empty / absent summary (memory off, store not loaded, non-patron) → suffix omitted → the system
     * block is <b>byte-identical to today</b>. The summary is normalized + hard-capped by
     * {@link com.player2.playerengine.memory.reflection.RelationshipSummary} (write-time egress boundary);
     * {@code setBaseSystemPrompt} additionally applies {@code LogEgressGuard.capForModel(..., "system")}.
     */
    private String withRelationshipSuffix(String basePrompt) {
        String base = basePrompt != null ? basePrompt : "";
        String summary = currentRelationshipSummaryOrEmpty();
        return base + com.player2.playerengine.memory.reflection.RelationshipSummary.suffixFor(summary);
    }

    /**
     * Reads this companion's current relationship summary from its loaded {@link
     * com.player2.playerengine.memory.MemoryStore}, or {@code ""} when memory is off / the store is not
     * loaded / there is no summary. Best-effort and never throws — a failure simply omits the suffix
     * (byte-identical to baseline). Does not load the store (read-only {@code peek}); the store is loaded
     * lazily by the lifecycle/ingestion/retrieval paths.
     */
    private String currentRelationshipSummaryOrEmpty() {
        try {
            com.player2.playerengine.memory.MemoryScope scope = resolveMemoryScopeOrNull();
            if (scope == null) {
                return "";
            }
            com.player2.playerengine.memory.MemoryStore store =
                    com.player2.playerengine.memory.MemoryStoreRegistry.peek(scope);
            if (store == null) {
                return "";
            }
            String summary = store.relationshipSummary();
            return summary != null ? summary : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Resolves this companion's {@link com.player2.playerengine.memory.MemoryScope} (owner-keyed, entity
     *  fallback), mirroring the conversation-history layout. {@code null} when the companion id is unknown. */
    private com.player2.playerengine.memory.MemoryScope resolveMemoryScopeOrNull() {
        if (this.characterId == null || this.characterId.isBlank()) {
            return null;
        }
        UUID ownerUuid = resolveOwnerUuidOrNull(mod);
        if (ownerUuid != null) {
            return com.player2.playerengine.memory.MemoryScope.of(ownerUuid, this.characterId);
        }
        if (mod != null && mod.getPlayer() != null) {
            return com.player2.playerengine.memory.MemoryScope.ofEntityFallback(
                    mod.getPlayer().getUUID(), this.characterId);
        }
        return null;
    }

    public void clearHistory() {
        conversationHistory.clear();
    }

    public Event getGreetingEvent() {
        String suffix = " IMPORTANT: SINCE THIS IS THE FIRST MESSAGE, ONLY USE COMMAND `bodylang greeting`";
        if (conversationHistory.isLoadedFromFile()) {
            return (new InfoMessage("You want to welcome user back." + suffix));
        } else {
            return (new InfoMessage(character.greetingInfo() + suffix));
        }
    }

    /**
     * Builds the FIRST_MEETING/RETURNING model event: a true greeting when this is the first-ever
     * spawn for this per-world history (no history file yet), otherwise a "&lt;owner&gt; has respawned
     * you" return line.
     * <p>
     * PRECONDITION: never call this for a DEATH_RESPAWN spawn. A bot that just died and was
     * auto-revived must go through {@link #getDeathRevivalEvent(String)} so the model is told it died
     * (and how). Routing a death respawn here would silently convert death context into a greeting
     * for any bot with no history file yet (first-tick death) -- exactly the truthfulness bug
     * (DESIGN.md §3) this feature exists to prevent. The caller dispatch in
     * {@code AutomatoneEntity.init} guarantees this: DEATH_RESPAWN goes to {@code sendDeathRevival},
     * only FIRST_MEETING/RETURNING reach {@code sendReturnMessage} -> here.
     */
    public Event getReturnEvent(String ownerName) {
        // First-ever meeting still greets (true greeting), even on a RETURNING reason.
        if (!conversationHistory.isLoadedFromFile()) {
            return getGreetingEvent(); // greetingInfo + greeting bodylang suffix
        }
        String who = (ownerName == null || ownerName.isBlank()) ? "Your owner" : ownerName;
        // Q3 default: no forced bodylang suffix on return.
        return new InfoMessage(who + " has respawned you. You are returning to them; do not greet as if "
            + "meeting for the first time.");
    }

    public Event getDeathRevivalEvent(String deathCause) {
        String cause = (deathCause == null || deathCause.isBlank()) ? "You died." : deathCause;
        // Q3 default: no forced bodylang suffix. Model-facing; instruct truthfulness (DESIGN.md §3).
        return new InfoMessage("You just died and the game automatically revived you. Death: " + cause
            + ". Do not claim you respawned yourself, and do not greet as if meeting for the first time;"
            + " you may briefly react to how you died.");
    }

    public Event dumpEventQueueToConversationHistoryAndReturnLastEvent(Deque<Event> eventQueue, Player2APIService player2apiService){
        Event lastEvent = null;
        java.util.List<String> committedTurns = new java.util.ArrayList<>();
        while(!eventQueue.isEmpty()){
            Event event = eventQueue.poll();
            String turn = event.getConversationHistoryString();
            conversationHistory.addUserMessage(turn, player2apiService);
            committedTurns.add(turn);
            lastEvent = event;
        }
        // Phase D memory ingestion (W3): self-gates on patron status + the master flag — a complete
        // no-op (zero LLM calls, zero writes) for non-patrons / when memory is off. Cheap to call
        // unconditionally; returns promptly (any extraction is scheduled async, never on the tick).
        com.player2.playerengine.memory.ingest.MemoryIngestionService.onCuratedTurnsReady(this.mod, committedTurns);
        return lastEvent;
    }
    public ConversationHistory getConversationHistoryWrappedWithStatus(String worldStatus, String agentStatus, String altoClefDebugMsgs, Player2APIService player2apiService, Optional<String> reminderString, Optional<String> validCommandsBlock){
        // Existing-arity delegate (no memory block) — byte-identical to pre-Phase-D.
        return getConversationHistoryWrappedWithStatus(worldStatus, agentStatus, altoClefDebugMsgs,
                player2apiService, reminderString, validCommandsBlock, Optional.empty());
    }

    /**
     * Phase D (W5) overload: threads the per-turn memory block into the throwaway wrapped copy so it
     * is injected at the user-tail (after {@code validCommands}) and never persisted. Non-patron /
     * empty → caller passes {@link Optional#empty()} → request byte-identical to today.
     */
    public ConversationHistory getConversationHistoryWrappedWithStatus(String worldStatus, String agentStatus, String altoClefDebugMsgs, Player2APIService player2apiService, Optional<String> reminderString, Optional<String> validCommandsBlock, Optional<String> memoryBlock){
        return this.conversationHistory
                .copyThenWrapLatestWithStatus(worldStatus, agentStatus, altoClefDebugMsgs, player2apiService, reminderString, validCommandsBlock, memoryBlock);
    }
    public void addAssistantMessage(String llmMessage, Player2APIService player2apiService){
        this.conversationHistory.addAssistantMessage(llmMessage, player2apiService);
    }

    public Optional<String> getLastAssistantContent() {
        return this.conversationHistory.getLastAssistantContent();
    }

    public Character getCharacter(){
        return this.character;
    }

    public void updateSystemPrompt(){
        String systemPrompt = Prompts.getAINPCSystemPrompt(character, mod.getCommandExecutor().allCommands(), mod.getOwnerUsername());
        conversationHistory.setBaseSystemPrompt(withRelationshipSuffix(systemPrompt));
    }

    /**
     * Updates the system prompt using a pre-built valid-commands block from {@code RagPromptBuilder}
     * rather than the full command list (Phase B3 live RAG path).
     *
     * @param validCommandsBlock formatted commands block from
     *        {@link com.player2.playerengine.retrieval.RagPromptBuilder#buildValidCommandsBlock}
     */
    public void updateSystemPromptWithBlock(String validCommandsBlock) {
        String block = validCommandsBlock != null ? validCommandsBlock : "";
        String systemPrompt = Prompts.getAINPCSystemPromptWithValidCommandsBlock(character, block, mod.getOwnerUsername());
        conversationHistory.setBaseSystemPrompt(withRelationshipSuffix(systemPrompt));
    }

    /**
     * Live RAG path: sets message 0 to the byte-stable base prompt with NO command list/section, so the
     * system message is byte-identical across turns. The per-turn retrieved command subset is delivered
     * in the latest user turn under the {@code validCommands} key (see
     * {@link ConversationHistory#copyThenWrapLatestWithStatus}), not in the system message.
     */
    public void updateSystemPromptStatic() {
        String systemPrompt = Prompts.getAINPCSystemPromptNoCommandsBlock(character, mod.getOwnerUsername());
        conversationHistory.setBaseSystemPrompt(withRelationshipSuffix(systemPrompt));
    }

    public void saveHistoryNow() {
        conversationHistory.saveNow();
    }

    public void reloadHistoryFromDisk() {
        conversationHistory.reloadNow();
        // After reload, ensure the system prompt is updated to match the current command list/owner.
        updateSystemPrompt();
    }

    public Path getConversationHistoryFile() {
        return this.conversationHistoryFile;
    }

    public String getCharacterId() {
        return this.characterId;
    }

    private static Path resolveWorldRootOrNull(PlayerEngineController mod) {
        if (mod == null || mod.getPlayer() == null) {
            return null;
        }
        try {
            MinecraftServer server = Objects.requireNonNull(mod.getPlayer().level().getServer(), "server");
            return server.getWorldPath(LevelResource.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Canonical: {@code player2npc/persistentdata/owners/<ownerUuid>/<characterId>/conversation.jsonl}.
     * If owner is unknown, uses legacy entity-UUID segment (same as pre-owner layout).
     */
    private static Path getConversationHistoryFileOrNull(PlayerEngineController mod, Path worldRoot, String characterId) {
        if (mod == null || mod.getPlayer() == null) return null;
        if (characterId == null || characterId.isBlank()) return null;
        if (worldRoot == null) return null;

        try {
            // Resolve the STABLE owner UUID (independent of whether the owner ServerPlayer is attached at
            // construction time). The owner entity is frequently null at summon — which previously dropped
            // this to the transient entity-UUID path below, so EVERY re-summon got a fresh empty
            // conversation file: the bot lost all prior-session memory AND greeted as if meeting for the
            // first time (isLoadedFromFile()==false). Resolving via the owner USERNAME (always known) keeps
            // the canonical owners/<ownerUuid>/<characterId>/ path stable across sessions, matching the
            // per-owner inventory/settings layout.
            UUID ownerUuid = resolveOwnerUuidOrNull(mod);
            if (ownerUuid != null) {
                return worldRoot
                        .resolve("player2npc")
                        .resolve("persistentdata")
                        .resolve("owners")
                        .resolve(ownerUuid.toString())
                        .resolve(characterId)
                        .resolve("conversation.jsonl");
            }
            // Last resort only (owner truly unresolvable): legacy entity-UUID segment — not stable across
            // re-summons, but better than no persistence for the current session.
            UUID entityUuid = mod.getPlayer().getUUID();
            return worldRoot
                    .resolve("player2npc")
                    .resolve("persistentdata")
                    .resolve(entityUuid.toString())
                    .resolve(characterId)
                    .resolve("conversation.jsonl");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolve the bot's owner UUID stably, NOT depending on the owner {@code ServerPlayer} being attached
     * at construction time. Tries the direct owner entity first, then resolves the owner username (always
     * known via {@code getOwnerUsername()}) through the shared {@link Player2NpcInitiatorUuidResolve}
     * (online list -> server username cache -> profile cache) — the same stable resolution the per-owner
     * inventory/settings paths rely on. Returns {@code null} only when the owner cannot be resolved at all.
     */
    private static UUID resolveOwnerUuidOrNull(PlayerEngineController mod) {
        try {
            if (mod.getOwner() != null) {
                return mod.getOwner().getUUID();
            }
        } catch (Exception ignored) {
            // fall through to username-based resolution
        }
        try {
            String ownerName = mod.getOwnerUsername();
            if (ownerName != null && !ownerName.isBlank() && mod.getPlayer() != null) {
                MinecraftServer server = mod.getPlayer().level().getServer();
                if (server != null) {
                    return Player2NpcInitiatorUuidResolve.resolve(server, ownerName);
                }
            }
        } catch (Exception ignored) {
            // owner UUID unresolvable
        }
        return null;
    }

    /**
     * If the UUID-scoped file is missing, copy from older locations (do not delete sources).
     */
    private static void migrateLegacyHistoryIfPresent(Character character, String characterId, Path worldRoot, Path newHistoryFile, PlayerEngineController mod) {
        if (newHistoryFile == null) return;
        try {
            if (Files.exists(newHistoryFile)) return;

            if (newHistoryFile.getParent() != null) {
                Files.createDirectories(newHistoryFile.getParent());
            }

            // 1) Legacy: global config dir, name-keyed .txt (duplicate-name or canonical slug)
            if (character != null) {
                String characterName = character.name();
                if (characterName != null && !characterName.isBlank()) {
                    Path legacyFile = resolveLegacyGlobalHistoryFile(characterName);
                    if (legacyFile != null) {
                        Files.copy(legacyFile, newHistoryFile);
                    }
                }
            }
            if (Files.exists(newHistoryFile)) return;

            // 2) Intermediate: per-world persistentdata/<characterId>/conversation.jsonl (no entity UUID segment)
            if (worldRoot != null && characterId != null && !characterId.isBlank()) {
                Path intermediate = worldRoot
                        .resolve("player2npc")
                        .resolve("persistentdata")
                        .resolve(characterId)
                        .resolve("conversation.jsonl");
                if (Files.exists(intermediate)) {
                    Files.copy(intermediate, newHistoryFile);
                }
            }
            if (Files.exists(newHistoryFile)) return;

            // 3) Old entity-scoped path (same layout whether or not owner is now known)
            if (worldRoot != null && characterId != null && !characterId.isBlank() && mod.getPlayer() != null) {
                UUID entityUuid = mod.getPlayer().getUUID();
                Path oldEntityScoped = worldRoot
                        .resolve("player2npc")
                        .resolve("persistentdata")
                        .resolve(entityUuid.toString())
                        .resolve(characterId)
                        .resolve("conversation.jsonl");
                if (Files.exists(oldEntityScoped)) {
                    Files.copy(oldEntityScoped, newHistoryFile);
                }
            }
        } catch (Exception e) {
            // Best-effort migration; ignore failures.
        }
    }

    /** Legacy config-dir history files (pre-UUID paths). */
    private static Path resolveLegacyGlobalHistoryFile(String characterName) {
        String slug = characterName.replaceAll("\\s+", "_");
        Path configDir = DirUtil.getConfigDir();
        Path duplicateNameFile = configDir.resolve(slug + "_" + slug + ".txt");
        if (Files.exists(duplicateNameFile)) {
            return duplicateNameFile;
        }

        Path canonicalFile = configDir.resolve(slug + "_conversation.txt");
        if (Files.exists(canonicalFile)) {
            return canonicalFile;
        }

        return null;
    }
}
