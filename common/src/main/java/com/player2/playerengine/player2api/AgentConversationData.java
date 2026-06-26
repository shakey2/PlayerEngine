package com.player2.playerengine.player2api;

import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
import com.player2.playerengine.retrieval.RetrievalConfidenceThresholds;
import com.player2.playerengine.retrieval.RetrievalHit;
import com.player2.playerengine.retrieval.RetrievalResult;
import com.player2.playerengine.retrieval.ToolRetriever;
import com.player2.playerengine.retrieval.learning.AliasLearnSuggestion;
import com.player2.playerengine.retrieval.learning.AliasLearningService;
import com.player2.playerengine.retrieval.learning.DeepCheckBudgetGate;
import com.player2.playerengine.retrieval.learning.DeepCheckRephraseService;
import com.player2.playerengine.retrieval.RagDeepSearchCommands;
import com.player2.playerengine.retrieval.learning.RagDeepCheckCoordinator;
import com.player2.playerengine.retrieval.learning.RagDeepCheckPipeline;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;

public class AgentConversationData {

    private static short MAX_EVENT_QUEUE_SIZE = 10;

    /**
     * Marker prefix for a {@code finishWithNote} note that carries an informational RESULT payload
     * (e.g. {@code locate_storage} coordinates) rather than a degradation. The command-finish prompt
     * strips it and frames the note as a neutral result instead of a "but:" degradation. Commands wrap
     * their payload with {@link com.player2.playerengine.commands.base.Command#finishWithInfo(String)}.
     */
    public static final String INFO_RESULT_NOTE_PREFIX = "[info-result] ";

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

    // --- Prefix-cache restructure: per-turn RAG command block relocated to the user tail ---
    /**
     * The RAG command block to inject into THIS turn's latest user message ({@code validCommands} key).
     * Null on session-stable branches (full-list / always-include / greeting), where the list stays in
     * the system message instead. Cleared per turn in {@link #resetB5TurnState()}.
     */
    private String pendingValidCommandsBlock = null;
    /**
     * Session-scoped cache of the last built RAG block string (NOT reset per turn). Reused by the
     * churn-gate skip and {@code applyRagPromptFromCache} so those turns can re-supply the same block to
     * the user tail without rebuilding (and without leaving the model with an empty command set).
     */
    private String cachedValidCommandsBlock = null;

    // --- Prefix-cache restructure: status locals captured once per turn for follow-up tail rebuilds ---
    /** worldStatus/agentStatus/gameDebugMessages/reminders captured at first-pass assembly (L290-295). */
    private String turnWorldStatus = "";
    private String turnAgentStatus = "";
    private String turnAltoClefDebugMsgs = "";
    private Optional<String> turnReminderString = Optional.empty();
    /** Suppress repeated warnings when the RAG index is not yet initialised for this bot. */
    private boolean ragFallbackWarnedOnce = false;

    // --- Phase B5: per-turn RAG / deep-check state ---
    private List<RetrievalHit> activeRetrievalHits = List.of();
    private Set<String> activePromptToolIds = Set.of();
    private String lastRagGoalText = "";
    private Optional<AliasLearnSuggestion> pendingLearnSuggestion = Optional.empty();
    private int deepCheckAttemptsThisTurn = 0;
    private boolean postDecisionRetryAttempted = false;
    private String lastRagPromptSource = "first_pass";
    /** Audit trigger for alias learning: heuristic_weak, model_requested, post_decision_out_of_top_k. */
    private String lastDeepCheckTriggerReason = "";

    /**
     * Command name (normalized, no {@code @}) for which a "finished running" InfoMessage is already
     * queued or awaiting an LLM response. Prevents the same completion from re-triggering API rounds.
     */
    private String commandAwaitingFinishAck = null;

    /**
     * Consecutive LLM JSON parse failures since the last successful parse. Bounds the
     * "resend valid JSON" retry loop so a model that keeps emitting unparseable output does not
     * spin forever (DESIGN.md §3 reporting must not turn into an infinite re-prompt). Reset to 0 on
     * any successful response in {@link #handleLlmResponse}.
     */
    private int consecutiveParseFailures = 0;

    /** Max consecutive parse-failure re-prompts before giving up this chain and notifying the player only. */
    private static final int MAX_PARSE_RETRY = 2;

    /**
     * Transient (no NBT): how many consecutive non-silent replies this bot has made to peer
     * (CharacterMessage) turns without an intervening human/owner turn. Drives the count-aware
     * peer-talk reminder ({@link #getReminderStringFromLastEvent}). Reset on human activity; credited
     * (reset) when the bot chooses silence. See masterplan/peer-talk-restraint-plan.md.
     */
    private int consecutivePeerReplies = 0;

    /**
     * Per-bot TTS pacing: nanoTime() after which this specific bot is allowed to start a new
     * LLM/conversation round. Replaces the previous server-wide TTS lock so other bots can be
     * processed while this one is still "speaking" client-side.
     */
    private volatile long ttsCooldownUntilNanos = 0L;

    /** Approx TTS characters/second (matches TTSManager). */
    private static final int TTS_CHARS_PER_SECOND = 25;

