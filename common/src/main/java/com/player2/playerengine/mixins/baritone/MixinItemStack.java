package com.player2.playerengine.mixins.baritone;

import com.player2.playerengine.automaton.api.utils.BaritoneStackDamage;
import com.player2.playerengine.automaton.api.utils.accessor.IItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({ItemStack.class})
public abstract class MixinItemStack implements IItemStack {
   @Shadow
   @Final
   private Item item;
   @Unique
   private int baritoneHash;
   @Unique
   private boolean baritoneHashValid;

   /**
    * Do not call {@link ItemStack#getDamageValue()} here: Forge item extensions may call
    * {@link net.minecraft.world.item.Item#getMaxDamage()} during stack construction or copy.
    */
   private void recalculateHash() {
      ItemStack self = (ItemStack)(Object)this;
      this.baritoneHash = this.item == null ? -1 : this.item.hashCode() + BaritoneStackDamage.storedDamage(self);
      this.baritoneHashValid = true;
   }

   @Inject(
      method = {"setDamageValue"},
      at = {@At("TAIL")}
   )
   private void onItemDamageSet(CallbackInfo ci) {
      this.recalculateHash();
   }

   @Override
   public int getBaritoneHash() {
      if (!this.baritoneHashValid) {
         this.recalculateHash();
      }

      return this.baritoneHash;
   }
}
