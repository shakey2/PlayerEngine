package com.player2.playerengine.player2api;

import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.player2api.BotBlacklistPolicy;
import com.player2.playerengine.player2api.UserBlacklistPolicy;
import com.player2.playerengine.player2api.AgentSideEffects.CommandExecutionStopReason;
import com.player2.playerengine.player2api.Event.InfoMessage;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.config.Player2ServerRuntimeConfig;
import com.player2.playerengine.player2api.status.AgentStatus;
import com.player2.playerengine.player2api.status.StatusUtils;
import com.player2.playerengine.player2api.status.WorldStatus;
import com.player2.playerengine.player2api.utils.Utils;
import com.player2.playerengine.retrieval.RagIndex;
import com.player2.playerengine.retrieval.RagPromptBuilder;
import com.player2.playerengine.retrieval.RetrievalHit;
import com.player2.playerengine.retrieval.ToolRetriever;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;

public class AgentConversationData {

    private static short MAX_EVENT_QUEUE_SIZE = 10;

    public static final Logger LOGGER = LogManager.getLogger();

    private final PlayerEngineController mod;

    private final Deque<Event> eventQueue = new ConcurrentLinkedDeque<>();
    private long lastProcessTime = 0L;
    private boolean isProcessing = false;
    private boolean enabled = true;

    // seperating these to be safe:
    private boolean isGreetingResponse = true;
    private boolean shouldIgnoreGreetingDance = true;

    private MessageBuffer playerEngineMsgBuffer = new MessageBuffer(10);

    /** Latest prompting username from the current batch (prompter-pays billing). */
    private String chainInitiatorUsername;

    // --- Phase B3: per-turn RAG prompt cache ---
    /** Most recent retrieval hits; reused when the goal text is short or the batch has no user message. */
    private List<RetrievalHit> cachedRetrievalHits = List.of();
    /** Hash of the last injected tool-id set; used to skip redundant system-prompt rewrites. */
    private int cachedPromptIdHash = 0;
    /** Suppress repeated warnings when the RAG index is not yet initialised for this bot. */
    private boolean ragFallbackWarnedOnce = false;

    /**
     * Per-bot TTS pacing: nanoTime() after which this specific bot is allowed to start a new
     * LLM/conversation round. Replaces the previous server-wide TTS lock so other bots can be
     * processed while this one is still "speaking" client-side.
     */
    private volatile long ttsCooldownUntilNanos = 0L;

    /** Approx TTS characters/second (matches TTSManager). */
    private static final int TTS_CHARS_PER_SECOND = 25;

    public AgentConversationData(PlayerEngineController mod) {
        this.mod = mod;
    }

    public String getChainInitiatorUsername() {
        return chainInitiatorUsername;
    }

    // ## Processing

    // 0 => should not process,
    // otherwise gives a number that increases based on higher priority
    // (for now it is #ns from last processing time)
    public long getPriority() {
        if (!enabled || isProcessing || eventQueue.isEmpty()) {
            return 0;
        }
        // Self-pace: don't start a new LLM round while this bot's last response is still
        // being spoken client-side. Other bots remain free to process during this window.
        if (System.nanoTime() < ttsCooldownUntilNanos) {
            return 0;
        }
        return System.nanoTime() - lastProcessTime;
    }

    /**
     * Read-only billing snapshot for the next dispatch round. Mirrors the initiator selection
     * used inside {@link #process} so the bucket key the dispatcher picks matches the eventual
     * API call. Side-effect free; safe to call from the conversation dispatch loop.
     */
    public Player2PayerResolution.ApiBillingContext previewBilling() {
        String lastUserInBatch = null;
        for (Event e : eventQueue) {
            if (e instanceof Event.UserMessage um) {
                lastUserInBatch = um.userName();
            }
        }
        String relayInitiator = lastUserInBatch != null ? lastUserInBatch : chainInitiatorUsername;
        return Player2PayerResolution.resolve(mod, relayInitiator,
                mod.getPlayer2APIService().getClientId());
    }

