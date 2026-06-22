package com.player2.playerengine.tasks.smithing.resolver;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;

/**
 * Cross-version access to the Minecraft smithing recipe set (transform recipes only;
 * trim recipes are explicitly excluded). Mirrors {@link
 * com.player2.playerengine.tasks.cooking.resolver.CookingRecipeAccess} — the interface is
 * byte-identical across the 1.20.1 and 1.21.1 PlayerEngine branches; all version divergences
 * are absorbed by the per-branch {@code SmithingRecipeAccessImpl}.
 *
 * <h3>Non-negotiables (from the plan)</h3>
 * <ol>
 *   <li>Only {@code SmithingTransformRecipe} is matched; {@code SmithingTrimRecipe} is
 *       explicitly skipped.</li>
 *   <li>No {@link net.minecraft.world.item.crafting.Ingredient} crosses this interface.
 *       The three role ingredient lists are extracted to plain {@code List<Item>} INSIDE
 *       the per-branch impl so the 1.21.1 Holder-based enumeration never leaks into
 *       common code.</li>
 *   <li>Matching is done by {@code Item} identity
 *       ({@code recipe.getResultItem(registries).getItem() == output}), never by
 *       registry-id strings or display names.</li>
 * </ol>
 *
 * <h3>Version seams isolated in {@code SmithingRecipeAccessImpl}</h3>
 * <ul>
 *   <li>1.20.1: {@code mgr.getAllRecipesFor(RecipeType.SMITHING)} returns
 *       {@code List<SmithingRecipe>} directly.</li>
 *   <li>1.21.1: same call returns {@code List<RecipeHolder<SmithingRecipe>>}; the impl
 *       unwraps via {@code holder.value()} before proceeding.</li>
 *   <li>{@code getResultItem}: 1.20.1 takes {@code RegistryAccess}; 1.21.1 takes
 *       {@code HolderLookup.Provider}. Both calls are in the per-branch impl only.</li>
 *   <li>Ingredient item extraction: both branches call {@code Ingredient.getItems()} (returns
 *       {@code ItemStack[]}) on the three role ingredients read via reflection — the enumeration
 *       code is identical across branches. (The 1.21.1 {@code RecipeHolder} unwrap applies to the
 *       recipe <em>list</em>, not to the ingredient items.) Both are in the per-branch impl only.</li>
 * </ul>
 *
 * <p>Callers hold the impl as:
 * <pre>{@code
 * private static final SmithingRecipeAccess SMITHING_RECIPE_ACCESS = new SmithingRecipeAccessImpl();
 * }</pre>
 * (mirrors the {@code COOKING_RECIPE_ACCESS} singleton pattern).
 *
 * <p>This type is part of the deterministic resolver package: it performs no Player2 / AiTask /
 * Joules calls and only reads the Minecraft recipe system.
 *
 * @see SmithingResolution
 */
public interface SmithingRecipeAccess {

    /**
     * The result of resolving a single smithing-transform upgrade.
     *
     * <p>Only loader-neutral, version-stable common MC types cross this record — NO
     * {@link net.minecraft.world.item.crafting.Ingredient}. The accepted items per role are
     * extracted to plain {@code List<Item>} INSIDE the per-branch impl (non-negotiable #2).
     *
     * @param output   concrete result stack (preserves NBT/DataComponents via
     *                 {@code getResultItem(registries)} — call is inside the impl).
     * @param template accepted template items (non-empty; first = canonical gather target).
     * @param base     accepted base items (the tool/armor being upgraded).
     * @param addition accepted addition items (the upgrade material, e.g. netherite ingot).
     */
    record SmithingResolution(
            ItemStack output,
            List<Item> template,
            List<Item> base,
            List<Item> addition) {}

    /**
     * Resolves a smithing transform recipe whose output {@link Item} equals {@code output}.
     *
     * <p>Matching rule: {@code recipe.getResultItem(registries).getItem() == output} (identity,
     * not registry-id equality). Only {@code SmithingTransformRecipe} instances are considered;
     * {@code SmithingTrimRecipe} is skipped before the result-item test (its
     * {@code getResultItem} produces a synthetic representative stack that would pollute matches).
     *
     * <p>Returns the first transform match. Returns {@link Optional#empty()} when no transform
     * recipe produces {@code output} — never crashes, never invents one.
     *
     * @param mgr        the live recipe manager; use {@code level.getRecipeManager()} or
     *                   {@code server.getRecipeManager()}.
     * @param output     the desired output item (e.g. {@code Items.NETHERITE_PICKAXE}).
     * @param registries server-level registry access, passed through to
     *                   {@code getResultItem} in the per-branch impl.
     * @return a {@link SmithingResolution} wrapped in {@link Optional}, or empty when no
     *         transform recipe produces {@code output}.
     */
    Optional<SmithingResolution> resolve(RecipeManager mgr, Item output, RegistryAccess registries);
}