    // --- Bodylang TTS-timed gestures: per-message marker state ---
    /**
     * Ordered, VALID-ONLY body-language boundaries for THIS bot's most recent message. Built by
     * {@link MarkerParser} in {@link #handleLlmResponse} and overwritten each message. This is the
     * authoritative server-side list the {@code segment_done} handler indexes into (by the same
     * valid-only numbering used on the wire), and that the fallback timer fires from. Invalid markers
     * are NOT stored here — they are reported to both audiences at parse time and never fire.
     */
    private volatile List<MarkerParser.SegmentBoundary> pendingSegmentActions = List.of();
    /**
     * The stripped text split at marker positions for THIS bot's most recent message (the wire chunk
     * list). Read by {@link AgentSideEffects#onEntityMessage} to build the {@code stream_tts} payload.
     */
    private volatile List<String> pendingChunks = List.of();
    /**
     * Raw tokens of INVALID markers from the most recent message (e.g. {@code "wave"}). Reported to
     * the model here at parse time (InfoMessage) and to the player in {@code onEntityMessage}
     * (Workstream 6 player-facing line). Overwritten each message; empty when all markers were valid.
     */
    private volatile List<String> pendingInvalidMarkers = List.of();
    /**
     * Per-bot fallback-timer cancel hook. Set by the WS4 dispatch path (server-side) when the safety
     * timer is scheduled; invoked by {@link #cancelFallbackTimer()} when {@code message_done} arrives so
     * a late prompter ACK does not double-clear / double-fire. {@code null} when no timer is pending.
     */
    private volatile Runnable fallbackTimerCancel = null;

    public AgentConversationData(PlayerEngineController mod) {
        this.mod = mod;
    }

    public String getChainInitiatorUsername() {
        return chainInitiatorUsername;
    }

    /** Valid-only ordered boundaries for the most recent message (segment_done indexes into this). */
    public List<MarkerParser.SegmentBoundary> getPendingSegmentActions() {
        return pendingSegmentActions;
    }

    /** Wire chunk list for the most recent message (read by onEntityMessage for the stream_tts payload). */
    public List<String> getPendingChunks() {
        return pendingChunks;
    }

    /** Invalid marker tokens from the most recent message (player-facing reporting in onEntityMessage). */
    public List<String> getPendingInvalidMarkers() {
        return pendingInvalidMarkers;
    }

    /**
     * Register a cancel hook for the WS4 server-side fallback timer (keyed by this bot). Replaces any
     * previously pending hook (a new dispatch supersedes the old timer).
     */
    public void setFallbackTimerCancel(Runnable cancel) {
        this.fallbackTimerCancel = cancel;
    }

    /**
     * Cancel the pending fallback timer if one is registered (idempotent). Called from the
     * {@code message_done} handler so the prompter ACK pre-empts the liveness backstop.
     */
    public void cancelFallbackTimer() {
        Runnable c = this.fallbackTimerCancel;
        this.fallbackTimerCancel = null;
        if (c != null) {
            c.run();
        }
    }

    /**
     * Workstream 6: reflect a partial-speech (degraded {@code message_done}) outcome into the MODEL's
     * feedback channel so the AI knows some of its speech did not play and cannot claim full success
     * (DESIGN.md §3). The player-facing line is broadcast separately at the {@code message_done} site.
     */
    public void reportPartialSpeechToModel() {
        addEventToQueue(new InfoMessage(
                "Note: part of your spoken reply did not play for the listener (a TTS chunk failed). "
                + "Do not claim you said everything; if it matters, you may briefly restate the key point."));
    }

    // ## Processing

    // 0 => should not process,
    // otherwise gives a number that increases based on higher priority
    // (for now it is #ns from last processing time)
    public long getTtsCooldownUntilNanos() {
        return ttsCooldownUntilNanos;
    }

