package com.player2.playerengine.mixins.baritone;

import com.player2.playerengine.PlayerEngine;
import java.util.concurrent.ExecutorService;

import com.player2.playerengine.player2api.auth.AuthenticationManager;
import com.player2.playerengine.player2api.manager.TTSManager;
import net.minecraft.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({Util.class})
public abstract class MixinUtil {
   @Shadow
   private static void shutdownExecutor(ExecutorService service) {
   }

   @Inject(
      method = {"shutdownExecutors"},
      at = {@At("RETURN")}
   )
   private static void shutdownBaritoneExecutor(CallbackInfo ci) {
      shutdownExecutor(PlayerEngine.getExecutor());
      shutdownExecutor(TTSManager.getExecutor());
      shutdownExecutor(AuthenticationManager.getExecutor());
      shutdownExecutor(AuthenticationManager.getPollingExecutor());
   }
}
