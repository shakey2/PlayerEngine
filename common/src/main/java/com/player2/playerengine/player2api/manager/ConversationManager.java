package com.player2.playerengine.player2api.manager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.Collections;
import java.util.stream.Collectors;

import com.player2.playerengine.player2api.AgentSideEffects;
import com.player2.playerengine.player2api.Character;
import com.player2.playerengine.player2api.Event;
import com.player2.playerengine.player2api.LLMCompleter;
import com.player2.playerengine.player2api.Player2PayerResolution;
import com.player2.playerengine.player2api.AgentConversationData;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.ChatEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.player2api.BotBlacklistPolicy;
import com.player2.playerengine.player2api.CallByNameMentionRouter;
import com.player2.playerengine.player2api.UserBlacklistPolicy;
import com.player2.playerengine.player2api.utils.SttLogging;
import com.player2.playerengine.player2api.Event.UserMessage;
import com.player2.playerengine.player2api.config.Player2ServerConfigHolder;
import com.player2.playerengine.player2api.status.StatusUtils;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

public class ConversationManager {
    public static final Logger LOGGER = LogManager.getLogger();

    public static ConcurrentHashMap<UUID, AgentConversationData> queueData = new ConcurrentHashMap<>();
    public static final float messagePassingMaxDistance = 64; // let messages between entities pass iff <= this maximum
    private static boolean hasInit = false;

    public static void init() {
        if (!hasInit) {
            hasInit = true;
            // unused but need to keep this so subscribes to events
            // TODO: figure out what to do w. fabric here:
            ChatEvent.RECEIVED.register((player, component) -> {
                String message = component.plainCopy().getString();
                String sender = player.getName().getString();
                ConversationManager.onUserChatMessage(new UserMessage(message, sender));
                return EventResult.pass();
            });
        }
    }

    /**
     * Per-billing-client LLM completers. One bucket per resolved billing key (online payer UUID
     * for PROMPTER_PAYS / OWNER_PAYS_ALL online, or "token:&lt;username&gt;" for stored-token mode).
     * A slow/busy client only blocks its own bucket — other buckets remain free to dispatch.
     */
    private static final ConcurrentHashMap<String, LLMCompleter> llmCompletersByBillingKey = new ConcurrentHashMap<>();

    /**
     * Extra completers (e.g. build-structure) that own their own lifecycle. Tracked separately so
     * shutdown can drain them without touching the main billing-bucket pool.
     */
    private static final CopyOnWriteArrayList<LLMCompleter> extraLLMCompleters = new CopyOnWriteArrayList<>();

    private static LLMCompleter getOrCreateCompleterForBillingKey(String billingKey) {
        return llmCompletersByBillingKey.computeIfAbsent(billingKey, k -> {
            LOGGER.info("ConversationManager: creating LLMCompleter bucket for billingKey={}", k);
            return new LLMCompleter();
        });
    }

    /** Extra completers (e.g. build-structure) register here; included in server shutdown. */
    public static void registerLLMCompleter(LLMCompleter completer) {
        if (completer != null && !extraLLMCompleters.contains(completer)) {
            extraLLMCompleters.add(completer);
        }
    }

    public static void unregisterLLMCompleter(LLMCompleter completer) {
        if (completer != null) {
            extraLLMCompleters.remove(completer);
        }
    }

    /**
     * Shuts down every registered completer (per-billing buckets + extras) and clears the maps so
     * the next session (e.g. integrated server restart in the same JVM) starts with fresh executors.
     * Buckets are lazily recreated on first dispatch.
     */
    public static void shutdownAndResetLLMCompleters() {
        for (LLMCompleter c : new ArrayList<>(llmCompletersByBillingKey.values())) {
            c.shutdown();
        }
        llmCompletersByBillingKey.clear();
        for (LLMCompleter c : new ArrayList<>(extraLLMCompleters)) {
            c.shutdown();
        }
        extraLLMCompleters.clear();
    }

    /**
     * Drop the bucket for a given billing key (e.g. on player disconnect under PROMPTER_PAYS).
     * The in-flight worker thread is shut down; new dispatch for that key will lazily build a
     * fresh bucket if/when the player rejoins.
     */
    public static void shutdownCompleterForBillingKey(String billingKey) {
        if (billingKey == null) {
            return;
        }
        LLMCompleter removed = llmCompletersByBillingKey.remove(billingKey);
        if (removed != null) {
            LOGGER.info("ConversationManager: shutting down LLMCompleter bucket for billingKey={}", billingKey);
            removed.shutdown();
        }
    }

    // ## Utils
    public static AgentConversationData getOrCreateEventQueueData(PlayerEngineController mod) {
        return queueData.computeIfAbsent(mod.getPlayer().getUUID(), k -> {
            LOGGER.info(
                    "EventQueueManager/getOrCreateEventQueueData: creating new queue data for entId={}",
                    mod.getPlayer().getStringUUID());
            return new AgentConversationData(mod);
        });
    }