    // --- Peer-talk restraint: deterministic fallback gate (Workstream 3) — NOT WIRED (future work) ---
    // STUB ONLY. The primary peer-talk mechanism is model-driven (the transient consecutivePeerReplies
    // counter + count-aware reminder, above/below). This block documents the planned deterministic
    // safety floor that is deliberately NOT implemented this run — no field, no config, no behavior here.
    //
    // When a future session is explicitly asked to enable the fallback (see
    // masterplan/peer-talk-restraint-plan.md Workstream 3), implement Option A (softest, recommended):
    //   - Add a transient `private long peerReplyCooldownUntilNanos = 0L;` mirroring ttsCooldownUntilNanos.
    //   - When the gate is enabled AND consecutivePeerReplies >= threshold, set the cooldown and have
    //     getPriority() return 0 (defer, NEVER discard) until it lifts — the peer message stays queued so
    //     the model still sees it (no model-unseen drop; preserves DESIGN.md §3 truthfulness).
    //   - Escalation rungs B (drop-after-expiry) and C (queue-injection block in onAICharacterMessage)
    //     are documented in the plan and carry a stronger truthfulness obligation; do not wire by default.
    // Config keys (define in Player2ServerRuntimeConfig / Player2ServerConfigHolder, ALL default OFF;
    // do NOT add them this run):
    //   - peerTalkRestraintHardGateEnabled = false   (boolean; master switch, off = model-driven only)
    //   - peerTalkRestraintGateThreshold   = 4       (clamp 2..20; streak at which the gate engages)
    //   - peerTalkRestraintCooldownSeconds = 8.0     (clamp 1.0..60.0; Option-A defer window)
    // Until then getPriority() below is unchanged (no peer-talk behavior).
    public long getPriority() {
        if (!enabled || isProcessing || eventQueue.isEmpty()) {
            return 0;
        }
        // Self-pace: don't start a new LLM round while this bot's last response is still
        // being spoken client-side.
        if (System.nanoTime() < ttsCooldownUntilNanos) {
            return 0;
        }
        // Listener-pace: defer until another bot's line at the head of the queue has finished
        // playing (estimated cooldown on the sender, or early-clear via tts_playback_done ACK).
        Event head = eventQueue.peek();
        if (head instanceof Event.CharacterMessage charMsg) {
            AgentConversationData sender = charMsg.sendingCharacterData();
            if (!sender.getUUID().equals(getUUID())
                    && System.nanoTime() < sender.getTtsCooldownUntilNanos()) {
                return 0;
            }
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
        cachedValidCommandsBlock = null;
        resetB5TurnState();
        commandAwaitingFinishAck = null;
        consecutiveParseFailures = 0;
        consecutivePeerReplies = 0;
    }

    private void resetB5TurnState() {
        activeRetrievalHits = List.of();
        activePromptToolIds = Set.of();
        pendingValidCommandsBlock = null;
        lastRagGoalText = "";
        pendingLearnSuggestion = Optional.empty();
        deepCheckAttemptsThisTurn = 0;
        postDecisionRetryAttempted = false;
        lastRagPromptSource = "first_pass";
        lastDeepCheckTriggerReason = "";
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
            // DESIGN.md §3: a model JSON parse failure must reach BOTH audiences, each tailored.
            //   - Model: reflect "your last reply was unparseable; re-send valid JSON" into the
            //     conversation feedback (an InfoMessage) so it retries truthfully next round.
            //   - Player: a concise human line — NEVER the raw com.google.gson exception string.
            // The raw payload is already logged in Utils.parseCleanedJson; do not surface it here.
            if (errMsg != null && errMsg.startsWith(
                    com.player2.playerengine.player2api.utils.LlmJsonParseException.SENTINEL)) {
                consecutiveParseFailures++;
                if (consecutiveParseFailures <= MAX_PARSE_RETRY) {
                    LOGGER.warn("[AICommandBridge]: LLM reply failed to parse as JSON for bot={} "
                            + "(attempt {}/{}); asking model to resend valid JSON and notifying player.",
                            getName(), consecutiveParseFailures, MAX_PARSE_RETRY);
                    addEventToQueue(new InfoMessage(
                            "Your previous reply could not be read because it was not valid JSON. "
                            + "Resend ONLY a single valid JSON object with the \"message\" and \"command\" fields, "
                            + "no extra text, no markdown code fences."));
                    extOnErrMsg.accept(getName() + " had trouble understanding that — let me try again.");
                } else {
                    // Repeated unparseable output: stop re-prompting (avoid a token-burning loop) and
                    // tell the player plainly. The model already received the corrective InfoMessage on
                    // the prior attempts; do not queue another (DESIGN.md §3 — both audiences served).
                    LOGGER.error("[AICommandBridge]: LLM reply still unparseable after {} retries for bot={}; "
                            + "giving up this chain.", MAX_PARSE_RETRY, getName());
                    consecutiveParseFailures = 0;
                    // Give-up aborts this chain WITHOUT the model ever responding to the round, so
                    // acknowledgeCommandFinishRoundIfComplete (in handleLlmResponse's finally) never runs.
                    // If the aborted round was a command-finish prompt, a stale commandAwaitingFinishAck
                    // would survive and suppress the NEXT command's finish as a false "duplicate" — the
                    // dead-callback-idle condition for subsequent commands. Clear it here so give-up does
                    // not strand the ack. (Safe: a true same-execution double-fire is still guarded while
                    // isProcessing is true / the finish InfoMessage is queued.)
                    commandAwaitingFinishAck = null;
                    extOnErrMsg.accept(getName() + " couldn't respond clearly just now. Please try again.");
                }
                return;
            }
            extOnErrMsg.accept(errMsg);
        };

        this.lastProcessTime = System.nanoTime();
        this.isProcessing = true;
        resetB5TurnState();

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
        // Capture the per-turn status locals so mid-turn follow-ups (deep-check / post-decision retry)
        // can rebuild the wrapped copy with the relocated command block WITHOUT re-draining the debug
        // buffer (dumpAndGetString is draining) and without re-deriving world/agent/reminder status.
        this.turnWorldStatus = worldStatus;
        this.turnAgentStatus = agentStatus;
        this.turnAltoClefDebugMsgs = altoClefDebugMsgs;
        this.turnReminderString = reminderString;
        ConversationHistory historyWithWrappedStatus = mod.getAIPersistantData()
                .getConversationHistoryWrappedWithStatus(worldStatus, agentStatus, altoClefDebugMsgs,
                        mod.getPlayer2APIService(), reminderString, Optional.ofNullable(pendingValidCommandsBlock));

        LOGGER.info("[AICommandBridge/processChatWithAPI]: Calling LLM: history={}",
                new Object[] { historyWithWrappedStatus.toString() });

        final Event.UserMessage ragUserMsg = lastUserMsgForRag;
        Consumer<JsonObject> onLLMResponse = jsonResp -> handleLlmResponse(
                jsonResp, lastEvent, relayInitiator, onCharacterEvent, onErrMsg, completer,
                historyWithWrappedStatus, ragUserMsg, false, false);
        completer.processToJson(mod.getPlayer2APIService(), historyWithWrappedStatus, onLLMResponse, onErrMsg, true, AiTaskClass.DECISION);
    }