    /**
     * Record an estimated speech duration for this bot's most recent message and start a per-bot
     * cooldown. Called from {@link AgentSideEffects#onEntityMessage} right after submitting the
     * TTS payload so dispatch defers this bot (only) for the playback window.
     */
    public void markSpeakingFor(String message) {
        if (message == null) {
            return;
        }
        int waitTimeSec = (int) Math.ceil(message.length() / (double) TTS_CHARS_PER_SECOND) + 1;
        long waitNanos = TimeUnit.SECONDS.toNanos(waitTimeSec);
        ttsCooldownUntilNanos = System.nanoTime() + waitNanos;
    }

    /** Test/clear helper: drop any pending self-pace (e.g. on queue clear / disconnect). */
    public void clearTtsCooldown() {
        ttsCooldownUntilNanos = 0L;
    }

    /** Clear pending events and per-round flags without disturbing persisted history. */
    public void resetForClear() {
        eventQueue.clear();
        isProcessing = false;
        chainInitiatorUsername = null;
        clearTtsCooldown();
        cachedRetrievalHits = List.of();
        cachedPromptIdHash = 0;
    }

    // get LLM response and add to conversation history
    public void process(
            Consumer<Event.CharacterMessage> onCharacterEvent,
            Consumer<String> extOnErrMsg,
            LLMCompleter completer) {

        if (isProcessing) {
            LOGGER.warn("Called queueData.process even though it was already processing! this should not happen");
            return;
        }
        if (eventQueue.isEmpty()) {
            LOGGER.warn("queueData.process called on empty event queue! this should not happen");
            return;
        }

        Consumer<String> onErrMsg = errMsg -> {
            this.isProcessing = false;
            extOnErrMsg.accept(errMsg);
        };

        this.lastProcessTime = System.nanoTime();
        this.isProcessing = true;

        String lastUserInBatch = null;
        for (Event e : eventQueue) {
            if (e instanceof Event.UserMessage um) {
                lastUserInBatch = um.userName();
            }
        }
        if (lastUserInBatch != null) {
            chainInitiatorUsername = lastUserInBatch;
        }

        final String relayInitiator = lastUserInBatch != null ? lastUserInBatch : chainInitiatorUsername;
        if (relayInitiator != null && !relayInitiator.isBlank()) {
            MinecraftServer srv = mod.getPlayer().getServer();
            if (srv != null && BotBlacklistPolicy.isBlocked(srv, relayInitiator, this)) {
                LOGGER.info("Skipping LLM/API: bot blacklist blocks initiator={} for bot={}", relayInitiator, getName());
                eventQueue.clear();
                this.isProcessing = false;
                return;
            }
            if (srv != null && UserBlacklistPolicy.isBlocked(srv, relayInitiator, this)) {
                LOGGER.info("Skipping LLM/API: user blacklist blocks initiator={} for bot={}", relayInitiator, getName());
                eventQueue.clear();
                this.isProcessing = false;
                return;
            }
        }

        Player2PayerResolution.ApiBillingContext billing = Player2PayerResolution.resolve(mod, chainInitiatorUsername,
                mod.getPlayer2APIService().getClientId());
        mod.getPlayer2APIService().setActiveBillingContext(billing);
        if (billing.onlinePayer() == null && !billing.useStoredToken()) {
            this.isProcessing = false;
            onErrMsg.accept("Player2: no billing player/token available for this API request.");
            return;
        }

        // --- Phase B3: capture last user message text before the queue is drained ---
        Event.UserMessage lastUserMsgForRag = null;
        for (Event e : eventQueue) {
            if (e instanceof Event.UserMessage um) {
                lastUserMsgForRag = um;
            }
        }

        // prepare conversation history for LLM call
        Event lastEvent = mod.getAIPersistantData().dumpEventQueueToConversationHistoryAndReturnLastEvent(eventQueue,
                mod.getPlayer2APIService());
        Optional<String> reminderString = getReminderStringFromLastEvent(lastEvent);

        // --- Phase B3: update system prompt with RAG-retrieved commands ---
        maybeUpdateRagSystemPrompt(lastUserMsgForRag);

        // remove all invalid npcs:
        String defaultReminderString = " | REMEMBER TO OUTPUT ONLY VALID JSON OUTPUT";
        reminderString = reminderString.map(a -> a + defaultReminderString);
        reminderString = Optional.of(reminderString.orElse(defaultReminderString));

        String agentStatus = AgentStatus.fromMod(this.mod).toString();
        String worldStatus = WorldStatus.fromMod(this.mod).toString();
        String altoClefDebugMsgs = this.playerEngineMsgBuffer.dumpAndGetString();
        ConversationHistory historyWithWrappedStatus = mod.getAIPersistantData()
                .getConversationHistoryWrappedWithStatus(worldStatus, agentStatus, altoClefDebugMsgs,
                        mod.getPlayer2APIService(), reminderString);

        LOGGER.info("[AICommandBridge/processChatWithAPI]: Calling LLM: history={}",
                new Object[] { historyWithWrappedStatus.toString() });

        Consumer<JsonObject> onLLMResponse = jsonResp -> {
            String llmMessage = Utils.getStringJsonSafely(jsonResp, "message");
            String command = this.isGreetingResponse ? "bodylang greeting"
                    : Utils.getStringJsonSafely(jsonResp, "command");
            this.isGreetingResponse = false;
            String previousAssistant = mod.getAIPersistantData().getLastAssistantContent().orElse("");
            boolean redundantAfterInfo = lastEvent instanceof Event.InfoMessage
                    && ConversationHistory.isRedundantAssistantAfterInfo(previousAssistant, llmMessage);
            if (redundantAfterInfo) {
                LOGGER.info(
                        "[AICommandBridge/processCharWithAPI]: Suppressing duplicate assistant chat after Info (command feedback) round");
                llmMessage = "";
            }
            if (llmMessage == null) {
                llmMessage = "";
            }
            LOGGER.info("[AICommandBridge/processCharWithAPI]: Processed LLM repsonse: message={} command={}",
                    llmMessage, command);
            try {
                if (!llmMessage.isEmpty() || command != null) {
                    mod.getAIPersistantData().addAssistantMessage(llmMessage, mod.getPlayer2APIService());
                    onCharacterEvent.accept(new Event.CharacterMessage(llmMessage, command, this, relayInitiator));
                } else {
                    LOGGER.warn(
                            "[AICommandBridge/processChatWithAPI/onLLMResponse]: Generated null llm message and command");
                }
            } catch (Exception e) {
                LOGGER.error("[AICommandBridge/processChatWithAPI/onLLMRepsonse: ERROR RUNNING SIDE EFFECTS, errMsg={}",
                        e.getMessage());
            } finally {
                this.isProcessing = false;
            }
        };
        completer.processToJson(mod.getPlayer2APIService(), historyWithWrappedStatus, onLLMResponse, onErrMsg, true, AiTaskClass.DECISION);
    }

