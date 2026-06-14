package com.player2.playerengine.player2api;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonObject;

import com.player2.playerengine.executor.StopReason;

import com.player2.playerengine.util.ExecutorShutdown;
import com.player2.playerengine.player2api.utils.Utils.ThrowingFunction;

public class LLMCompleter {
    // [DEBUG-INSTR:llm-latency-2026-06-13] Debug flag: set true to enable latency/timeout probes. Flip to false and rebuild to revert all probes in this class.
    public static final boolean DEBUG_LLM_PROBE = false; // [DEBUG-INSTR:llm-latency-2026-06-13]

    /**
     * Caps blocking LLM work at proxy chat-completion ceiling + buffer ({@link Player2ClientApiBridge}, Phase A3).
     */
    // [DEBUG-INSTR:llm-latency-2026-06-13] PROBE 3a: unbounded watchdog under debug flag so the real latency can be measured. Original: defaultTimeoutForEndpoint("/v1/chat/completions") + 15L
    private static final long LLM_CHAT_WORKER_TIMEOUT_SECONDS = DEBUG_LLM_PROBE
            ? 3600L
            : Player2ClientApiBridge.defaultTimeoutForEndpoint("/v1/chat/completions") + 15L;
    // [/DEBUG-INSTR:llm-latency-2026-06-13]

