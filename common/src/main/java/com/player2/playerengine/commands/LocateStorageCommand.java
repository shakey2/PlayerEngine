package com.player2.playerengine.commands;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.containeraccess.ContainerKind;
import com.player2.playerengine.containeraccess.ContainerResolver;
import com.player2.playerengine.containeraccess.StorageLocator;
import com.player2.playerengine.player2api.AgentConversationData;
import com.player2.playerengine.player2api.manager.ConversationManager;
import com.player2.playerengine.util.Debug;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * {@code locate_storage} — standalone storage-container locator. Reports up to THREE labeled
 * canonical coordinates to the model so either the player can say "the chest near you" or the
 * model can pick the coordinate that makes contextual sense:
 * <ol>
 *   <li>{@code chest user is looking at: x y z} — the supported container the prompting
 *       player's server-side eye ray hits (omitted entirely when not looking at one, or when
 *       no player is resolvable);
 *   <li>{@code near user: x y z} — the supported container nearest to the prompting player;
 *   <li>{@code near you: x y z} — the supported container nearest to the bot.
 * </ol>
 *
 * <p>Pure instant lookup: NO navigation, NO animation, never moves the bot. Searches are
 * capped at {@link ContainerResolver#STORAGE_TRAVEL_CAP_BLOCKS} so every reported coordinate
 * is actually usable by the storage commands, and every position is the CANONICAL one (double
 * chests report the canonical half) so it can be passed verbatim to {@code scan_storage} /
 * {@code withdraw_from_storage} / {@code deposit_to_storage}.
 *
 * <p>"User" resolution reuses the established prompting-player idiom: the chain initiator if
 * online in the bot's level ({@code read_signs} precedent), else the owner, else the nearest
 * player ({@code reportAgenticProgress} precedent). With no resolvable player, only the
 * "near you" line is emitted.
 *
 * <p>Chat stays silent on success (data lookup; the {@code scan_storage} Decision 5
 * precedent) — the payload goes to the model only, via {@link #finishWithInfo(String)}.
 * Finding nothing is a successful empty result, reported truthfully, never an error.
 */
public class LocateStorageCommand extends Command {

    public LocateStorageCommand() throws CommandException {
        super(
                "locate_storage",
                "locate_storage. Instantly reports the coordinates of nearby storage containers"
                        + " (chest, trapped chest, barrel, shulker box): the container the prompting"
                        + " user is looking at, the one nearest the user, and the one nearest you."
                        + " Takes no arguments, never moves you. Feed the returned coordinates to"
                        + " scan_storage / withdraw_from_storage / deposit_to_storage.");
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        ServerLevel level = mod.getWorld();
        LivingEntity bot = mod.getEntity();
        ServerPlayer user = resolvePromptingPlayer(mod, level);

        List<String> lines = new ArrayList<>();
        if (user != null) {
            StorageLocator.findLookedAtContainer(level, user, StorageLocator.LOOK_REACH_BLOCKS)
                    .ifPresent(located -> lines.add("chest user is looking at: " + render(located)));
            StorageLocator.findNearestContainer(
                            mod, user.position(), ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS)
                    .ifPresent(located -> lines.add("near user: " + render(located)));
        }
        StorageLocator.findNearestContainer(
                        mod, bot.position(), ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS)
                .ifPresent(located -> lines.add("near you: " + render(located)));

        String payload;
        if (lines.isEmpty()) {
            // Truthful empty result — a success, not an error (nothing here can really fail).
            payload = "no storage containers found within "
                    + (int) ContainerResolver.STORAGE_TRAVEL_CAP_BLOCKS + " blocks of you or the user";
        } else {
            payload = String.join("\n", lines);
        }
        Debug.logMessage("storage-locate ok lines=" + lines.size()
                + " user=" + (user != null ? user.getGameProfile().getName() : "none")
                + " bot=" + bot.getName().getString());
        // Deliver the coordinates as the command-finish RESULT rather than a bare enqueueInfo + finish().
        // The old pattern left the just-enqueued InfoMessage in the event queue at finish time, so
        // onCommandFinish's "queue not empty" guard suppressed the standard "what shall we do next?"
        // cue — the model got raw coordinates with no follow-up prompt and picked `deposit` (no coords)
        // instead of `deposit_to_storage <x y z>`. finishWithInfo routes the payload through the
        // command-finish prompt so the coordinates AND the next-step cue arrive together.
        this.finishWithInfo(payload);
    }

    /** One labeled coordinate; the kind token is appended only when it is not a plain chest. */
    private static String render(StorageLocator.Located located) {
        String line = located.canonicalPos().getX() + " " + located.canonicalPos().getY()
                + " " + located.canonicalPos().getZ();
        if (located.kind() != ContainerKind.CHEST) {
            line += " (" + located.kind().token() + ")";
        }
        return line;
    }

    /**
     * Established prompting-player idiom: chain initiator if online in the bot's level
     * ({@code read_signs}), else the owner, else the nearest player
     * ({@code reportAgenticProgress}); {@code null} when none resolves.
     */
    private static ServerPlayer resolvePromptingPlayer(PlayerEngineController mod, ServerLevel level) {
        if (level == null) {
            return null;
        }
        AgentConversationData data = ConversationManager.getOrCreateEventQueueData(mod);
        String initiator = data.getChainInitiatorUsername();
        if (initiator != null && !initiator.isBlank()) {
            ServerPlayer pl = level.getServer().getPlayerList().getPlayerByName(initiator);
            if (pl != null && pl.level() == level) {
                return pl;
            }
        }
        if (mod.getOwner() instanceof ServerPlayer owner && owner.level() == level) {
            return owner;
        }
        Optional<ServerPlayer> closest = mod.getClosestPlayer();
        return closest.filter(pl -> pl.level() == level).orElse(null);
    }
}
