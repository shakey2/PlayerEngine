package com.player2.playerengine.util.time;

import com.player2.playerengine.PlayerEngineController;
import com.player2.playerengine.util.Debug;

/**
 * A game-time timer that works on both client and dedicated server.
 * Uses a shared tick counter incremented from the server tick loop,
 * avoiding any reference to client-only classes like Minecraft.
 */
public class TimerGame extends BaseTimer {
   // Shared tick counter, incremented once per server tick.
   private static volatile int serverTicks = 0;

   public TimerGame(double intervalSeconds) {
      super(intervalSeconds);
   }

   /**
    * Called once per server tick to advance the shared clock.
    * Hooked from PlayerEngineController or a TickEvent.
    */
   public static void incrementServerTick() {
      serverTicks++;
   }

   @Override
   protected double currentTime() {
      if (!PlayerEngineController.inGame()) {
         Debug.logError("Running game timer while not in game.");
         return 0.0;
      }
      // Convert ticks to seconds (20 ticks/sec)
      return serverTicks / 20.0;
   }
}
