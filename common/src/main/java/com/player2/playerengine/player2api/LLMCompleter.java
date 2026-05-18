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
    /**
     * Caps blocking LLM work at proxy chat-completion ceiling + buffer ({@link Player2ClientApiBridge}, Phase A3).
     */
    private static final long LLM_CHAT_WORKER_TIMEOUT_SECONDS =
            Player2ClientApiBridge.defaultTimeoutForEndpoint("/v1/chat/completions") + 15L;

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
            try {
                T response = completeConversation.apply(history);
                LOGGER.info("LLMCompleter returned json={}", response);
                onLLMResponse.accept(response);
            } catch (Exception e) {
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

    public void processToJson(
            Player2APIService player2apiService,
            ConversationHistory history,
            Consumer<JsonObject> extOnLLMResponse,
            Consumer<String> extOnErrMsg,
            boolean isConversation) {
        process(player2apiService, history, extOnLLMResponse, extOnErrMsg,
                player2apiService::completeConversation, isConversation);
    }

    public void processToString(
            Player2APIService player2apiService,
            ConversationHistory history,
            Consumer<String> extOnLLMResponse,
            Consumer<String> extOnErrMsg,
            boolean isConversation) {
        process(player2apiService, history, extOnLLMResponse, extOnErrMsg,
                player2apiService::completeConversationToString, isConversation);
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
