package com.player2.playerengine.tasks.cooking.resolver;

import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;

/**
 * Cross-version access to the Minecraft cooking recipe set (smelting / blasting / smoking),
 * narrowed so deterministic resolver code stays byte-identical across the 1.20.1 and 1.21.1
 * PlayerEngine branches.
 *
 * <p>The concrete implementation ({@code CookingRecipeAccessImpl}) absorbs BOTH version
 * divergences so they never reach callers:
 * <ol>
 *   <li>the {@code RecipeHolder<SmeltingRecipe>} unwrap (1.21.1 only: {@code .value()}); and</li>
 *   <li>the {@code getResultItem} signature difference — 1.20.1 takes {@code RegistryAccess},
 *       1.21.1 takes {@code HolderLookup.Provider}. Passing a {@code RegistryAccess} satisfies
 *       both (confirmed {@code RegistryAccess extends HolderLookup.Provider} in both versions),
 *       but the override signature is genuinely different, so the call lives only in the
 *       per-branch impl.</li>
 * </ol>
 *
 * <p>Only common MC types ({@link Item}, {@link ItemStack}, {@link RegistryAccess}) cross this
 * interface. {@code RecipeHolder}, {@code SingleRecipeInput}, {@code HolderLookup.Provider},
 * and {@code AbstractCookingRecipe.getId()} are NEVER surfaced here — the id exists only on
 * 1.20.1's recipe; on 1.21.1 it lives on the {@code RecipeHolder.id()} wrapper.
 *
 * <p>Do NOT call {@code getResultItem(...)} from common code; it lives exclusively inside
 * {@code CookingRecipeAccessImpl} to absorb the version-specific signature.
 *
 * <p>This type is part of the deterministic resolver package: it performs no Player2 / AiTask /
 * Joules calls and only reads the Minecraft recipe system.
 *
 * @see CookResolution
 * @see CookKind
 */
public interface CookingRecipeAccess {

    /**
     * The furnace family: regular smelting ({@code SMELTING}), blast furnace ({@code BLASTING}),
     * and smoker ({@code SMOKING}). Maps 1-to-1 to {@code RecipeType.SMELTING},
     * {@code RecipeType.BLASTING}, {@code RecipeType.SMOKING}.
     */
    enum CookKind {
        SMELTING,
        BLASTING,
        SMOKING
    }

    /**
     * The result of resolving a cooking recipe for a given input.
     *
     * @param output    the single-item output stack (count is the per-smelt yield, typically 1).
     * @param cookTicks per-item cook duration in game ticks (200 for smelting, 100 for
     *                  blasting/smoking in vanilla; always the real recipe value — NOT the
     *                  normalised {@code /200} shortcut used by {@code ItemHelper.getFuelAmount}).
     * @param kind      which furnace family this recipe belongs to.
     */
    record CookResolution(ItemStack output, int cookTicks, CookKind kind) {}

    /**
     * Resolves a cooking recipe for {@code input} under the given {@code kind} (smelting /
     * blasting / smoking). Returns {@link Optional#empty()} when no matching recipe exists —
     * never crashes, never invents an output.
     *
     * <p>Matching is done by testing the recipe's first ingredient against a single-item
     * {@code ItemStack(input)} (the standard furnace-family ingredient test). Only the FIRST
     * matching recipe is returned; vanilla items never have more than one per kind per input.
     *
     * <p>The {@link RecipeManager} parameter mirrors the pattern used by {@link
     * com.player2.playerengine.tasks.crafting.resolver.RecipeAccess} — callers always hold a
     * live manager from {@code level.getRecipeManager()} or {@code server.getRecipeManager()}.
     *
     * @param mgr        the live recipe manager; use {@code level.getRecipeManager()} or
     *                   {@code server.getRecipeManager()}.
     * @param input      the raw-material item to smelt (e.g. {@code Items.RAW_IRON}).
     * @param kind       the furnace family to query.
     * @param registries the server-level registry access (passed through to
     *                   {@code getResultItem(RegistryAccess)} in the per-branch impl).
     * @return the resolved output + cook ticks wrapped in {@code Optional}, or
     *         {@link Optional#empty()} if no recipe matches.
     */
    Optional<CookResolution> resolve(RecipeManager mgr, Item input, CookKind kind, RegistryAccess registries);

    /**
     * Convenience variant: tries {@code SMELTING}, then {@code BLASTING}, then {@code SMOKING}
     * in that order, returning the first match. Returns {@link Optional#empty()} if the item
     * has no cooking recipe in any of the three kinds.
     *
     * <p>Use this when the caller wants "any furnace family" and does not need to constrain the
     * type. When the caller has a preferred kind (e.g. an agentic step arg {@code kind=blasting}),
     * call {@link #resolve(RecipeManager, Item, CookKind, RegistryAccess)} directly.
     *
     * @param mgr        the live recipe manager.
     * @param input      the raw-material item.
     * @param registries the server-level registry access.
     * @return first matching {@link CookResolution}, or {@link Optional#empty()}.
     */
    default Optional<CookResolution> resolveAny(RecipeManager mgr, Item input, RegistryAccess registries) {
        Optional<CookResolution> smelting = resolve(mgr, input, CookKind.SMELTING, registries);
        if (smelting.isPresent()) return smelting;
        Optional<CookResolution> blasting = resolve(mgr, input, CookKind.BLASTING, registries);
        if (blasting.isPresent()) return blasting;
        return resolve(mgr, input, CookKind.SMOKING, registries);
    }

    /**
     * True iff at least one {@code RecipeType.SMELTING} recipe produces {@code output} as its result.
     * The deterministic, loader-neutral "is this a furnace-smeltable ingot?" predicate: yes for
     * raw_iron-&gt;iron_ingot, raw_copper-&gt;copper_ingot, ancient_debris-&gt;netherite_scrap, etc.
     *
     * <p>SMELTING only (not {@code BLASTING}/{@code SMOKING}): the predicate means "obtainable by
     * smelting a raw material", which is exactly the sourcing the resolver should prefer over a
     * nugget/block compression craft. Returns false for any item with no smelting recipe.
     *
     * <p>This is a reverse (output -&gt; is-smelt-result) query, distinct from {@link #resolve}'s
     * forward (input -&gt; output) match. Like {@link #resolve}, the per-branch impl absorbs the
     * {@code RecipeHolder} unwrap and the {@code getResultItem} signature; it never surfaces them to
     * common callers. Deterministic — a pure recipe-system read, no Player2/AiTask/Joules calls.
     *
     * @param mgr        the live recipe manager ({@code level.getRecipeManager()} or
     *                   {@code server.getRecipeManager()}).
     * @param output     the item to test as a smelting result (e.g. {@code Items.IRON_INGOT}).
     * @param registries server-level registry access (passed through to {@code getResultItem(...)}
     *                   in the per-branch impl).
     * @return {@code true} iff some SMELTING recipe's result item equals {@code output}.
     */
    boolean isSmeltingResult(RecipeManager mgr, Item output, RegistryAccess registries);
}