    private boolean isEventDuplicateOfLastMessage(Event evt) {
        boolean isDuplicate = eventQueue.peekLast() != null && eventQueue.peekLast().equals(evt);
        if (isDuplicate) {
            LOGGER.warn("[EventQueueData]: evt={} was added twice!", evt.getConversationHistoryString());
            return true;
        }
        return false;
    }

    private void addEventToQueue(Event event) {
        if (isEventDuplicateOfLastMessage(event)) {
            return; // skip
        }
        if (eventQueue.size() > MAX_EVENT_QUEUE_SIZE) {
            eventQueue.removeFirst();
        }
        LOGGER.info("queue for UUID={} name={} adding event={} ", getUUID(), getName(), event);
        eventQueue.add(event);
    }

    private Optional<String> getReminderStringFromLastEvent(Event lastEvent) {
        if (lastEvent instanceof Event.UserMessage) {
            return Optional.of((((Event.UserMessage) lastEvent).userName().equals(getMod().getOwnerUsername())
                    ? Prompts.reminderOnOwnerMsg
                    : Prompts.reminderOnOtherUSerMsg) + " " + Prompts.generalConversationReminder);
        }
        if (lastEvent instanceof Event.CharacterMessage) {
            return Optional.of(Prompts.reminderOnAIMsg + " " + Prompts.generalConversationReminder);
        }
        return Optional.of(Prompts.generalConversationReminder);
    }

