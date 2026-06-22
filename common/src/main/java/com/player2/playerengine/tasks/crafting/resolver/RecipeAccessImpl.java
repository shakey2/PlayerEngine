package com.player2.playerengine.tasks.crafting.resolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * MC 1.20.1 body of {@link RecipeAccess}.
 *
 * <p>In 1.20.1 the crafting recipe set is reached without a {@code RecipeHolder} wrapper:
 * {@code RecipeManager.getAllRecipesFor(RecipeType.CRAFTING)} (typed
 * {@code RecipeType<CraftingRecipe>}) returns {@code List<CraftingRecipe>} directly (verified
 * {@code decompiled-sources/1.20.1/.../crafting/RecipeManager.java:116} and {@code RecipeType.java:8}),
 * and the MC {@code CraftingRecipe} interface here is {@code CraftingRecipe extends
 * Recipe<CraftingContainer>} ({@code decompiled-sources/1.20.1/.../crafting/CraftingRecipe.java:5}).
 *
 * <p>This is one of the two documented per-branch bodies (alongside {@code IngredientInspectorImpl});
 * the 1.21.1 sibling must instead unwrap {@code RecipeHolder<CraftingRecipe>} via {@code .value()} and
 * absorbs the {@code Recipe<CraftingInput>} generic bound. Exposing only the common MC
 * {@link CraftingRecipe} interface keeps {@code MaterialResolver} byte-identical across branches.
 *
 * <p>This type is part of the deterministic resolver package: no Player2/AiTask/Joules calls.
 */
public final class RecipeAccessImpl implements RecipeAccess {

   @Override
   public List<CraftingRecipe> getCraftingRecipes(RecipeManager mgr) {
      // 1.20.1: getAllRecipesFor returns the MC CraftingRecipe directly (no RecipeHolder to unwrap).
      // Pre-filter special recipes (CustomRecipe subclasses: firework, shulker dye, banner, map clone,
      // repair, tipped arrow, etc.) and incomplete recipes (empty/unbound ingredient lists). Both
      // isSpecial() and isIncomplete() exist on the Recipe interface in 1.20.1 (decompiled-sources
      // 1.20.1/.../crafting/Recipe.java). Calling them here keeps the resolver from mis-classifying
      // BARRIER-returning or unresolvable ingredient slots downstream.
      List<CraftingRecipe> all = mgr.getAllRecipesFor(RecipeType.CRAFTING);
      List<CraftingRecipe> result = new ArrayList<>(all.size());
      for (CraftingRecipe recipe : all) {
         if (recipe.isSpecial() || recipe.isIncomplete()) {
            continue;
         }
         result.add(recipe);
      }
      return result;
   }

   @Override
   public Optional<CraftingRecipe> selectRecipeForResult(RecipeManager mgr, Item target, RegistryAccess registries) {
      // Load-bearing variant-lock step: keep only recipes whose result item == the concrete target,
      // then prefer one that fits a 2x2 grid (no crafting table needed). getResultItem(RegistryAccess)
      // is byte-identical across versions (RegistryAccess extends HolderLookup.Provider).
      CraftingRecipe firstMatch = null;
      for (CraftingRecipe recipe : getCraftingRecipes(mgr)) {
         if (recipe.getResultItem(registries).getItem() != target) {
            continue;
         }
         if (recipe.canCraftInDimensions(2, 2)) {
            return Optional.of(recipe);
         }
         if (firstMatch == null) {
            firstMatch = recipe;
         }
      }
      return Optional.ofNullable(firstMatch);
   }

   @Override
   public List<CraftingRecipe> recipesForResult(RecipeManager mgr, Item target, RegistryAccess registries) {
      // Registry-order list of EVERY recipe producing `target` (e.g. stick has a planks recipe AND a
      // bamboo recipe); the resolver's funding-aware selection picks among them.
      List<CraftingRecipe> out = new ArrayList<>();
      for (CraftingRecipe recipe : getCraftingRecipes(mgr)) {
         if (recipe.getResultItem(registries).getItem() == target) {
            out.add(recipe);
         }
      }
      return out;
   }

