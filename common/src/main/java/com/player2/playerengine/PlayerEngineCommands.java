package com.player2.playerengine;

import com.player2.playerengine.commands.*;
import com.player2.playerengine.commands.*;
import com.player2.playerengine.commands.random.*;
import com.player2.playerengine.commands.base.CommandException;

public class PlayerEngineCommands {
   public static void init(PlayerEngineController controller) throws CommandException {
      controller.getCommandExecutor()
            .registerNewCommand(
                  new GetCommand(),
                  new EquipCommand(),
                  new BuildStructureCommand(),
                  new BodyLanguageCommand(),
                  new DepositCommand(),
                  new GotoCommand(),
                  new IdleCommand(),
                  new HeroCommand(),
                  new LocateStructureCommand(),
                  new StopCommand(),
                  new FoodCommand(),
                  new MeatCommand(),
                  new ReloadSettingsCommand(),
                  new ResetMemoryCommand(),
                  // DISABLED for release: "beat the game" (gamer) is broken. Registration cut so it
                  // cannot be activated; GamerCommand/BeatMinecraftTask code left intact. Re-enable
                  // here AND in SeedToolMetadata (doc("gamer", ...)) once fixed.
                  // new GamerCommand(),
                  new FollowCommand(),
                  new LeaveBoatCommand(),
                  new GiveCommand(),
                  new ScanCommand(),
                  new AttackPlayerOrMobCommand(),
                  new SetAIBridgeEnabledCommand(),
                  new FarmCommand(),
                  new EatFoodCommand(),
                  new PickupDropsCommand(),
                  new AgenticCommand(),
                  new SetHostileAttackCommand(),
                  new FishCommand(),
                  new ReadNearbySignsCommand(),
                  new PlaceSignCommand(),
                  new ScanStorageCommand(),
                  new WithdrawFromStorageCommand(),
                  new DepositToStorageCommand(),
                  new WithdrawStorageSlotCommand(),
                  new DepositStorageSlotCommand(),
                  new LocateStorageCommand(),
                  new CreateWaypointCommand(),
                  new DeleteWaypointCommand(),
                  new AuditWaypointCommand(),
                  new CompareWaypointCommand(),
                  new LocateWaypointsCommand());
   }
}
