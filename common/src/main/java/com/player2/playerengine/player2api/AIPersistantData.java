
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
        if (this.conversationHistoryFile != null) {
            migrateLegacyHistoryIfPresent(character, this.characterId, worldRoot, this.conversationHistoryFile, mod);
            this.conversationHistory = new ConversationHistory(systemPrompt, this.conversationHistoryFile);
        } else {
            // Fallback to non-persistent history if we can't resolve world root or characterId.
            this.conversationHistory = new ConversationHistory(systemPrompt);
        }
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

    public Event dumpEventQueueToConversationHistoryAndReturnLastEvent(Deque<Event> eventQueue, Player2APIService player2apiService){
        Event lastEvent = null;
        while(!eventQueue.isEmpty()){
            Event event = eventQueue.poll();
            conversationHistory.addUserMessage(event.getConversationHistoryString(), player2apiService);
            lastEvent = event;
        }
        return lastEvent;
    }
    public ConversationHistory getConversationHistoryWrappedWithStatus(String worldStatus, String agentStatus, String altoClefDebugMsgs, Player2APIService player2apiService, Optional<String> reminderString, Optional<String> validCommandsBlock){
        return this.conversationHistory
                .copyThenWrapLatestWithStatus(worldStatus, agentStatus, altoClefDebugMsgs, player2apiService, reminderString, validCommandsBlock);
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
        conversationHistory.setBaseSystemPrompt(systemPrompt);
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
        conversationHistory.setBaseSystemPrompt(systemPrompt);
    }

    /**
     * Live RAG path: sets message 0 to the byte-stable base prompt with NO command list/section, so the
     * system message is byte-identical across turns. The per-turn retrieved command subset is delivered
     * in the latest user turn under the {@code validCommands} key (see
     * {@link ConversationHistory#copyThenWrapLatestWithStatus}), not in the system message.
     */
    public void updateSystemPromptStatic() {
        String systemPrompt = Prompts.getAINPCSystemPromptNoCommandsBlock(character, mod.getOwnerUsername());
        conversationHistory.setBaseSystemPrompt(systemPrompt);
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
            if (mod.getOwner() != null) {
                UUID ownerUuid = mod.getOwner().getUUID();
                return worldRoot
                        .resolve("player2npc")
                        .resolve("persistentdata")
                        .resolve("owners")
                        .resolve(ownerUuid.toString())
                        .resolve(characterId)
                        .resolve("conversation.jsonl");
            }
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
