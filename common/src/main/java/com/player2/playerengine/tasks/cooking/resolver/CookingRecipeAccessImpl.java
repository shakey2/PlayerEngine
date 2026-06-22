package com.player2.playerengine.tasks.cooking.resolver;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;

/**
 * MC 1.20.1 body of {@link CookingRecipeAccess}.
 *
 * <p>In 1.20.1, {@code RecipeManager.getAllRecipesFor(RecipeType.SMELTING)} (typed
 * {@code RecipeType<SmeltingRecipe>}) returns {@code List<SmeltingRecipe>} directly — no
 * {@code RecipeHolder} wrapper (confirmed {@code decompiled-sources/1.20.1/.../crafting/
 * RecipeType.java:9-11} and {@code RecipeManager.java:116}).
 *
 * <p>{@code AbstractCookingRecipe implements Recipe<Container>} in 1.20.1 (confirmed
 * {@code decompiled-sources/1.20.1/.../crafting/AbstractCookingRecipe.java:10}), and the
 * output accessor is {@code getResultItem(RegistryAccess)} (line 70 of that file). In 1.21.1
 * the same call takes {@code HolderLookup.Provider} and the result is wrapped in a
 * {@code RecipeHolder} — both divergences are absorbed here so {@link CookingRecipeAccess}
 * stays byte-identical across branches.
 *
 * <p>Input matching uses {@code recipe.getIngredients().get(0).test(new ItemStack(input))},
 * which is the standard furnace-family ingredient test — every {@code AbstractCookingRecipe}
 * stores exactly one ingredient (confirmed {@code AbstractCookingRecipe.java:56-59}).
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
     * <p>In 1.20.1, {@code getAllRecipesFor} returns the recipe subtype directly ({@code
     * List<SmeltingRecipe>}, {@code List<BlastingRecipe>}, {@code List<SmokingRecipe>}); all
     * three extend {@code AbstractCookingRecipe}, so a single generic helper handles all three.
     * The 1.21.1 sibling must unwrap {@code RecipeHolder<T>.value()} before reaching this point.
     *
     * @param mgr        the live recipe manager ({@code level.getRecipeManager()} or
     *                   {@code server.getRecipeManager()}).
     * @param input      the raw-material item (e.g. {@code Items.RAW_IRON}).
     * @param kind       which furnace family to query.
     * @param registries server-level registry access; passed to {@code getResultItem(RegistryAccess)}
     *                   (the 1.20.1 signature — do NOT move this call to common/interface code).
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
     * <p>In 1.20.1, {@code getAllRecipesFor} returns the concrete {@link SmeltingRecipe} subtype
     * directly (no {@code RecipeHolder} wrapper). The output is read via
     * {@code getResultItem(RegistryAccess)} — the 1.20.1 signature
     * ({@code AbstractCookingRecipe.java:70}). Both divergences stay local to this per-branch impl;
     * the 1.21.1 sibling unwraps {@code RecipeHolder<SmeltingRecipe>.value()} and uses the
     * {@code HolderLookup.Provider} signature. Compares by {@link ItemStack#getItem()} so
     * result count is irrelevant.
     */
    @Override
    public boolean isSmeltingResult(RecipeManager mgr, Item output, RegistryAccess registries) {
        for (SmeltingRecipe recipe : mgr.getAllRecipesFor(RecipeType.SMELTING)) {
            if (recipe.getResultItem(registries).getItem() == output) {
                return true;
            }
        }
        return false;
    }

    // ---- internal ----

    /**
     * Iterates a typed recipe list (already the concrete subtype in 1.20.1 — no
     * {@code RecipeHolder} unwrap needed) and returns the first recipe whose first ingredient
     * matches a single-item stack of {@code input}.
     *
     * <p>{@code getIngredients().get(0)} is safe: every {@code AbstractCookingRecipe} stores
     * exactly one ingredient, added as the only element of a one-slot {@code NonNullList}
     * (confirmed {@code AbstractCookingRecipe.java:56-59}). The {@code isEmpty()} guard
     * defends against malformed custom recipes.
     *
     * <p>{@code getResultItem(RegistryAccess)} is the 1.20.1 signature
     * ({@code AbstractCookingRecipe.java:70}). This call is intentionally in the per-branch
     * impl to absorb the version divergence — the 1.21.1 counterpart uses
     * {@code HolderLookup.Provider} instead. Do NOT move this call to the interface or any
     * common caller.
     *
     * @param recipes    full typed recipe list from {@code RecipeManager.getAllRecipesFor(...)}.
     * @param input      the raw-material item to match.
     * @param kind       embedded in the returned {@link CookResolution}.
     * @param registries passed to {@code getResultItem(RegistryAccess)}.
     * @return first matching resolution, or {@link Optional#empty()}.
     */
    private <R extends AbstractCookingRecipe> Optional<CookResolution> resolveFrom(
            List<R> recipes, Item input, CookKind kind, RegistryAccess registries) {
        ItemStack probe = new ItemStack(input);
        for (R recipe : recipes) {
            if (recipe.getIngredients().isEmpty()) {
                continue; // defensive: skip malformed custom recipes without an ingredient
            }
            if (!recipe.getIngredients().get(0).test(probe)) {
                continue;
            }
            // getResultItem(RegistryAccess) — 1.20.1 signature (AbstractCookingRecipe.java:70).
            // Call is intentionally local to this per-branch impl; do NOT propagate to interface.
            ItemStack output = recipe.getResultItem(registries).copy();
            int cookTicks = recipe.getCookingTime();
            return Optional.of(new CookResolution(output, cookTicks, kind));
        }
        return Optional.empty();
    }
}