    private static final ScheduledExecutorService LLM_WATCHDOG_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "playerengine-llm-watchdog");
        t.setDaemon(true);
        return t;
    });

    /**
     * Per-completer in-flight gate. Replaces the previous server-wide
     * {@code ConversationManager.Lock.waitingForResponseLock}; each per-billing-bucket
     * completer now serializes only its own bot's LLM round-trip.
     */
    private volatile boolean isProcessing = false;

    private final ExecutorService llmThread = Executors.newSingleThreadExecutor();
    private static final Logger LOGGER = LogManager.getLogger();
    private volatile boolean executorShutDown = false;

    /** Interrupts in-flight work and terminates the worker thread (safe to call more than once). */
    public void shutdown() {
        if (executorShutDown) {
            return;
        }
        executorShutDown = true;
        ExecutorShutdown.shutdownNowAwait("LLMCompleter", llmThread);
    }

    private <T> void process(
            Player2APIService player2apiService,
            ConversationHistory history,
            Consumer<T> extOnLLMResponse,
            Consumer<String> extOnErrMsg,
            ThrowingFunction<ConversationHistory, T> completeConversation,
            boolean isConversation) {
        LOGGER.info("Called completer.process with history={}", history);
        if (executorShutDown) {
            LOGGER.warn("Called llmcompleter.process after shutdown; ignoring.");
            return;
        }
        if (isProcessing) {
            LOGGER.warn("Called llmcompleter.process when it was already processing! This should not happen.");
            return;
        }

        isProcessing = true;

        Consumer<T> onLLMResponse = resp -> {
            try {
                extOnLLMResponse.accept(resp);
            } catch (Exception e) {
                LOGGER.error(
                        "[LLMCompleter/process/onLLMResponse]: Error in external llm resp, errMsg={} llmResp={}",
                        e.getMessage(), resp.toString());
            } finally {
                LOGGER.info("Done processing, releasing completer isProcessing -> false");
                isProcessing = false;
            }
        };

        Consumer<String> onErrMsg = errMsg -> {
            try {
                extOnErrMsg.accept(errMsg);
            } catch (Exception e) {
                LOGGER.error(
                        "[LLMCompleter/process/onErrMsg]: Error in external onErrmsg, errMsgFromException={} errMsg={}",
                        e.getMessage(), errMsg);
            } finally {
                LOGGER.info("Done processing (err path), releasing completer isProcessing -> false");
                isProcessing = false;
            }
        };

        llmThread.submit(() -> {
            Thread workerThread = Thread.currentThread();
            ScheduledFuture<?> watchdog = LLM_WATCHDOG_SCHEDULER.schedule(() -> workerThread.interrupt(),
                    LLM_CHAT_WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // [DEBUG-INSTR:llm-latency-2026-06-13] PROBE 1: capture wall-clock start before the blocking LLM call
            long dbgStartNs = DEBUG_LLM_PROBE ? System.nanoTime() : 0L; // [DEBUG-INSTR:llm-latency-2026-06-13]
            try {
                T response = completeConversation.apply(history);
                // [DEBUG-INSTR:llm-latency-2026-06-13] PROBE 1: log elapsed on SUCCESS path
                if (DEBUG_LLM_PROBE) {
                    String dbgElapsedS = String.format("%.3f", (System.nanoTime() - dbgStartNs) / 1_000_000_000.0);
                    LOGGER.info("[DBG llm-latency-2026-06-13] chat completion elapsed={}s outcome=SUCCESS isConversation={}", dbgElapsedS, isConversation);
                } // [/DEBUG-INSTR:llm-latency-2026-06-13]
                LOGGER.info("LLMCompleter returned json={}", response);
                onLLMResponse.accept(response);
            } catch (Exception e) {
                // [DEBUG-INSTR:llm-latency-2026-06-13] PROBE 1: log elapsed on ERROR/TIMEOUT path
                if (DEBUG_LLM_PROBE) {
                    String dbgElapsedS = String.format("%.3f", (System.nanoTime() - dbgStartNs) / 1_000_000_000.0);
                    String dbgOutcome = isInterruptedLike(e) ? "TIMEOUT/INTERRUPT" : "ERROR:" + e.getClass().getSimpleName();
                    LOGGER.info("[DBG llm-latency-2026-06-13] chat completion elapsed={}s outcome={} isConversation={}", dbgElapsedS, dbgOutcome, isConversation);
                } // [/DEBUG-INSTR:llm-latency-2026-06-13]
                if (isInterruptedLike(e)) {
                    Thread.currentThread().interrupt();
                    onErrMsg.accept(StopReason.FATAL.name() + ":llm_worker_timeout");
                } else {
                    onErrMsg.accept(
                            e.getMessage() == null ? "Unknown error from CompleteConversation API" : e.getMessage());
                }
            } finally {
                watchdog.cancel(false);
                Thread.interrupted(); // clear interrupt bit for worker reuse
            }
        });
    }

    /**
     * Submit a JSON-parsed LLM completion request for the given task class.
     *
     * @param taskClass determines which Player2 profile is used (B3 routing)
     */
    public void processToJson(
            Player2APIService player2apiService,
            ConversationHistory history,
            Consumer<JsonObject> extOnLLMResponse,
            Consumer<String> extOnErrMsg,
            boolean isConversation,
            AiTaskClass taskClass) {
        process(player2apiService, history, extOnLLMResponse, extOnErrMsg,
                h -> player2apiService.completeConversation(h, taskClass), isConversation);
    }

    /**
     * NPC chat / command pick. Defaults to {@link AiTaskClass#DECISION}.
     *
     * @deprecated Pass an explicit {@link AiTaskClass}. Kept for compatibility.
     */
    @Deprecated
    public void processToJson(
            Player2APIService player2apiService,
            ConversationHistory history,
            Consumer<JsonObject> extOnLLMResponse,
            Consumer<String> extOnErrMsg,
            boolean isConversation) {
        processToJson(player2apiService, history, extOnLLMResponse, extOnErrMsg, isConversation, AiTaskClass.DECISION);
    }

    /**
     * Submit a plain-text LLM completion request for the given task class.
     *
     * @param taskClass determines which Player2 profile is used (B3 routing)
     */
    public void processToString(
            Player2APIService player2apiService,
            ConversationHistory history,
            Consumer<String> extOnLLMResponse,
            Consumer<String> extOnErrMsg,
            boolean isConversation,
            AiTaskClass taskClass) {
        process(player2apiService, history, extOnLLMResponse, extOnErrMsg,
                h -> player2apiService.completeConversationToString(h, taskClass), isConversation);
    }

    /**
     * Defaults to {@link AiTaskClass#DECISION}.
     *
     * @deprecated Pass an explicit {@link AiTaskClass}. Kept for compatibility.
     */
    @Deprecated
    public void processToString(
            Player2APIService player2apiService,
            ConversationHistory history,
            Consumer<String> extOnLLMResponse,
            Consumer<String> extOnErrMsg,
            boolean isConversation) {
        processToString(player2apiService, history, extOnLLMResponse, extOnErrMsg, isConversation, AiTaskClass.DECISION);
    }

    public boolean isAvailible() {
        return !isProcessing;
    }

    private static boolean isInterruptedLike(Throwable e) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        while (e != null) {
            if (e instanceof InterruptedException) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }
}