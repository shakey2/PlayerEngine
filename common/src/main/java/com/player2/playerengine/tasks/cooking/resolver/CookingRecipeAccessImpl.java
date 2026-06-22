package com.player2.playerengine.tasks.cooking.resolver;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;

/**
 * MC 1.21.1 body of {@link CookingRecipeAccess}.
 *
 * <p>In 1.21.1, {@code RecipeManager.getAllRecipesFor(RecipeType.SMELTING)} (typed
 * {@code RecipeType<SmeltingRecipe>}) returns {@code List<RecipeHolder<SmeltingRecipe>>} — each
 * recipe is wrapped in a {@link RecipeHolder} (confirmed {@code decompiled-sources/1.21.1/.../
 * crafting/RecipeManager.java:107}). Each holder must be unwrapped via {@link RecipeHolder#value()}
 * before reaching the concrete {@code AbstractCookingRecipe}.
 *
 * <p>{@code AbstractCookingRecipe implements Recipe<SingleRecipeInput>} in 1.21.1 (confirmed
 * {@code decompiled-sources/1.21.1/.../crafting/AbstractCookingRecipe.java:8}), and the output
 * accessor is {@code getResultItem(HolderLookup.Provider)} (line 57 of that file), NOT
 * {@code getResultItem(RegistryAccess)} as in 1.20.1. Passing a {@code RegistryAccess} satisfies
 * the 1.21.1 parameter because {@code RegistryAccess extends HolderLookup.Provider}. Both
 * divergences — the {@code RecipeHolder} unwrap and the {@code getResultItem} signature — are
 * absorbed here so {@link CookingRecipeAccess} stays byte-identical across branches.
 *
 * <p>Input matching uses {@code recipe.getIngredients().get(0).test(new ItemStack(input))},
 * which is the standard furnace-family ingredient test — every {@code AbstractCookingRecipe}
 * stores exactly one ingredient (confirmed {@code AbstractCookingRecipe.java:42-47}).
 *
 * <p>Stateless — safe to use as a {@code private static final} singleton field at the call
 * site, mirroring the pattern used by {@code RecipeAccessImpl} callers
 * ({@code CraftMacroPlanner.java:66}, {@code CraftMacroTasks.java:21}, etc.).
 *
 * <p>This type is part of the deterministic resolver package: no Player2 / AiTask / Joules
 * calls. It only reads the Minecraft recipe system.
 */
public final class CookingRecipeAccessImpl implements CookingRecipeAccess {

    /**
     * Resolves a cooking recipe for {@code input} under the given {@code kind}.
     *
     * <p>Routes to the typed recipe list for the corresponding {@link RecipeType}, iterates it,
     * and returns the first recipe whose first ingredient matches {@code input}. Returns
     * {@link Optional#empty()} when no recipe matches — never crashes, never invents an output.
     *
     * <p>In 1.21.1, {@code getAllRecipesFor} returns {@code List<RecipeHolder<T>>}
     * ({@code List<RecipeHolder<SmeltingRecipe>>}, etc.); the {@code RecipeHolder<T>.value()}
     * unwrap is done in {@link #resolveFrom}. The 1.20.1 sibling receives the concrete subtype
     * list directly (no {@code RecipeHolder}).
     *
     * @param mgr        the live recipe manager ({@code level.getRecipeManager()} or
     *                   {@code server.getRecipeManager()}).
     * @param input      the raw-material item (e.g. {@code Items.RAW_IRON}).
     * @param kind       which furnace family to query.
     * @param registries server-level registry access; passed to
     *                   {@code getResultItem(HolderLookup.Provider)} (the 1.21.1 signature, which a
     *                   {@code RegistryAccess} satisfies — do NOT move this call to common/interface code).
     */
    @Override
    public Optional<CookResolution> resolve(
            RecipeManager mgr, Item input, CookKind kind, RegistryAccess registries) {
        switch (kind) {
            case SMELTING:
                return resolveFrom(mgr.getAllRecipesFor(RecipeType.SMELTING),  input, kind, registries);
            case BLASTING:
                return resolveFrom(mgr.getAllRecipesFor(RecipeType.BLASTING),  input, kind, registries);
            case SMOKING:
                return resolveFrom(mgr.getAllRecipesFor(RecipeType.SMOKING),   input, kind, registries);
            default:
                return Optional.empty();
        }
    }