    private boolean isEventDuplicateOfLastMessage(Event evt) {
        boolean isDuplicate = eventQueue.peekLast() != null && eventQueue.peekLast().equals(evt);
        if (isDuplicate) {
            if (evt instanceof Event.UserMessage um && um.fromVoice()) {
                LOGGER.warn("STT/voice: duplicate user message dropped for companion={} preview=\"{}\"",
                        getName(), com.player2.playerengine.player2api.utils.SttLogging.messagePreview(um.message()));
            } else {
                LOGGER.warn("[EventQueueData]: evt={} was added twice!", evt.getConversationHistoryString());
            }
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
            // This plan (masterplan/peer-talk-restraint-plan.md) OWNS the CharacterMessage reminder text
            // (the single per-turn reminder slot for a peer head event). Do NOT overwrite this slot from
            // another track — extend Prompts.reminderOnAIMsg(int) instead. The streak read here reflects
            // the START-OF-TURN value: this method is called at process() (~:424) BEFORE handleLlmResponse
            // increments/resets consecutivePeerReplies for this turn, so the reminder count is correct.
            return Optional.of(Prompts.reminderOnAIMsg(getConsecutivePeerReplies())
                    + " " + Prompts.generalConversationReminder);
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

        lastRagGoalText = goalText;
        int topK = config.getRagTopKClamped();
        RetrievalConfidenceThresholds thresholds = RetrievalConfidenceThresholds.fromConfig(config);
        RetrievalResult firstPass = retriever.retrieveWithConfidence(goalText, topK, null, thresholds);

        Player2PayerResolution.ApiBillingContext billing = Player2PayerResolution.resolve(
                mod, chainInitiatorUsername, mod.getPlayer2APIService().getClientId());
        RagDeepCheckCoordinator.RetryMergeResult merged = RagDeepCheckCoordinator.maybeImproveRetrieval(
                retriever,
                goalText,
                topK,
                null,
                thresholds,
                firstPass,
                mod.getPlayer2APIService(),
                mod.getCommandExecutor(),
                billing,
                config.isEnableDeepCheckRephrase());
        if (merged.deepCheckAttempted()) {
            deepCheckAttemptsThisTurn++;
            lastDeepCheckTriggerReason = "heuristic_weak";
        }
        lastRagPromptSource = merged.promptSource();
        pendingLearnSuggestion = merged.learnSuggestion();
        List<RetrievalHit> hits = merged.activeResult().hits();

        if (hits.isEmpty()) {
            LOGGER.debug("[RAG] empty retrieval for goal=\"{}\" bot={} source={}", goalText, getName(), lastRagPromptSource);
            activeRetrievalHits = hits;
            activePromptToolIds = Set.of();
            cachedRetrievalHits = hits;
            cachedPromptIdHash = 0;
            if (config.isRagFallbackToFullList()) {
                mod.getAIPersistantData().updateSystemPrompt();
            } else {
                updateSystemPromptAlwaysIncludeOnly();
            }
            return;
        }

        activeRetrievalHits = hits;
        activePromptToolIds = toolIdsFromHits(hits);

        int newHash = RagPromptBuilder.toolIdSetHash(RagPromptBuilder.ALWAYS_INCLUDE_IDS, hits);
        if (newHash == cachedPromptIdHash && cachedValidCommandsBlock != null) {
            // Churn-gate hit: same tool set as last build AND we hold the cached block string. The block is
            // NOT rebuilt here, so reuse the cached block and route it to the user tail (the system message
            // stays the byte-stable base). Without this the model would get an empty command set on a
            // same-set turn.
            // Guard: cachedPromptIdHash starts at 0 and toolIdSetHash returns Set.hashCode(), which is 0 for
            // an empty set (and in principle could be 0 for a non-empty set). If that collided on the FIRST
            // build of a session, cachedValidCommandsBlock would still be null here and reusing it would
            // strand the model with an empty validCommands tail even though activePromptToolIds (set above)
            // holds real ids. Requiring a non-null cached block forces fall-through to the build path in
            // that window, so the block is actually built before being cached/routed.
            LOGGER.debug("[RAG] same tool set (hash={}), reusing cached block for bot={}", newHash, getName());
            pendingValidCommandsBlock = cachedValidCommandsBlock;
            mod.getAIPersistantData().updateSystemPromptStatic();
            return;
        }

        cachedRetrievalHits = hits;
        cachedPromptIdHash = newHash;

        String block = RagPromptBuilder.buildValidCommandsBlock(
                retriever.getRegistry(), hits, mod.getCommandExecutor(), RagPromptBuilder.ALWAYS_INCLUDE_IDS);
        LOGGER.debug("[RAG] injecting {} hits into user-tail validCommands for goal=\"{}\" bot={} source={}",
                hits.size(), goalText, getName(), lastRagPromptSource);
        // Relocate the per-turn block to the user tail; keep message 0 byte-stable.
        cachedValidCommandsBlock = block;
        pendingValidCommandsBlock = block;
        mod.getAIPersistantData().updateSystemPromptStatic();
    }

    private static Set<String> toolIdsFromHits(List<RetrievalHit> hits) {
        Set<String> ids = new HashSet<>();
        for (RetrievalHit h : hits) {
            ids.add(h.toolId());
        }
        ids.addAll(RagPromptBuilder.ALWAYS_INCLUDE_IDS);
        return ids;
    }

    private void handleLlmResponse(
            JsonObject jsonResp,
            Event lastEvent,
            String relayInitiator,
            Consumer<Event.CharacterMessage> onCharacterEvent,
            Consumer<String> onErrMsg,
            LLMCompleter completer,
            ConversationHistory historyWithWrappedStatus,
            Event.UserMessage lastUserMsgForRag,
            boolean isFollowUpDecision,
            boolean isModelDeepSearchFollowUp) {
        // A response reached us = the LLM reply parsed successfully; clear the parse-retry guard.
        this.consecutiveParseFailures = 0;
        String llmMessage = Utils.getStringJsonSafely(jsonResp, "message");
        String command = this.isGreetingResponse ? "bodylang greeting"
                : Utils.getStringJsonSafely(jsonResp, "command");
        this.isGreetingResponse = false;

        String cmdId = resolveCommandId(command);
        if (isModelDeepSearchFollowUp && RagDeepSearchCommands.isMetaCommandId(cmdId)) {
            LOGGER.warn("[B5] model_deepsearch_loop_blocked bot={}", getName());
            command = "idle";
            cmdId = "idle";
        }

        if (!isFollowUpDecision && maybeModelRequestedDeepSearch(
                command, cmdId, lastUserMsgForRag, relayInitiator, onCharacterEvent, onErrMsg, completer,
                historyWithWrappedStatus, lastEvent)) {
            return;
        }

        if (!isFollowUpDecision && maybePostDecisionRetry(
                command, lastUserMsgForRag, relayInitiator, onCharacterEvent, onErrMsg, completer,
                historyWithWrappedStatus, lastEvent)) {
            return;
        }

        if (RagDeepSearchCommands.isMetaCommandId(cmdId)) {
            LOGGER.debug("[B5] model_deepsearch_skipped bot={} (not handled)", getName());
            command = "idle";
            // Keep cmdId in sync with the rewritten command (mirrors the isModelDeepSearchFollowUp
            // guard above). Otherwise the stale meta-command cmdId leaks into the peer-talk
            // substantiveReply predicate below and would inflate the streak on an effectively-idle turn.
            cmdId = "idle";
        }

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
        // --- Bodylang TTS-timed gestures: deterministic marker parse (Workstream 1) ---
        // Strip inline [bl:<action>] markers from the message ONCE here (decision 2 — the single
        // choke-point) and build the ordered chunk + boundary lists. The CharacterMessage is then
        // constructed with the STRIPPED text in its message field, so chat (AgentSideEffects:55), TTS
        // (:58), and markSpeakingFor (:63) all operate on stripped text with no second strip.
        MarkerParser.ParsedMessage parsed = MarkerParser.parse(llmMessage);
        String strippedMessage = parsed.strippedText();
        // Store the valid-only boundary list (segment_done / fallback timer index into this) and the
        // wire chunk list (read by onEntityMessage). Invalid markers are reported below, not stored.
        List<MarkerParser.SegmentBoundary> validBoundaries = new java.util.ArrayList<>();
        List<String> invalidTokens = new java.util.ArrayList<>();
        for (MarkerParser.SegmentBoundary b : parsed.boundaries()) {
            if (b.valid()) {
                validBoundaries.add(b);
            } else {
                invalidTokens.add(b.rawToken());
            }
        }
        this.pendingSegmentActions = List.copyOf(validBoundaries);
        this.pendingChunks = List.copyOf(parsed.chunks());
        this.pendingInvalidMarkers = List.copyOf(invalidTokens);
        // DESIGN.md §3 (truthfulness): an unknown marker is NOT silently dropped. Report it to the
        // MODEL here via an InfoMessage so the AI knows that gesture did not fire and cannot claim it
        // did. (The player-facing chat line is emitted in AgentSideEffects — Workstream 6.) Do NOT
        // call markSpeakingFor here (decision 4 — it stays at AgentSideEffects:63).
        if (!invalidTokens.isEmpty()) {
            LOGGER.warn("[Bodylang] bot={} emitted unknown gesture marker(s): {}", getName(), invalidTokens);
            addEventToQueue(new InfoMessage(String.format(
                    "Note: the gesture marker(s) %s are not valid and were NOT performed. "
                    + "Valid gestures are: greeting, nod_head, shake_head, victory. "
                    + "Do not claim you performed an invalid gesture.",
                    String.join(", ", invalidTokens))));
        }

        LOGGER.info("[AICommandBridge/processCharWithAPI]: Processed LLM response: message={} command={}",
                strippedMessage, command);
        // --- Peer-talk restraint (masterplan/peer-talk-restraint-plan.md), Workstreams 1 & 4 ---
        // Compute the "substantive peer reply" predicate ONCE here, at the dispatch site. Both the
        // increment and the silence-credit derive from this single predicate (the same notion of
        // substance the relay/TTS guard at AgentSideEffects:69 and the command guard at :102 use). The
        // discriminators (strippedMessage / cmdId / validBoundaries) are already in scope. NOTE: the
        // start-of-turn streak the model saw was already baked into the reminder back in process()
        // (getReminderStringFromLastEvent) BEFORE this point, so mutating the counter now is correct.
        boolean isPeerTurn = lastEvent instanceof Event.CharacterMessage;
        boolean substantiveReply = !strippedMessage.isEmpty()
                || (cmdId != null && !"idle".equals(cmdId))   // a real, non-idle command counts
                || !validBoundaries.isEmpty();                 // a valid gesture counts
        try {
            // Fire the CharacterMessage when there is stripped text, a command, OR at least one valid
            // gesture boundary — a marker-only message ("[bl:greeting]") strips to empty text but must
            // still dispatch so its gesture fires via the TTS/segment path.
            if (!strippedMessage.isEmpty() || command != null || !validBoundaries.isEmpty()) {
                registerPendingLearnCandidate();
                mod.getAIPersistantData().addAssistantMessage(strippedMessage, mod.getPlayer2APIService());
                onCharacterEvent.accept(new Event.CharacterMessage(strippedMessage, command, this, relayInitiator));
                // Substantive reply to a peer turn: grow the peer-reply streak. (The dispatch condition
                // above is intentionally BROADER than substantiveReply — it lets marker-only/blank-command
                // turns through — so the counter is gated on substantiveReply, not on dispatch.)
                if (isPeerTurn && substantiveReply) {
                    this.consecutivePeerReplies++;
                }
            } else {
                LOGGER.warn(
                        "[AICommandBridge/processChatWithAPI/onLLMResponse]: Generated null llm message and command");
            }
        } catch (Exception e) {
            LOGGER.error("[AICommandBridge/processChatWithAPI/onLLMResponse]: ERROR RUNNING SIDE EFFECTS, errMsg={}",
                    e.getMessage());
        } finally {
            // Peer-talk counter reset/credit — evaluated AFTER the if/else so it covers BOTH branches
            // (genuine silence {message:"",command:""} takes the DISPATCH branch because `command` is a
            // raw "" not null at :648-649; the else fires only for rare all-null). Never key silence on
            // the else.
            if (!isPeerTurn) {
                // Any non-CharacterMessage head (a UserMessage or a command-finish InfoMessage) is
                // human-driven activity and breaks the peer-reply streak.
                this.consecutivePeerReplies = 0;
            } else if (!substantiveReply) {
                // The bot chose silence (empty text AND idle/blank cmdId AND no gesture) — the desired
                // outcome (no chat/TTS/relay downstream at AgentSideEffects:69). Credit it and stop
                // nagging. DESIGN.md §3: silence is not a failure — debug log only, no player/model report.
                LOGGER.debug("peer_talk_silence bot={} streak_before={}", getName(), this.consecutivePeerReplies);
                this.consecutivePeerReplies = 0;
            }
            acknowledgeCommandFinishRoundIfComplete(lastEvent, command);
            this.isProcessing = false;
        }
    }

    private String resolveCommandId(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String withPrefix = mod.getCommandExecutor().isClientCommand(command)
                ? command
                : mod.getCommandExecutor().getCommandPrefix() + command;
        return AgentSideEffects.firstCommandId(withPrefix, mod.getCommandExecutor());
    }

    private boolean maybeModelRequestedDeepSearch(
            String command,
            String cmdId,
            Event.UserMessage lastUserMsgForRag,
            String relayInitiator,
            Consumer<Event.CharacterMessage> onCharacterEvent,
            Consumer<String> onErrMsg,
            LLMCompleter completer,
            ConversationHistory historyWithWrappedStatus,
            Event lastEvent) {
        Player2ServerRuntimeConfig config = Player2ServerConfigHolder.get();
        if (!config.isEnableDeepCheckRephrase() || !RagDeepSearchCommands.isMetaCommandId(cmdId)) {
            return false;
        }
        if (deepCheckAttemptsThisTurn >= config.getDeepCheckMaxAttemptsPerTurnClamped()) {
            LOGGER.debug("[B5] model_deepsearch_skipped reason=attempt_cap bot={}", getName());
            return false;
        }
        if (lastUserMsgForRag == null || lastRagGoalText.isBlank()) {
            LOGGER.debug("[B5] model_deepsearch_skipped reason=no_goal bot={}", getName());
            return false;
        }

        LOGGER.info("[B5] model_deepsearch requested bot={} goal=\"{}\"", getName(), lastRagGoalText);

        Player2PayerResolution.ApiBillingContext billing = Player2PayerResolution.resolve(
                mod, chainInitiatorUsername, mod.getPlayer2APIService().getClientId());
        DeepCheckBudgetGate.SkipReason skip = DeepCheckBudgetGate.preflight(billing);
        if (skip != DeepCheckBudgetGate.SkipReason.NONE) {
            LOGGER.debug("[B5] model_deepsearch_skipped reason={} bot={}", skip.name().toLowerCase(), getName());
            return false;
        }

        MinecraftServer server = mod.getPlayer().getServer();
        UUID ownerUuid = mod.getOwner() != null ? mod.getOwner().getUUID() : null;
        ToolRetriever retriever = RagIndex.getForOwner(server, ownerUuid);
        if (retriever == null) {
            return false;
        }

        deepCheckAttemptsThisTurn++;

        if (config.isEnableDeepCheckMessage()) {
            addEventToQueue(new InfoMessage("Let me check the command list for a better match."));
        }

        RetrievalConfidenceThresholds thresholds = RetrievalConfidenceThresholds.fromConfig(config);
        RetrievalResult firstPass = retriever.retrieveWithConfidence(
                lastRagGoalText, config.getRagTopKClamped(), null, thresholds);

        RagDeepCheckPipeline.ApplyResult applied = RagDeepCheckPipeline.apply(
                retriever,
                lastRagGoalText,
                config.getRagTopKClamped(),
                null,
                thresholds,
                firstPass,
                mod.getPlayer2APIService(),
                mod.getCommandExecutor(),
                billing,
                true,
                "model_requested");
        if (!applied.success()) {
            return false;
        }

        applyRetrievalToPrompt(retriever, applied);
        lastRagPromptSource = "model_deepsearch";
        lastDeepCheckTriggerReason = "model_requested";
        LOGGER.info("[B5] model_deepsearch_ok source={} bot={}", applied.promptSource(), getName());

        // Rebuild the wrapped copy so the newly-retrieved command set reaches the user tail (the prior
        // copy still carries the first-pass block). System message is not mutated mid-turn.
        ConversationHistory followUpHistory = rebuildWrappedStatusForFollowUp();
        Consumer<JsonObject> followUp = jsonResp -> handleLlmResponse(
                jsonResp, lastEvent, relayInitiator, onCharacterEvent, onErrMsg, completer,
                followUpHistory, lastUserMsgForRag, true, true);
        completer.processToJson(
                mod.getPlayer2APIService(), followUpHistory, followUp, onErrMsg, true, AiTaskClass.DECISION);
        return true;
    }

    private void applyRetrievalToPrompt(ToolRetriever retriever, RagDeepCheckPipeline.ApplyResult applied) {
        activeRetrievalHits = applied.activeResult().hits();
        activePromptToolIds = toolIdsFromHits(applied.activeResult().hits());
        pendingLearnSuggestion = applied.learnSuggestion();
        String block = RagPromptBuilder.buildValidCommandsBlock(
                retriever.getRegistry(),
                applied.activeResult().hits(),
                mod.getCommandExecutor(),
                RagPromptBuilder.ALWAYS_INCLUDE_IDS);
        // Prefix-cache restructure: do NOT mutate message 0 mid-turn (it would re-bust the cache and
        // would not reach the already-built wrapped copy anyway). Stage the block; the follow-up call
        // sites rebuild the wrapped copy carrying this block in the user tail.
        cachedValidCommandsBlock = block;
        pendingValidCommandsBlock = block;
        cachedRetrievalHits = applied.activeResult().hits();
        cachedPromptIdHash = RagPromptBuilder.toolIdSetHash(
                RagPromptBuilder.ALWAYS_INCLUDE_IDS, applied.activeResult().hits());
    }

    /**
     * Rebuilds the wrapped user-tail copy for a mid-turn follow-up LLM call (deep-check / post-decision
     * retry) using the status locals captured at first-pass assembly plus the freshly-staged
     * {@link #pendingValidCommandsBlock}. Re-supplies world/agent status, reminders, and the (already
     * drained) debug messages so the follow-up turn keeps full status — the system message is left
     * untouched (byte-stable for the conversation).
     */
    private ConversationHistory rebuildWrappedStatusForFollowUp() {
        return mod.getAIPersistantData().getConversationHistoryWrappedWithStatus(
                turnWorldStatus, turnAgentStatus, turnAltoClefDebugMsgs,
                mod.getPlayer2APIService(), turnReminderString,
                Optional.ofNullable(pendingValidCommandsBlock));
    }

    private void registerPendingLearnCandidate() {
        UUID ownerUuid = mod.getOwner() != null ? mod.getOwner().getUUID() : null;
        if (ownerUuid == null || pendingLearnSuggestion.isEmpty()) {
            return;
        }
        String triggerReason = lastDeepCheckTriggerReason.isBlank()
                ? lastRagPromptSource
                : lastDeepCheckTriggerReason;
        pendingLearnSuggestion.ifPresent(s -> AliasLearningService.registerPendingFromSuggestion(
                ownerUuid,
                getUUID(),
                lastRagGoalText,
                s,
                triggerReason));
        pendingLearnSuggestion = Optional.empty();
    }

    private boolean maybePostDecisionRetry(
            String command,
            Event.UserMessage lastUserMsgForRag,
            String relayInitiator,
            Consumer<Event.CharacterMessage> onCharacterEvent,
            Consumer<String> onErrMsg,
            LLMCompleter completer,
            ConversationHistory historyWithWrappedStatus,
            Event lastEvent) {
        Player2ServerRuntimeConfig config = Player2ServerConfigHolder.get();
        if (!config.isEnableDeepCheckRephrase() || postDecisionRetryAttempted) {
            return false;
        }
        String cmdId = resolveCommandId(command);
        if (cmdId == null || cmdId.isBlank() || activePromptToolIds.contains(cmdId)) {
            return false;
        }
        if (deepCheckAttemptsThisTurn >= config.getDeepCheckMaxAttemptsPerTurnClamped()) {
            return false;
        }
        Player2PayerResolution.ApiBillingContext billing = Player2PayerResolution.resolve(
                mod, chainInitiatorUsername, mod.getPlayer2APIService().getClientId());
        if (DeepCheckBudgetGate.preflight(billing) != DeepCheckBudgetGate.SkipReason.NONE) {
            LOGGER.debug("[B5] post_decision_retry_skipped cmd={}", cmdId);
            return false;
        }
        if (lastUserMsgForRag == null || lastRagGoalText.isBlank()) {
            return false;
        }

        postDecisionRetryAttempted = true;
        MinecraftServer server = mod.getPlayer().getServer();
        UUID ownerUuid = mod.getOwner() != null ? mod.getOwner().getUUID() : null;
        ToolRetriever retriever = RagIndex.getForOwner(server, ownerUuid);
        if (retriever == null) {
            return false;
        }

        RetrievalConfidenceThresholds thresholds = RetrievalConfidenceThresholds.fromConfig(config);
        RetrievalResult firstPass = retriever.retrieveWithConfidence(
                lastRagGoalText, config.getRagTopKClamped(), null, thresholds);
        deepCheckAttemptsThisTurn++;

        String enrichedGoal = lastRagGoalText + " (model chose command: " + cmdId + ")";
        RagDeepCheckPipeline.ApplyResult applied = RagDeepCheckPipeline.apply(
                retriever,
                enrichedGoal,
                config.getRagTopKClamped(),
                null,
                thresholds,
                firstPass,
                mod.getPlayer2APIService(),
                mod.getCommandExecutor(),
                billing,
                true,
                "post_decision_out_of_top_k");
        if (!applied.success()) {
            return false;
        }

        applyRetrievalToPrompt(retriever, applied);
        lastRagPromptSource = applied.promptSource();
        lastDeepCheckTriggerReason = "post_decision_out_of_top_k";

        // Rebuild the wrapped copy so the re-retrieved command set reaches the user tail. System message
        // is not mutated mid-turn.
        ConversationHistory retryHistory = rebuildWrappedStatusForFollowUp();
        Consumer<JsonObject> retryHandler = jsonResp -> handleLlmResponse(
                jsonResp, lastEvent, relayInitiator, onCharacterEvent, onErrMsg, completer,
                retryHistory, lastUserMsgForRag, true, false);
        completer.processToJson(
                mod.getPlayer2APIService(), retryHistory, retryHandler, onErrMsg, true, AiTaskClass.DECISION);
        return true;
    }

    private void applyRagPromptFromCache(Player2ServerRuntimeConfig config) {
        if (cachedRetrievalHits.isEmpty()) {
            // No prior retrieval this session; use full list or always-include only (session-stable,
            // stays in the system message; no user-tail block).
            if (config.isRagFallbackToFullList()) {
                mod.getAIPersistantData().updateSystemPrompt();
            } else {
                updateSystemPromptAlwaysIncludeOnly();
            }
            return;
        }
        // Prior retrieval exists this session. Under the prefix-cache restructure the cached set is NO
        // LONGER in the system message, so re-supply it to the user tail and keep message 0 byte-stable.
        // Without this, InfoMessage-only / short-goal / autonomous-step turns would send the model an
        // empty command set (no system list AND no tail block).
        //
        // Defense-in-depth (mirrors the churn-gate guard at L474): cachedRetrievalHits being non-empty is
        // today paired with a cachedValidCommandsBlock write (build path L491/L499, applyRetrievalToPrompt),
        // but that pairing is an invariant no assert enforces. If a future edit populates cachedRetrievalHits
        // without also setting cachedValidCommandsBlock, routing a null block here would yield
        // Optional.ofNullable(null) -> an absent validCommands key and an empty command set this turn -- the
        // exact failure WS3 set out to prevent. Make the violation loud and degrade to the session-stable
        // path (list rendered in message 0) instead of silently sending zero commands.
        if (cachedValidCommandsBlock == null) {
            LOGGER.warn("[RAG] applyRagPromptFromCache: non-empty cachedRetrievalHits but cachedValidCommandsBlock "
                    + "is null for bot={} — falling back to system-message list", getName());
            if (config.isRagFallbackToFullList()) {
                mod.getAIPersistantData().updateSystemPrompt();
            } else {
                updateSystemPromptAlwaysIncludeOnly();
            }
            return;
        }
        pendingValidCommandsBlock = cachedValidCommandsBlock;
        mod.getAIPersistantData().updateSystemPromptStatic();
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
        if (event instanceof Event.UserMessage) {
            commandAwaitingFinishAck = null;
        }
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

    public void onReturn(String ownerName) {
        addEventToQueue(mod.getAIPersistantData().getReturnEvent(ownerName));
    }

    public void onDeathRevival(String deathCause) {
        addEventToQueue(mod.getAIPersistantData().getDeathRevivalEvent(deathCause));
    }

    private static boolean isCommandFinishPromptMessage(String message) {
        return message != null
                && message.startsWith("Command feedback:")
                && message.contains("finished running");
    }

    private static String normalizeCommandNameForFinishAck(String commandName) {
        if (commandName == null) {
            return "";
        }
        String s = commandName.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("@")) {
            s = s.substring(1);
        }
        int space = s.indexOf(' ');
        return space > 0 ? s.substring(0, space) : s;
    }

    private static boolean isNonTaskCommandForFinishPrompt(String commandName) {
        String base = normalizeCommandNameForFinishAck(commandName);
        return base.contains("bodylang")
                || "idle".equals(base)
                || "place_sign".equals(base)
                || "read_signs".equals(base);
    }

    private boolean shouldEnqueueCommandFinishPrompt(String commandName) {
        if (isNonTaskCommandForFinishPrompt(commandName)) {
            LOGGER.info("Skipping command finish prompt for non-task cmd={}", commandName);
            return false;
        }
        String normalized = normalizeCommandNameForFinishAck(commandName);
        if (commandAwaitingFinishAck != null && commandAwaitingFinishAck.equals(normalized)) {
            // [DEBUG-INSTR:dead-callback-idle] log the held ack vs incoming key to confirm the Gap-2
            // dedup path (cobblestone vs stone_pickaxe) post-fix. Remove with the ledger once confirmed.
            LOGGER.info(
                    "Skipping duplicate command finish prompt for cmd={} (held ack={} normalized={})",
                    commandName, commandAwaitingFinishAck, normalized);
            return false;
        }
        return true;
    }

    private void enqueueCommandFinishPrompt(String commandName) {
        enqueueCommandFinishPrompt(commandName, null);
    }

    private void enqueueCommandFinishPrompt(String commandName, String note) {
        if (note == null || note.isBlank()) {
            // Clean success: byte-identical to the original single-InfoMessage prompt.
            addEventToQueue(new InfoMessage(String.format(
                    "Command feedback: %s finished running. What shall we do next? If no new action is needed to finish user's request, generate empty command `\"\"`.",
                    commandName)));
        } else if (note.startsWith(INFO_RESULT_NOTE_PREFIX)) {
            // Succeeded with an informational RESULT payload (e.g. locate_storage coordinates) — NOT a
            // degradation. Frame it as a result, not a "but:", so the model neither treats the payload
            // as a problem nor loses the standard "what next?" cue (which the old enqueueInfo+finish()
            // path suppressed by leaving a non-empty event queue at finish time).
            addEventToQueue(new InfoMessage(String.format(
                    "Command feedback: %s finished running. Result: %s. What shall we do next? If no new action is needed to finish user's request, generate empty command `\"\"`.",
                    commandName,
                    note.substring(INFO_RESULT_NOTE_PREFIX.length()))));
        } else {
            // Succeeded-but-degraded: same single InfoMessage, with a factual clause so the model can
            // truthfully report the degradation instead of claiming a clean success.
            addEventToQueue(new InfoMessage(String.format(
                    "Command feedback: %s finished running, but: %s. What shall we do next? If no new action is needed to finish user's request, generate empty command `\"\"`.",
                    commandName,
                    note)));
        }
        commandAwaitingFinishAck = normalizeCommandNameForFinishAck(commandName);
    }

    private void acknowledgeCommandFinishRoundIfComplete(Event lastEvent, String command) {
        if (!(lastEvent instanceof InfoMessage info) || !isCommandFinishPromptMessage(info.message())) {
            return;
        }
        // The model has now RESPONDED to this command-finish-prompt round (with any command — terminal
        // or a follow-up like another `get`). The round is acknowledged, so the dedup guard has done its
        // job and must be cleared. Previously this only cleared on a terminal command (idle/empty), so a
        // normal follow-up command left the ack set; the NEXT command's successful finish then matched
        // the stale ack, was suppressed as a "duplicate", enqueued no event, and — with an empty queue
        // and no auto-tick — the model idled until an external UserMessage cleared the ack (dead-callback
        // idle). Clearing unconditionally here lets each successful command's finish enqueue its own
        // "what next?" prompt and reliably drive the model's next turn. This does NOT re-introduce genuine
        // duplicate prompts: the ack is SET at enqueue time and a true double-fire of onCommandFinish for
        // the SAME execution happens before the model responds (while the finish InfoMessage is still
        // queued / isProcessing is still true), so the duplicate is still suppressed; this clear only runs
        // after the model has actually answered the round, when no duplicate remains to guard against.
        LOGGER.info("Command-finish round complete (model responded with cmd={}); clearing finish-ack for cmd={}",
                command, commandAwaitingFinishAck);
        commandAwaitingFinishAck = null;
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
            if (!eventQueue.isEmpty()) {
                LOGGER.info("Skipping command finish prompt for cmd={} because event queue is not empty",
                        stopReason.commandName());
            } else if (shouldEnqueueCommandFinishPrompt(stopReason.commandName())) {
                LOGGER.info("Enqueueing command finish prompt for cmd={}", stopReason.commandName());
                String note = ((CommandExecutionStopReason.Finished) stopReason).note();
                enqueueCommandFinishPrompt(stopReason.commandName(), note);
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

    /** Transient peer-reply streak (consecutive non-silent replies to peer turns); read by the reminder builder. */
    int getConsecutivePeerReplies() {
        return consecutivePeerReplies;
    }

}
