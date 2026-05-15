package com.player2.playerengine.commands;

import com.google.gson.JsonObject;
import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.commands.base.Arg;
import com.player2.playerengine.commands.base.ArgParser;
import com.player2.playerengine.commands.base.Command;
import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.player2api.AiConversationFeedback;
import com.player2.playerengine.tasks.construction.PlaceSignTask;
import com.player2.playerengine.util.sign.SignPlacementLineSlots;
import com.player2.playerengine.util.sign.SignScanSupport;
import com.player2.playerengine.util.sign.SignTextFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.HangingSignItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

public class PlaceSignCommand extends Command {

    public enum SignAttachFace {
        north(Direction.NORTH),
        south(Direction.SOUTH),
        east(Direction.EAST),
        west(Direction.WEST),
        up(Direction.UP),
        down(Direction.DOWN);

        private final Direction direction;

        SignAttachFace(Direction direction) {
            this.direction = direction;
        }

        public Direction asDirection() {
            return direction;
        }
    }

    public PlaceSignCommand() throws CommandException {
        super(
                "place_sign",
                "place_sign <x> <y> <z> <face> [item_id] [line text…]. Anchor: solid support block coords; face = toward empty cell (floor standing sign: block_below_feet + face up). item_id optional (first sign in inventory if omitted). Hanging signs: never face up from the block below; use face down from ceiling above the air cell, or a horizontal face from a wall block toward air. Lines: f0-f3 front, b0-b3 back — use f0 Hello or f0=\"two words\" or f0=§aHi; do not put raw f0= as the visible text. § and JSON still work on the line body. Sneaks on interactables.",
                new Arg<>(Integer.class, "x"),
                new Arg<>(Integer.class, "y"),
                new Arg<>(Integer.class, "z"),
                new Arg<>(SignAttachFace.class, "face"),
                new Arg<>(String.class, "item_id", "", 4, false));
    }

    @Override
    protected void call(PlayerEngineController mod, ArgParser parser) throws CommandException {
        String[] u = parser.getArgUnits();
        if (u.length < 4) {
            throw new CommandException(
                    "place_sign needs at least x y z face. Usage: " + this.getHelpRepresentation());
        }
        int x;
        int y;
        int z;
        try {
            x = Integer.parseInt(u[0].trim());
            y = Integer.parseInt(u[1].trim());
            z = Integer.parseInt(u[2].trim());
        } catch (NumberFormatException e) {
            throw new CommandException("place_sign x y z must be integers.");
        }
        SignAttachFace faceE;
        try {
            faceE = (SignAttachFace) Arg.parseEnum(u[3].trim(), SignAttachFace.class);
        } catch (CommandException e) {
            throw new CommandException("place_sign face: " + e.getMessage());
        }
        String[] tailUnits = java.util.Arrays.copyOfRange(u, 4, u.length);
        SignPlacementLineSlots.ParsedTail parsed =
                SignPlacementLineSlots.parseTailAfterFace(tailUnits, PlaceSignCommand::resolveSignItemById);
        String itemId = parsed.itemIdOrEmpty();
        String[] raw = parsed.eightLines();

        BlockPos anchor = new BlockPos(x, y, z);
        Direction face = faceE.asDirection();
        boolean itemIdBlank = itemId.isBlank();
        Item signItem = itemIdBlank ? findFirstSignItemInInventory(mod) : resolveSignItemById(itemId);
        if (signItem == Items.AIR) {
            if (itemIdBlank) {
                AiConversationFeedback.enqueueInfo(
                        mod,
                        "{\"kind\":\"place_sign\",\"ok\":false,\"error\":\"no_sign_in_inventory\",\"item\":\"(no sign items in inventory; add a sign or pass item_id)\"}");
            } else {
                AiConversationFeedback.enqueueInfo(
                        mod,
                        "{\"kind\":\"place_sign\",\"ok\":false,\"error\":\"unknown_item\",\"item\":\""
                                + escape(itemId)
                                + "\"}");
            }
            this.finish();
            return;
        }

        if (!hasSignInInventory(mod, signItem)) {
            AiConversationFeedback.enqueueInfo(
                    mod,
                    "{\"kind\":\"place_sign\",\"ok\":false,\"error\":\"no_sign_in_inventory\",\"item\":\""
                            + BuiltInRegistries.ITEM.getKey(signItem) + "\"}");
            this.finish();
            return;
        }

        BlockState support = mod.getWorld().getBlockState(anchor);
        if (support.isAir()) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", "place_sign");
            o.addProperty("ok", false);
            o.addProperty("error", "anchor_air");
            o.addProperty(
                    "hint",
                    "anchor must be a solid block. For a floor sign at the bot's feet use agentStatus block_below_feet as x y z with face up.");
            AiConversationFeedback.enqueueInfo(mod, o.toString());
            this.finish();
            return;
        }