    /**
     * Reverse query: true iff at least one {@code RecipeType.SMELTING} recipe produces {@code output}.
     *
     * <p>Mirrors {@link #resolveFrom}'s 1.21.1 divergence handling — {@code getAllRecipesFor} returns
     * {@code List<RecipeHolder<SmeltingRecipe>>}, each unwrapped via {@link RecipeHolder#value()}, and
     * the output read uses {@code getResultItem(HolderLookup.Provider)} (the 1.21.1 signature, which a
     * {@code RegistryAccess} satisfies). Both divergences stay local to this per-branch impl. Compares
     * by {@link ItemStack#getItem()} so result count is irrelevant.
     */
    @Override
    public boolean isSmeltingResult(RecipeManager mgr, Item output, RegistryAccess registries) {
        for (RecipeHolder<SmeltingRecipe> holder : mgr.getAllRecipesFor(RecipeType.SMELTING)) {
            SmeltingRecipe recipe = holder.value();
            if (recipe.getResultItem(registries).getItem() == output) {
                return true;
            }
        }
        return false;
    }

    // ---- internal ----

    /**
     * Iterates a typed {@code RecipeHolder} list, unwraps each via {@link RecipeHolder#value()}
     * (the 1.21.1 wrapper), and returns the first recipe whose first ingredient matches a
     * single-item stack of {@code input}.
     *
     * <p>{@code getIngredients().get(0)} is safe: every {@code AbstractCookingRecipe} stores
     * exactly one ingredient, added as the only element of a one-slot {@code NonNullList}
     * (confirmed {@code AbstractCookingRecipe.java:42-47}). The {@code isEmpty()} guard
     * defends against malformed custom recipes.
     *
     * <p>{@code getResultItem(HolderLookup.Provider)} is the 1.21.1 signature
     * ({@code AbstractCookingRecipe.java:57}); passing the {@link RegistryAccess} satisfies it
     * ({@code RegistryAccess extends HolderLookup.Provider}). This call is intentionally in the
     * per-branch impl to absorb the version divergence — the 1.20.1 counterpart uses
     * {@code getResultItem(RegistryAccess)} and receives an unwrapped recipe list. Do NOT move this
     * call (or the {@code RecipeHolder} unwrap) to the interface or any common caller.
     *
     * @param recipes    full typed {@code RecipeHolder} list from {@code RecipeManager.getAllRecipesFor(...)}.
     * @param input      the raw-material item to match.
     * @param kind       embedded in the returned {@link CookResolution}.
     * @param registries passed to {@code getResultItem(HolderLookup.Provider)}.
     * @return first matching resolution, or {@link Optional#empty()}.
     */
    private <R extends AbstractCookingRecipe> Optional<CookResolution> resolveFrom(
            List<RecipeHolder<R>> recipes, Item input, CookKind kind, RegistryAccess registries) {
        ItemStack probe = new ItemStack(input);
        for (RecipeHolder<R> holder : recipes) {
            R recipe = holder.value();
            if (recipe.getIngredients().isEmpty()) {
                continue; // defensive: skip malformed custom recipes without an ingredient
            }
            if (!recipe.getIngredients().get(0).test(probe)) {
                continue;
            }
            // getResultItem(HolderLookup.Provider) — 1.21.1 signature (AbstractCookingRecipe.java:57);
            // RegistryAccess satisfies it. Call is intentionally local to this per-branch impl; do NOT
            // propagate to interface.
            ItemStack output = recipe.getResultItem(registries).copy();
            int cookTicks = recipe.getCookingTime();
            return Optional.of(new CookResolution(output, cookTicks, kind));
        }
        return Optional.empty();
    }
}
