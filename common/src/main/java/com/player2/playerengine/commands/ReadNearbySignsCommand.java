package com.player2.playerengine.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.Arg;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.player2api.AiConversationFeedback;
import com.player2.playerengine.player2api.manager.ConversationManager;
import com.player2.playerengine.player2api.AgentConversationData;
import com.player2.playerengine.util.sign.SignScanSupport;
import com.player2.playerengine.util.sign.SignTextFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class ReadNearbySignsCommand extends Command {
    private static final int DEFAULT_RADIUS = 32;
    private static final int MAX_RADIUS = 64;

    public ReadNearbySignsCommand() throws CommandException {
        super(
                "read_signs",
                "List nearby signs with raw §+JSON lines, world block pos, deltas from bot eye (world axes), bot yaw/pitch and cardinal, and deltas from prompting player feet if chain initiator is online within the same radius. Optional radius (default 32, max 64).",
                new Arg<>(Integer.class, "radius", DEFAULT_RADIUS, 0));
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        int radius = parser.get(Integer.class);
        if (radius < 1) {
            radius = 1;
        }
        if (radius > MAX_RADIUS) {
            radius = MAX_RADIUS;
        }

        ServerLevel level = mod.getWorld();
        LivingEntity bot = mod.getPlayer();
        Vec3 botEye = bot.getEyePosition(1.0F);
        float yaw = bot.getYRot();
        float pitch = bot.getXRot();
        String cardinal = SignScanSupport.cardinalFromYaw(yaw);

        JsonObject root = new JsonObject();
        root.addProperty("kind", "read_signs");
        root.addProperty("radius", radius);
        JsonObject botSnap = new JsonObject();
        botSnap.addProperty("eyeX", round4(botEye.x));
        botSnap.addProperty("eyeY", round4(botEye.y));
        botSnap.addProperty("eyeZ", round4(botEye.z));
        botSnap.addProperty("yawDeg", round4(yaw));
        botSnap.addProperty("pitchDeg", round4(pitch));
        botSnap.addProperty("cardinalApprox", cardinal);
        root.add("bot", botSnap);

        BlockPos origin = bot.blockPosition();
        int rSq = radius * radius;
        JsonArray signs = new JsonArray();
        int r = radius;
        int count = 0;
        for (int dx = -r; dx <= r && count < SignScanSupport.MAX_SIGNS; dx++) {
            for (int dy = -r; dy <= r && count < SignScanSupport.MAX_SIGNS; dy++) {
                for (int dz = -r; dz <= r && count < SignScanSupport.MAX_SIGNS; dz++) {
                    if (dx * dx + dy * dy + dz * dz > rSq) {
                        continue;
                    }
                    BlockPos p = origin.offset(dx, dy, dz);
                    if (!level.isLoaded(p)) {
                        continue;
                    }
                    if (!(level.getBlockEntity(p) instanceof SignBlockEntity sign)) {
                        continue;
                    }
                    count++;
                    BlockState st = level.getBlockState(p);
                    ResourceLocation blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock());
                    Vec3 signCenter = Vec3.atCenterOf(p);
                    JsonObject one = new JsonObject();
                    one.addProperty("block", blockId.toString());
                    one.addProperty("x", p.getX());
                    one.addProperty("y", p.getY());
                    one.addProperty("z", p.getZ());
                    one.addProperty("dxFromBotEye", round4(signCenter.x - botEye.x));
                    one.addProperty("dyFromBotEye", round4(signCenter.y - botEye.y));
                    one.addProperty("dzFromBotEye", round4(signCenter.z - botEye.z));
                    one.addProperty("waxed", sign.isWaxed());
                    one.add("front", linesJson(sign, true, level.registryAccess()));
                    one.add("back", linesJson(sign, false, level.registryAccess()));
                    signs.add(one);
                }
            }
        }
        root.add("signs", signs);
        root.addProperty("signCount", signs.size());

        JsonObject prompting = new JsonObject();
        AgentConversationData data = ConversationManager.getOrCreateEventQueueData(mod);
        String initiator = data.getChainInitiatorUsername();
        if (initiator == null || initiator.isBlank()) {
            prompting.addProperty("status", "none");
        } else {
            prompting.addProperty("username", initiator);
            ServerPlayer pl = level.getServer().getPlayerList().getPlayerByName(initiator);
            if (pl == null || pl.level() != level) {
                prompting.addProperty("status", "not_loaded");
            } else {
                double dist = pl.distanceTo(bot);
                prompting.addProperty("distanceToBot", round4(dist));
                if (dist > radius) {
                    prompting.addProperty("status", "too_far");
                } else {
                    Vec3 feet = pl.position();
                    prompting.addProperty("status", "nearby");
                    prompting.addProperty("feetX", round4(feet.x));
                    prompting.addProperty("feetY", round4(feet.y));
                    prompting.addProperty("feetZ", round4(feet.z));
                    JsonArray rel = new JsonArray();
                    for (int i = 0; i < signs.size(); i++) {
                        JsonObject s = signs.get(i).getAsJsonObject();
                        double sx = s.get("x").getAsInt() + 0.5;
                        double sy = s.get("y").getAsInt() + 0.5;
                        double sz = s.get("z").getAsInt() + 0.5;
                        JsonObject d = new JsonObject();
                        d.addProperty("dxFromPlayerFeet", round4(sx - feet.x));
                        d.addProperty("dyFromPlayerFeet", round4(sy - feet.y));
                        d.addProperty("dzFromPlayerFeet", round4(sz - feet.z));
                        rel.add(d);
                    }
                    prompting.add("perSignFromPlayerFeet", rel);
                }
            }
        }
        root.add("promptingPlayer", prompting);

        AiConversationFeedback.enqueueInfo(mod, root.toString());
        this.finish();
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static JsonArray linesJson(SignBlockEntity sign, boolean front, HolderLookup.Provider registries) {
        JsonArray arr = new JsonArray();
        var text = front ? sign.getFrontText() : sign.getBackText();
        for (int line = 0; line < 4; line++) {
            var comp = text.getMessage(line, false);
            JsonObject o = new JsonObject();
            String legacy = SignTextFormatting.componentToLegacySection(comp);
            String json = SignTextFormatting.componentToJson(comp, registries);
            o.addProperty("legacySection", SignScanSupport.truncate(legacy, SignScanSupport.MAX_LINE_LEN));
            o.addProperty("componentJson", SignScanSupport.truncate(json, SignScanSupport.MAX_LINE_LEN));
            arr.add(o);
        }
        return arr;
    }
}