    // --- Phase B3: per-turn RAG helpers ---

    /**
     * Updates the NPC system prompt with a RAG-retrieved command set for the given turn.
     *
     * <p>Skips retrieval on greeting turns and when the goal text is too short.
     * Reuses cached hits when the batch contains only InfoMessage events.
     * Falls back to the full command list when the RAG index is not initialised,
     * controlled by {@code ragFallbackToFullList}.
     */
    private void maybeUpdateRagSystemPrompt(Event.UserMessage lastUserMsgForRag) {
        Player2ServerRuntimeConfig config = Player2ServerConfigHolder.get();
        if (!config.isRagLiveEnabled()) {
            return;
        }

        // Greeting turn: inject the always-include set only (no content-based retrieval yet).
        if (isGreetingResponse) {
            updateSystemPromptAlwaysIncludeOnly();
            LOGGER.debug("[RAG] skip retrieval: greeting turn for bot={}", getName());
            return;
        }

        // InfoMessage-only batch or autonomous step: reuse cached hits from the previous user turn.
        if (lastUserMsgForRag == null) {
            applyRagPromptFromCache(config);
            return;
        }

        // Short goal text: not enough signal for BM25 — reuse cached hits.
        String goalText = lastUserMsgForRag.message();
        if (!RagPromptBuilder.hasSubstantiveGoalText(goalText, config.getRagMinGoalCharsClamped())) {
            LOGGER.debug("[RAG] skip retrieval: short goal for bot={}", getName());
            applyRagPromptFromCache(config);
            return;
        }

        // Resolve owner-scoped retriever.
        MinecraftServer server = mod.getPlayer().getServer();
        UUID ownerUuid = mod.getOwner() != null ? mod.getOwner().getUUID() : null;
        ToolRetriever retriever = RagIndex.getForOwner(server, ownerUuid);

        if (retriever == null) {
            if (!ragFallbackWarnedOnce) {
                LOGGER.warn("[RAG] retriever null for bot={} owner={}; set ragLiveEnabled=false to silence. "
                        + "Falling back to full command list.", getName(), ownerUuid);
                ragFallbackWarnedOnce = true;
            }
            if (config.isRagFallbackToFullList()) {
                mod.getAIPersistantData().updateSystemPrompt();
            } else {
                updateSystemPromptAlwaysIncludeOnly();
            }
            return;
        }

        List<RetrievalHit> hits = retriever.retrieve(goalText, config.getRagTopKClamped(), null);

        if (hits.isEmpty()) {
            LOGGER.debug("[RAG] empty retrieval for goal=\"{}\" bot={}", goalText, getName());
            cachedRetrievalHits = hits;
            cachedPromptIdHash = 0;
            if (config.isRagFallbackToFullList()) {
                mod.getAIPersistantData().updateSystemPrompt();
            } else {
                updateSystemPromptAlwaysIncludeOnly();
            }
            return;
        }

        // Skip prompt rewrite when the retrieved tool set hasn't changed since last turn.
        int newHash = RagPromptBuilder.toolIdSetHash(RagPromptBuilder.ALWAYS_INCLUDE_IDS, hits);
        if (newHash == cachedPromptIdHash) {
            LOGGER.debug("[RAG] same tool set (hash={}), skipping prompt update for bot={}", newHash, getName());
            return;
        }

        cachedRetrievalHits = hits;
        cachedPromptIdHash = newHash;

        String block = RagPromptBuilder.buildValidCommandsBlock(
                retriever.getRegistry(), hits, mod.getCommandExecutor(), RagPromptBuilder.ALWAYS_INCLUDE_IDS);
        LOGGER.debug("[RAG] injecting {} hits into prompt for goal=\"{}\" bot={}", hits.size(), goalText, getName());
        mod.getAIPersistantData().updateSystemPromptWithBlock(block);
    }