   @Override
   public int outputCountOf(CraftingRecipe recipe, RegistryAccess registries) {
      // MC's Recipe interface has NO outputCount(); the per-craft yield is the result stack's count.
      return recipe.getResultItem(registries).getCount();
   }

   /**
    * WS6 implementation — 1.20.1 branch.
    *
    * <p>Constructs a {@link TransientCraftingContainer} from the given slot contents to call
    * {@link CraftingRecipe#getRemainingItems}. In 1.20.1, {@link CraftingRecipe} extends
    * {@code Recipe<CraftingContainer>}, so {@code getRemainingItems} takes a {@link
    * net.minecraft.world.inventory.CraftingContainer} (which {@code TransientCraftingContainer}
    * implements).
    *
    * <p>The 4-argument {@code TransientCraftingContainer} constructor requires a
    * {@link NonNullList}{@code <ItemStack>}, not a plain {@link List}. A copy is made here:
    * {@code NonNullList.withSize(...)} + a set loop.
    *
    * <p>The required {@link AbstractContainerMenu} stub calls {@code super(null, 0)} (the
    * {@code (@Nullable MenuType<?>, int)} constructor in 1.20.1 AbstractContainerMenu) and implements
    * the two abstract methods: {@code quickMoveStack(Player, int)} returns {@link ItemStack#EMPTY},
    * and {@code stillValid(Player)} returns {@code true}. The {@code slotsChanged} override is a
    * no-op. The stub's only role is to satisfy {@code TransientCraftingContainer}'s constructor
    * signature; {@code getRemainingItems}'s default body only reads
    * {@code getContainerSize()}/{@code getItem(i)} and never calls back into the menu.
    *
    * <p>The returned {@link NonNullList} is indexed 0..width*height-1 (unlike the 1.21.1 branch where
    * {@code CraftingInput.of} crops the grid). Callers must iterate and reinsert each non-empty stack
    * regardless.
    */
   @Override
   public NonNullList<ItemStack> getRemainingItems(
         CraftingRecipe recipe, List<ItemStack> slotContents,
         int width, int height, RegistryAccess registries) {
      // Copy the plain List into a NonNullList so the 4-arg TransientCraftingContainer ctor is satisfied.
      NonNullList<ItemStack> nn = NonNullList.withSize(slotContents.size(), ItemStack.EMPTY);
      for (int i = 0; i < slotContents.size(); i++) {
         ItemStack s = slotContents.get(i);
         nn.set(i, s != null ? s : ItemStack.EMPTY);
      }
      TransientCraftingContainer container = new TransientCraftingContainer(MENU_STUB, width, height, nn);
      return recipe.getRemainingItems(container);
   }

   /**
    * Minimal {@link AbstractContainerMenu} stub required by the {@link TransientCraftingContainer}
    * constructor in 1.20.1. The stub has no behavior: {@code quickMoveStack} returns
    * {@link ItemStack#EMPTY}, {@code stillValid} returns {@code true}, and {@code slotsChanged} is a
    * no-op. {@code super(null, 0)} satisfies the {@code (@Nullable MenuType<?>, int)} constructor.
    *
    * <p>A single static instance is safe because the stub has no mutable state and
    * {@code getRemainingItems}'s default body never calls {@code setItem}, {@code removeItem}, or
    * any other mutating method — it only reads {@code getContainerSize()} and {@code getItem(i)}
    * from the container, not from the menu.
    */
   private static final AbstractContainerMenu MENU_STUB = new AbstractContainerMenu(null, 0) {
      @Override
      public ItemStack quickMoveStack(Player player, int index) {
         return ItemStack.EMPTY;
      }

      @Override
      public boolean stillValid(Player player) {
         return true;
      }

      @Override
      public void slotsChanged(Container container) {
         // no-op: TransientCraftingContainer calls this on setItem/removeItem, but
         // getRemainingItems default body never mutates the container.
      }
   };
}