    private static Stream<AgentConversationData> filterQueueData(Predicate<AgentConversationData> pred) {
        return queueData.values().stream().filter(pred);
    }

    private static Stream<AgentConversationData> getCloseDataByUUID(UUID sender) {
        return filterQueueData(data -> data.getDistance(sender) < messagePassingMaxDistance);
    }

    // ## Callbacks (need to register these externally)

    private static final String USER_BLACKLIST_CALL_BY_NAME_MSG = "The owner of this bot has blacklisted you.";

    private static void maybeNotifyUserBlacklistCallByName(MinecraftServer server, String speakerName,
            HashSet<UUID> notifiedBotOwnerUuids, AgentConversationData blockedTarget) {
        if (server == null || speakerName == null || speakerName.isBlank()) {
            return;
        }
        Player owner = blockedTarget.getMod().getOwner();
        if (owner == null) {
            return;
        }
        UUID ownerUuid = owner.getUUID();
        if (!notifiedBotOwnerUuids.add(ownerUuid)) {
            return;
        }
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (sp.getGameProfile().getName().equalsIgnoreCase(speakerName.trim())) {
                sp.sendSystemMessage(Component.literal(USER_BLACKLIST_CALL_BY_NAME_MSG));
                return;
            }
        }
    }

    // register when a user sends a chat message
    public static void onUserChatMessage(UserMessage msg) {
        LOGGER.info("User message event={}", msg);
        boolean callByName = Player2ServerConfigHolder.get().isCallByNameChat();
        List<AgentConversationData> nearby = filterQueueData(d -> isCloseToPlayer(d, msg.userName()))
                .collect(Collectors.toList());
        if (nearby.isEmpty()) {
            logMessageNotDelivered(msg, callByName, "no_nearby_companion", nearby, null);
            return;
        }
        MinecraftServer server = nearby.stream()
                .map(d -> d.getMod().getPlayer().getServer())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (!callByName) {
            int queued = 0;
            for (AgentConversationData data : nearby) {
                if (server != null && BotBlacklistPolicy.isBlocked(server, msg.userName(), data)) {
                    logMessageBlocked(msg, data.getName(), "bot_blacklist");
                    continue;
                }
                if (server != null && UserBlacklistPolicy.isBlocked(server, msg.userName(), data)) {
                    logMessageBlocked(msg, data.getName(), "user_blacklist");
                    continue;
                }
                data.onEvent(msg);
                queued++;
            }
            if (queued == 0) {
                logMessageNotDelivered(msg, false, "all_nearby_blocked", nearby, null);
            }
            return;
        }

        CallByNameMentionRouter.ResolvedTargets resolved = CallByNameMentionRouter.resolveTargets(msg, msg.userName(),
                nearby);
        if (resolved == null || resolved.targets() == null || resolved.targets().isEmpty()) {
            logMessageNotDelivered(msg, true, "call_by_name_no_mention", nearby, resolved);
            return;
        }
        if (resolved.cleanedMessage() == null) {
            logMessageNotDelivered(msg, true, "call_by_name_cleaned_message_null", nearby, resolved);
            return;
        }
        HashSet<UUID> userBlacklistNotifiedOwners = new HashSet<>();
        int queued = 0;
        for (AgentConversationData data : resolved.targets()) {
            if (server != null && BotBlacklistPolicy.isBlocked(server, msg.userName(), data)) {
                logMessageBlocked(msg, data.getName(), "bot_blacklist");
                continue;
            }
            if (server != null && UserBlacklistPolicy.isBlocked(server, msg.userName(), data)) {
                maybeNotifyUserBlacklistCallByName(server, msg.userName(), userBlacklistNotifiedOwners, data);
                logMessageBlocked(msg, data.getName(), "user_blacklist");
                continue;
            }
            data.onEvent(resolved.cleanedMessage());
            queued++;
        }
        if (queued == 0) {
            logMessageNotDelivered(msg, true, "call_by_name_all_targets_blocked", nearby, resolved);
        }
    }

    private static void logMessageNotDelivered(UserMessage msg, boolean callByName, String reason,
            List<AgentConversationData> nearby, CallByNameMentionRouter.ResolvedTargets resolved) {
        String nearbyNames = nearby.stream().map(AgentConversationData::getName).collect(Collectors.joining(", "));
        String targetNames = resolved == null || resolved.targets() == null ? ""
                : resolved.targets().stream().map(AgentConversationData::getName).collect(Collectors.joining(", "));
        if (msg.fromVoice()) {
            LOGGER.warn(
                    "STT/voice: message not delivered to companion (reason={}, callByName={}, user={}, nearby=[{}], targets=[{}], preview=\"{}\"). "
                            + "If callByName is enabled, include the companion name (e.g. \"Lina, ...\") in speech.",
                    reason, callByName, msg.userName(), nearbyNames, targetNames, SttLogging.messagePreview(msg.message()));
        } else {
            LOGGER.warn(
                    "Chat: message not delivered to companion (reason={}, callByName={}, user={}, nearby=[{}], targets=[{}], preview=\"{}\")",
                    reason, callByName, msg.userName(), nearbyNames, targetNames, SttLogging.messagePreview(msg.message()));
        }
    }

    private static void logMessageBlocked(UserMessage msg, String companionName, String blockReason) {
        if (msg.fromVoice()) {
            LOGGER.warn("STT/voice: message blocked for companion={} (reason={}, user={}, preview=\"{}\")",
                    companionName, blockReason, msg.userName(), SttLogging.messagePreview(msg.message()));
        } else {
            LOGGER.debug("Chat: message blocked for companion={} (reason={}, user={})", companionName, blockReason,
                    msg.userName());
        }
    }

    // register when an AI character messages
    public static void onAICharacterMessage(Event.CharacterMessage msg, UUID senderId) {
        UUID sendingUUID = msg.sendingCharacterData().getUUID();
        MinecraftServer server = msg.sendingCharacterData().getMod().getPlayer().getServer();
        String initiator = msg.originatingUserName();
        getCloseDataByUUID(sendingUUID).filter(data -> !(data.getUUID().equals(senderId)))
                .filter(data -> initiator == null || initiator.isBlank() || server == null
                        || (!BotBlacklistPolicy.isBlocked(server, initiator, data)
                                && !UserBlacklistPolicy.isBlocked(server, initiator, data)))
                .forEach(data -> {
                    LOGGER.info("onCharMsg/ msg={}, sender={}, running onCharMsg for ={}", msg.message(), senderId,
                            data.getName());
                    data.onAICharacterMessage(msg);
                });
    }

    private static void process(Consumer<Event.CharacterMessage> onCharacterEvent,
            BiConsumer<String, ServerPlayer> onErrEvent) {
        // Group ready candidates by billing key, then dispatch at most one per bucket so a slow
        // bucket doesn't starve the others. Within a bucket we still pick max(priority) to match
        // the previous head-of-line semantics.
        Map<String, AgentConversationData> bestPerBucket = new HashMap<>();
        for (AgentConversationData data : queueData.values()) {
            if (data.getPriority() == 0
                    || data.getEntity() == null
                    || !data.getEntity().isAlive()
                    || data.getEntity().isRemoved()
                    || data.getMod().getOwner() == null) {
                continue;
            }
            Player2PayerResolution.ApiBillingContext billing = data.previewBilling();
            String billingKey = billing != null ? billing.billingKey() : null;
            if (billingKey == null) {
                // No usable billing — let AgentConversationData.process emit the standard "no billing" error.
                billingKey = "__no_billing__:" + data.getUUID();
            }
            AgentConversationData current = bestPerBucket.get(billingKey);
            if (current == null || data.getPriority() > current.getPriority()) {
                bestPerBucket.put(billingKey, data);
            }
        }
        for (Map.Entry<String, AgentConversationData> entry : bestPerBucket.entrySet()) {
            String billingKey = entry.getKey();
            AgentConversationData data = entry.getValue();
            LLMCompleter completer = getOrCreateCompleterForBillingKey(billingKey);
            if (!completer.isAvailible()) {
                continue; // bucket busy with prior in-flight call; other buckets keep moving.
            }
            Player owner = data.getMod().getOwner();
            MinecraftServer srv = owner != null ? owner.getServer() : null;
            ServerPlayer ownerServerPlayer = (srv != null) ? srv.getPlayerList().getPlayer(owner.getUUID()) : null;
            data.process(onCharacterEvent, errMsg -> onErrEvent.accept(errMsg, ownerServerPlayer), completer);
        }
    }

    // side effects are here:
    public static void injectOnTick(MinecraftServer server) {
        queueData.forEach((k, v) -> {
            if(v.getMod().getPlayer().getServer() != server){
                despwnCompanion(k);
            }
        });

        Consumer<Event.CharacterMessage> onCharacterEvent = (data) -> {
            AgentSideEffects.onEntityMessage(server, data);
        };
        BiConsumer<String, ServerPlayer> onErrEvent = (errMsg, player) -> {
            AgentSideEffects.onError(server, errMsg, player);
        };

        // No global gate: each per-billing bucket gates only its own in-flight call, and per-bot
        // TTS pacing lives in AgentConversationData. Other bots continue to make progress while one
        // bucket waits on a slow client.
        process(onCharacterEvent, onErrEvent);
    }

    public static void sendGreeting(PlayerEngineController mod, Character character) {
        LOGGER.info("Sending greeting character={}", character);
        AgentConversationData data = getOrCreateEventQueueData(mod);
        data.onGreeting();
    }

    public static void sendReturnMessage(PlayerEngineController mod, Character character, String ownerName) {
        LOGGER.info("Sending return message character={} owner={}", character, ownerName);
        AgentConversationData data = getOrCreateEventQueueData(mod);
        data.onReturn(ownerName);
    }

    public static void sendDeathRevival(PlayerEngineController mod, Character character, String deathCause) {
        LOGGER.info("Sending death revival character={} cause={}", character, deathCause);
        AgentConversationData data = getOrCreateEventQueueData(mod);
        data.onDeathRevival(deathCause);
    }

    public static void resetMemory(PlayerEngineController mod) {
        mod.getAIPersistantData().clearHistory();
    }

    private static boolean isCloseToPlayer(AgentConversationData data, String userName) {
        LOGGER.info("Passing msg btw {} <-> {}", data.getName(), userName);
        return StatusUtils.getDistanceToUsername(data.getMod(), userName) < messagePassingMaxDistance;
    }


    // recall (map : Map<T, UUID>).values() : Collection<UUID>
    public static void syncQueueData(Collection<UUID> validUuids) {
        queueData.keySet().retainAll(validUuids);
    } 

    public static void despwnCompanion(UUID id) {
        queueData.remove(id);
    }

    public static Collection<AgentConversationData> getDataByOwner(UUID ownerId) {
        if (ownerId == null) return Collections.emptyList();

        return queueData.values().stream()
                .filter(data -> {
                    if (data == null || data.getMod() == null) return false;
                    Player owner = data.getMod().getOwner();
                    if(owner == null) return false;
                    LOGGER.info("getDataByOwner: ownerId={}, test={}", ownerId, owner.getUUID());
                    return owner != null && ownerId.equals(owner.getUUID());
                })
                .collect(Collectors.toList());
    }

    /** Summary of what {@link #clearPendingWork} drained, for operator feedback. */
    public record QueueClearSummary(int queuesCleared, int bucketsShutdown) {
    }

    /**
     * Flush every {@link AgentConversationData} event queue, reset per-bot greeting / in-flight
     * flags, and shut down all per-billing LLM completer buckets. Persisted conversation history
     * is preserved (this drains pending work, not memory). Lazy bucket reconstruction takes care
     * of the next dispatch.
     */
    public static QueueClearSummary clearPendingWork() {
        int queuesCleared = 0;
        for (AgentConversationData data : queueData.values()) {
            data.resetForClear();
            queuesCleared++;
        }
        int bucketsShutdown = llmCompletersByBillingKey.size();
        for (LLMCompleter c : new ArrayList<>(llmCompletersByBillingKey.values())) {
            c.shutdown();
        }
        llmCompletersByBillingKey.clear();
        LOGGER.info("ConversationManager.clearPendingWork: queuesCleared={} bucketsShutdown={}",
                queuesCleared, bucketsShutdown);
        return new QueueClearSummary(queuesCleared, bucketsShutdown);
    }

    /**
     * Scoped variant of {@link #clearPendingWork}: only touches conversations whose owner UUID
     * matches. Bucket shutdown is best-effort here — if the owner is also the billing key (e.g.
     * OWNER_PAYS_ALL online) we shut that bucket, otherwise we leave shared buckets alone.
     */
    public static QueueClearSummary clearPendingWorkFor(UUID ownerUuid) {
        if (ownerUuid == null) {
            return new QueueClearSummary(0, 0);
        }
        int queuesCleared = 0;
        HashSet<String> seenBillingKeys = new HashSet<>();
        for (AgentConversationData data : queueData.values()) {
            Player owner = data.getMod() != null ? data.getMod().getOwner() : null;
            if (owner == null || !ownerUuid.equals(owner.getUUID())) {
                continue;
            }
            try {
                Player2PayerResolution.ApiBillingContext billing = data.previewBilling();
                String billingKey = billing != null ? billing.billingKey() : null;
                if (billingKey != null) {
                    seenBillingKeys.add(billingKey);
                }
            } catch (Exception e) {
                LOGGER.warn("clearPendingWorkFor: previewBilling threw for owner={}, msg={}", ownerUuid, e.getMessage());
            }
            data.resetForClear();
            queuesCleared++;
        }
        int bucketsShutdown = 0;
        for (String billingKey : seenBillingKeys) {
            LLMCompleter removed = llmCompletersByBillingKey.remove(billingKey);
            if (removed != null) {
                removed.shutdown();
                bucketsShutdown++;
            }
        }
        LOGGER.info("ConversationManager.clearPendingWorkFor owner={}: queuesCleared={} bucketsShutdown={}",
                ownerUuid, queuesCleared, bucketsShutdown);
        return new QueueClearSummary(queuesCleared, bucketsShutdown);
    }

}