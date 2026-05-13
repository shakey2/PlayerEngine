package com.player2.playerengine.mixins.baritone;

import com.player2.playerengine.PlayerEngine;
import net.minecraft.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({Util.class})
public abstract class MixinUtil {

   @Inject(
      method = {"shutdownExecutors"},
      at = {@At("RETURN")}
   )
   private static void shutdownBaritoneExecutor(CallbackInfo ci) {
      PlayerEngine.shutdownBackgroundExecutors();
   }
}