        if (signItem instanceof HangingSignItem && face == Direction.UP) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", "place_sign");
            o.addProperty("ok", false);
            o.addProperty("error", "hanging_sign_bad_face");
            o.addProperty(
                    "hint",
                    "Hanging signs do not place like floor standing signs (face up from the block below). Use *_hanging_sign with: ceiling hang — anchor = solid block directly above the empty cell, face = down; wall bracket — anchor = wall block, face = north/south/east/west toward an adjacent air cell (y must match the wall row).");
            AiConversationFeedback.enqueueInfo(mod, o.toString());
            this.finish();
            return;
        }

        var registries = mod.getWorld().registryAccess();
        Component[] front = SignTextFormatting.fourLinesFromStrings(new String[] {raw[0], raw[1], raw[2], raw[3]}, registries);
        Component[] back = SignTextFormatting.fourLinesFromStrings(new String[] {raw[4], raw[5], raw[6], raw[7]}, registries);

        mod.runUserTask(
                new PlaceSignTask(
                        anchor,
                        face,
                        signItem,
                        front,
                        back,
                        () -> {
                            JsonObject o = new JsonObject();
                            o.addProperty("kind", "place_sign");
                            o.addProperty("ok", true);
                            o.addProperty("anchorX", anchor.getX());
                            o.addProperty("anchorY", anchor.getY());
                            o.addProperty("anchorZ", anchor.getZ());
                            o.addProperty("face", faceE.name());
                            o.addProperty("item", BuiltInRegistries.ITEM.getKey(signItem).toString());
                            AiConversationFeedback.enqueueInfo(mod, o.toString());
                            this.finish();
                        },
                        err -> {
                            JsonObject o = new JsonObject();
                            o.addProperty("kind", "place_sign");
                            o.addProperty("ok", false);
                            o.addProperty("error", err);
                            o.addProperty("anchorX", anchor.getX());
                            o.addProperty("anchorY", anchor.getY());
                            o.addProperty("anchorZ", anchor.getZ());
                            o.addProperty("face", faceE.name());
                            if ("no_sign_block_entity_at_expected".equals(err)) {
                                BlockPos exp = SignScanSupport.expectedSignBlockPos(anchor, face);
                                o.addProperty("expectedSignX", exp.getX());
                                o.addProperty("expectedSignY", exp.getY());
                                o.addProperty("expectedSignZ", exp.getZ());
                                String hint =
                                        "Placement did not create a sign at the computed cell; check anchor/face (sign should occupy expectedSign*) or stand closer.";
                                if (signItem instanceof HangingSignItem) {
                                    hint +=
                                            " For *_hanging_sign on a wall, anchor y must match the wall block (same row as the planks), not the ground in front; ceiling hang uses face down from the block above the air cell.";
                                }
                                o.addProperty("hint", hint);
                            }
                            AiConversationFeedback.enqueueInfo(mod, o.toString());
                            this.finish();
                        }),
                () -> {});
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean hasSignInInventory(PlayerEngineController mod, Item signItem) {
        for (int i = 0; i < mod.getInventory().getContainerSize(); i++) {
            if (mod.getInventory().getItem(i).is(signItem)) {
                return true;
            }
        }
        return false;
    }

    private static Item findFirstSignItemInInventory(PlayerEngineController mod) {
        for (int i = 0; i < mod.getInventory().getContainerSize(); i++) {
            Item it = mod.getInventory().getItem(i).getItem();
            if (it instanceof net.minecraft.world.item.SignItem) {
                return it;
            }
        }
        return Items.AIR;
    }

    private static Item resolveSignItemById(String idStr) {
        String trimmed = idStr.trim().toLowerCase();
        ResourceLocation id = ResourceLocation.tryParse(trimmed.contains(":") ? trimmed : "minecraft:" + trimmed);
        if (id == null) {
            return Items.AIR;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == Items.AIR) {
            return Items.AIR;
        }
        if (!(item instanceof net.minecraft.world.item.SignItem)) {
            return Items.AIR;
        }
        return item;
    }
}