    private void applyRagPromptFromCache(Player2ServerRuntimeConfig config) {
        if (cachedRetrievalHits.isEmpty()) {
            // No prior retrieval this session; use full list or always-include only.
            if (config.isRagFallbackToFullList()) {
                mod.getAIPersistantData().updateSystemPrompt();
            } else {
                updateSystemPromptAlwaysIncludeOnly();
            }
        }
        // Otherwise the existing system prompt already contains the cached set — nothing to do.
    }

    private void updateSystemPromptAlwaysIncludeOnly() {
        ToolRetriever global = RagIndex.getGlobal();
        if (global == null) {
            mod.getAIPersistantData().updateSystemPrompt();
            return;
        }
        String block = RagPromptBuilder.buildValidCommandsBlock(
                global.getRegistry(), List.of(), mod.getCommandExecutor(), RagPromptBuilder.ALWAYS_INCLUDE_IDS);
        if (block.isBlank()) {
            mod.getAIPersistantData().updateSystemPrompt();
            return;
        }
        mod.getAIPersistantData().updateSystemPromptWithBlock(block);
    }

    // --- end B3 ---

    public void onEvent(Event event) {
        addEventToQueue(event);
    }

    public void onAICharacterMessage(Event.CharacterMessage msg) {
        boolean comingFromThisCharacter = msg.sendingCharacterData().getUUID().equals(getUUID());
        // is our character <=> dont add because we will already have added assistant
        // msg
        if (comingFromThisCharacter) {
            return;
        }
        eventQueue.add(msg);
    }

    public void onGreeting() {
        // queue up greeting
        addEventToQueue(mod.getAIPersistantData().getGreetingEvent());
    }

    public void onCommandFinish(AgentSideEffects.CommandExecutionStopReason stopReason) {
        LOGGER.info("on command finish for cmd={}", stopReason.commandName());
        if (stopReason instanceof CommandExecutionStopReason.Finished) {
            LOGGER.info("on command={} finish case", stopReason.commandName());
            if (shouldIgnoreGreetingDance && stopReason.commandName().contains("bodylang greeting")) {
                LOGGER.info("Skipping on command finish because should ignore greeting dance");
                // ignore first greeting command finish:
                shouldIgnoreGreetingDance = false;
                return;
            } else {
                shouldIgnoreGreetingDance = false;
            }
            if (eventQueue.isEmpty()) {

                LOGGER.info("adding cmd={} to queue because it finished and queue not empty", stopReason.commandName());
                addEventToQueue(new InfoMessage(String.format(
                        "Command feedback: %s finished running. What shall we do next? If no new action is needed to finish user's request, generate empty command `\"\"`.",
                        stopReason.commandName())));
            } else {
                LOGGER.info("Skipping command stop for cmd={} because queue not empty", stopReason.commandName());
            }
        } else if (stopReason instanceof CommandExecutionStopReason.Error) {
            LOGGER.info("adding cmd={} to queue because it errored", stopReason.commandName());
            addEventToQueue(new InfoMessage(String.format(
                    "Command feedback: %s FAILED. The error was %s.",
                    stopReason.commandName(),
                    ((CommandExecutionStopReason.Error) stopReason).errMsg())));
        } else {
            LOGGER.info("Skipping command stop for cmd={} because it was cancelled", stopReason.commandName());
        }
        // (if canceled dont modify queue)
    }

    // Utils:
    public float getDistance(UUID target) {
        return StatusUtils.getDistanceToUUID(mod, target);
    }

    public UUID getUUID() {
        return mod.getPlayer().getUUID();
    }

    public PlayerEngineController getMod() {
        return mod;
    }

    public boolean isOwner(UUID playerToCheck) {
        return mod.isOwner(playerToCheck);
    }

    public LivingEntity getEntity() {
        return mod.getPlayer();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Character getCharacter() {
        return mod.getAIPersistantData().getCharacter();
    }

    public Player2APIService getPlayer2apiService() {
        return mod.getPlayer2APIService();
    }

    public String getName() {
        return getCharacter().shortName();
    }

}