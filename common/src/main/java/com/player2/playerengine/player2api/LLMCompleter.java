package com.player2.playerengine.player2api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    private static final long NO_REQUEST_ID = 0L;
    private static final int MAX_RETIRED_WORKERS = 2;

    /** Identity returned to the submitting conversation so only its own request can be cancelled. */
    public record Submission(long requestId, boolean accepted) {
        static Submission rejected() {
            return new Submission(NO_REQUEST_ID, false);
        }
    }

    public enum CancellationOutcome {
        NOT_ACTIVE,
        CANCELLED_QUEUED,
        CANCELLED_AND_RETIRED,
        RETIREMENT_LIMIT_REACHED
    }

    /** Caps blocking LLM work at the proxy chat-completion ceiling plus a safety buffer. */
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

    /** Per-billing-bucket in-flight gate. */
    private ExecutorService llmThread = newWorkerExecutor();
    private final List<ExecutorService> retiredExecutors = new ArrayList<>();
    private final Set<Long> runningRequestIds = new HashSet<>();
    private final Set<Long> cancelledBeforeStartRequestIds = new HashSet<>();
    private long nextRequestId = NO_REQUEST_ID;
    private long activeRequestId = NO_REQUEST_ID;
    private Future<?> activeFuture;
    private static final Logger LOGGER = LogManager.getLogger();
    private volatile boolean executorShutDown = false;

    /** Interrupts in-flight work and terminates the worker thread (safe to call more than once). */
    public void shutdown() {
        shutdown(null);
    }

    /** Package-private hook lets the detached self-test hold the former flag-to-stop race window open. */
    void shutdown(Runnable afterShutdownFlagSet) {
        List<ExecutorService> executors;
        Future<?> future;
        synchronized (this) {
            if (executorShutDown) {
                return;
            }
            executorShutDown = true;
            future = activeFuture;
            activeFuture = null;
            activeRequestId = NO_REQUEST_ID;
            executors = new ArrayList<>(retiredExecutors);
            executors.add(llmThread);
            retiredExecutors.clear();
        }
        try {
            if (afterShutdownFlagSet != null) {
                afterShutdownFlagSet.run();
            }
        } finally {
            if (future != null) {
                future.cancel(true);
            }
            for (ExecutorService executor : executors) {
                ExecutorShutdown.shutdownNowAwait("LLMCompleter", executor);
            }
        }
    }

    private synchronized <T> Submission process(
            Player2APIService player2apiService,
            ConversationHistory history,
            Consumer<T> extOnLLMResponse,
            Consumer<String> extOnErrMsg,
            ThrowingFunction<ConversationHistory, T> completeConversation,
            boolean isConversation,
            Submission predecessor) {
        LOGGER.info("Called completer.process with history={}", history);
        if (executorShutDown) {
            LOGGER.warn("Called llmcompleter.process after shutdown; ignoring.");
            return Submission.rejected();
        }
        boolean authorizedHandoff = predecessor != null && predecessor.accepted()
                && activeRequestId == predecessor.requestId();
        if ((activeRequestId != NO_REQUEST_ID && !authorizedHandoff)
                || (activeRequestId == NO_REQUEST_ID && predecessor != null)) {
            LOGGER.warn("Called llmcompleter.process when it was already processing! This should not happen.");
            return Submission.rejected();
        }

        long previousRequestId = activeRequestId;
        Future<?> previousFuture = activeFuture;
        final long requestId = ++nextRequestId;
        activeRequestId = requestId;

        Consumer<T> onLLMResponse = resp -> {
            try {
                extOnLLMResponse.accept(resp);
            } catch (Exception e) {
                LOGGER.error(
                        "[LLMCompleter/process/onLLMResponse]: Error in external llm resp, errMsg={} llmResp={}",
                        e.getMessage(), resp.toString());
            } finally {
                LOGGER.info("Done processing, releasing completer requestId={}", requestId);
                releaseRequest(requestId);
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
                LOGGER.info("Done processing (err path), releasing completer requestId={}", requestId);
                releaseRequest(requestId);
            }
        };

        try {
            activeFuture = llmThread.submit(() -> {
                if (!beginRequest(requestId)) {
                    return;
                }
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
                    Thread.interrupted();
                    markRequestFinished(requestId);
                }
            });
            return new Submission(requestId, true);
        } catch (RuntimeException submitFailure) {
            activeFuture = authorizedHandoff ? previousFuture : null;
            activeRequestId = authorizedHandoff ? previousRequestId : NO_REQUEST_ID;
            LOGGER.error("Unable to submit LLM request: type={}", submitFailure.getClass().getSimpleName());
            return Submission.rejected();
        }
    }

    /** Cancels the matching request, rotating a worker only within the bounded retirement budget. */
    public CancellationOutcome cancel(Submission submission) {
        if (submission == null || !submission.accepted()) {
            return CancellationOutcome.NOT_ACTIVE;
        }
        Future<?> future;
        ExecutorService retired = null;
        CancellationOutcome outcome;
        synchronized (this) {
            if (activeRequestId != submission.requestId()) {
                return CancellationOutcome.NOT_ACTIVE;
            }
            retiredExecutors.removeIf(ExecutorService::isTerminated);
            future = activeFuture;
            if (!runningRequestIds.contains(submission.requestId())) {
                // A queued continuation must remain cancelled even if its runnable drains during shutdown.
                cancelledBeforeStartRequestIds.add(submission.requestId());
                activeFuture = null;
                activeRequestId = NO_REQUEST_ID;
                outcome = CancellationOutcome.CANCELLED_QUEUED;
            } else if (retiredExecutors.size() >= MAX_RETIRED_WORKERS) {
                // Keep the request busy until its bounded transport timeout drains.
                outcome = CancellationOutcome.RETIREMENT_LIMIT_REACHED;
            } else {
                activeFuture = null;
                activeRequestId = NO_REQUEST_ID;
                retired = llmThread;
                llmThread = newWorkerExecutor();
                retiredExecutors.add(retired);
                outcome = CancellationOutcome.CANCELLED_AND_RETIRED;
            }
        }
        if (future != null && outcome != CancellationOutcome.CANCELLED_QUEUED) {
            future.cancel(true);
        }
        if (retired != null) {
            retired.shutdownNow();
        }
        LOGGER.info("LLM cancellation requestId={} outcome={}", submission.requestId(), outcome);
        return outcome;
    }

    private synchronized void releaseRequest(long requestId) {
        if (activeRequestId == requestId) {
            activeRequestId = NO_REQUEST_ID;
            activeFuture = null;
        }
    }

    private synchronized boolean beginRequest(long requestId) {
        if (executorShutDown || cancelledBeforeStartRequestIds.remove(requestId)) {
            return false;
        }
        runningRequestIds.add(requestId);
        return true;
    }

    private synchronized void markRequestFinished(long requestId) {
        runningRequestIds.remove(requestId);
        retiredExecutors.removeIf(ExecutorService::isTerminated);
    }

    private static ExecutorService newWorkerExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "playerengine-llm-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Submission processToJson(
            Player2APIService player2apiService,
            ConversationHistory history,
            Consumer<JsonObject> extOnLLMResponse,
            Consumer<String> extOnErrMsg,
            boolean isConversation,
            AiTaskClass taskClass) {
        return process(player2apiService, history, extOnLLMResponse, extOnErrMsg,
                h -> player2apiService.completeConversation(h, taskClass), isConversation, null);
    }

    /** Atomically queues a callback-driven continuation behind its active predecessor. */
    public Submission processToJsonAfter(
            Submission predecessor,
            Player2APIService player2apiService,
            ConversationHistory history,
            Consumer<JsonObject> extOnLLMResponse,
            Consumer<String> extOnErrMsg,
            boolean isConversation,
            AiTaskClass taskClass) {
        return process(player2apiService, history, extOnLLMResponse, extOnErrMsg,
                h -> player2apiService.completeConversation(h, taskClass), isConversation, predecessor);
    }

    @Deprecated
    public Submission processToJson(
            Player2APIService player2apiService,
            ConversationHistory history,
            Consumer<JsonObject> extOnLLMResponse,
            Consumer<String> extOnErrMsg,
            boolean isConversation) {
        return processToJson(player2apiService, history, extOnLLMResponse, extOnErrMsg,
                isConversation, AiTaskClass.DECISION);
    }

    public Submission processToString(
            Player2APIService player2apiService,
            ConversationHistory history,
            Consumer<String> extOnLLMResponse,
            Consumer<String> extOnErrMsg,
            boolean isConversation,
            AiTaskClass taskClass) {
        return process(player2apiService, history, extOnLLMResponse, extOnErrMsg,
                h -> player2apiService.completeConversationToString(h, taskClass), isConversation, null);
    }

    @Deprecated
    public Submission processToString(
            Player2APIService player2apiService,
            ConversationHistory history,
            Consumer<String> extOnLLMResponse,
            Consumer<String> extOnErrMsg,
            boolean isConversation) {
        return processToString(player2apiService, history, extOnLLMResponse, extOnErrMsg,
                isConversation, AiTaskClass.DECISION);
    }

    public synchronized boolean isAvailible() {
        return !executorShutDown && activeRequestId == NO_REQUEST_ID;
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
