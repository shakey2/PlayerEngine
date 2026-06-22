package com.player2.playerengine.tasks.smithing.resolver;

import com.mojang.logging.LogUtils;
import com.player2.playerengine.tasks.smithing.resolver.SmithingRecipeAccess.SmithingResolution;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import org.slf4j.Logger;

/**
 * MC 1.20.1 body of {@link SmithingRecipeAccess}.
 *
 * <h3>1.20.1 version characteristics (confirmed against decompiled-sources/1.20.1)</h3>
 * <ul>
 *   <li>{@code RecipeManager.getAllRecipesFor(RecipeType.SMITHING)} returns
 *       {@code List<SmithingRecipe>} directly — no {@code RecipeHolder} wrapper
 *       ({@code RecipeManager.java:116}).</li>
 *   <li>{@code SmithingTransformRecipe.getResultItem(RegistryAccess)} takes a plain
 *       {@code RegistryAccess} ({@code SmithingTransformRecipe.java:47}).</li>
 *   <li>{@code Ingredient.getItems()} returns {@code ItemStack[]}
 *       ({@code Ingredient.java:57-60}).</li>
 *   <li>The {@code template}, {@code base}, {@code addition} fields on
 *       {@code SmithingTransformRecipe} are package-private ({@code SmithingTransformRecipe.java:17-19})
 *       — not directly accessible from this package. They are accessed here via reflection with
 *       {@code setAccessible(true)}. The fields are resolved by <strong>type + declaration order</strong>
 *       (the three {@code Ingredient}-typed fields, in order: template, base, addition), NOT by name:
 *       at runtime the Fabric 1.20.1 environment remaps these package-private field names to
 *       intermediary names, so {@code getDeclaredField("template")} throws {@code NoSuchFieldException}.
 *       Selecting by type + order survives that remapping (the remapper preserves field types and
 *       declaration order).</li>
 * </ul>
 *
 * <h3>Loader-immune field reflection (shared approach across branches)</h3>
 * The field-reflection strategy is <strong>loader-immune</strong> and identical in approach to the
 * 1.21.1 sibling: it selects the {@code Ingredient}-typed fields by type and declaration order
 * rather than by mojmap name. Name-based reflection works on a mojmap runtime (Forge 1.20.1,
 * NeoForge 1.21.1) but FAILS on a Fabric runtime (any MC version), which remaps package-private
 * field names to intermediary. Because this branch builds and ships a Fabric 1.20.1 jar, the
 * type+order strategy is required for smithing resolution to work there. The remapper preserves
 * field types and declaration order, so the approach works on Forge AND Fabric. The only intended
 * divergence from the 1.21.1 sibling is the recipe-list API (see below), NOT this reflection block.
 *
 * <h3>SmithingTrimRecipe exclusion</h3>
 * The {@code instanceof SmithingTransformRecipe} guard skips all trim recipes before the
 * result-item test. {@code SmithingTrimRecipe.getResultItem} produces a synthetic
 * representative stack (an IRON_CHESTPLATE with trim data) that must never participate in
 * a result-item identity match (non-negotiable #3 from the plan).
 *
 * <h3>Ingredient extraction</h3>
 * {@code SmithingTransformRecipe} stores three package-private {@link Ingredient} fields:
 * {@code template}, {@code base}, {@code addition}. Because {@code Ingredient.getItems()}
 * is the standard way to enumerate an ingredient's accepted item stacks and there is no
 * public API on the recipe to retrieve these ingredients by role, they are accessed via
 * reflection. Each resulting {@code ItemStack[]} is mapped to its {@code Item}, AIR /
 * empty stacks are filtered, duplicates are removed, and the items are placed in the
 * corresponding role list.
 *
 * <p>The 1.21.1 sibling must unwrap {@code RecipeHolder<SmithingRecipe>.value()} and use
 * the Holder-based accepted-item enumeration — both divergences stay in the per-branch impl.
 *
 * <p>Stateless and thread-safe — safe to use as a {@code private static final} singleton.
 */
public final class SmithingRecipeAccessImpl implements SmithingRecipeAccess {

    private static final Logger LOGGER = LogUtils.getLogger();

    // -------------------------------------------------------------------------
    // Reflection handles — resolved once, reused every call.
    // -------------------------------------------------------------------------

    private static final Field FIELD_TEMPLATE;
    private static final Field FIELD_BASE;
    private static final Field FIELD_ADDITION;
    private static final boolean REFLECTION_OK;

    static {
        Field ft = null, fb = null, fa = null;
        boolean ok = false;
        try {
            // Resolve the three role ingredients by TYPE + declaration order, NOT by name.
            //
            // The mojmap names (template, base, addition) are correct at dev time and in
            // decompiled-sources/1.20.1 (SmithingTransformRecipe.java:17-19), but at runtime the
            // Fabric 1.20.1 environment remaps these package-private fields to intermediary names
            // (e.g. field_xxxxx). getDeclaredField("template") therefore throws
            // NoSuchFieldException against the running Fabric JVM. (Forge 1.20.1 runs mojmap, so
            // name-based lookup would work there — but this branch also builds and ships a Fabric
            // jar, so the strategy must be loader-immune.) Enumerating getDeclaredFields() and
            // selecting the Ingredient-typed fields in declaration order is immune to that
            // remapping: the remapper preserves field types and declaration order.
            //
            // SmithingTransformRecipe declares exactly five instance fields, in order
            // (SmithingTransformRecipe.java:16-20):
            //   [0] ResourceLocation id   [1] Ingredient template   [2] Ingredient base
            //   [3] Ingredient addition   [4] ItemStack result
            // The non-Ingredient id/result fields are skipped by the type filter, so the
            // Ingredient-typed fields in declaration order are template, base, addition.
            List<Field> ingredientFields = new ArrayList<>(3);
            for (Field f : SmithingTransformRecipe.class.getDeclaredFields()) {
                if (f.getType() == Ingredient.class) {
                    ingredientFields.add(f);
                }
            }
            if (ingredientFields.size() != 3) {
                throw new NoSuchFieldException(
                        "expected exactly 3 Ingredient fields on SmithingTransformRecipe, found "
                                + ingredientFields.size());
            }
            ft = ingredientFields.get(0);
            fb = ingredientFields.get(1);
            fa = ingredientFields.get(2);
            ft.setAccessible(true);
            fb.setAccessible(true);
            fa.setAccessible(true);
            ok = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Reflection failed — resolve() will always return Optional.empty().
            // Log at WARN so operators see the failure immediately; do not suppress it silently.
            // ReflectiveOperationException covers NoSuchFieldException / IllegalAccessException.
            // RuntimeException covers InaccessibleObjectException (Java 16+ module enforcement).
            LOGGER.warn("[PlayerEngine] SmithingRecipeAccessImpl: reflection setup failed — "
                    + "smithing resolution will return empty for every query. "
                    + "Check JVM --add-opens for net.minecraft.world.item.crafting. Error: {}", e.toString());
        }
        FIELD_TEMPLATE = ft;
        FIELD_BASE = fb;
        FIELD_ADDITION = fa;
        REFLECTION_OK = ok;
    }

    /**
     * Resolves a smithing transform recipe for the given output {@link Item}.
     *
     * <p>Iterates {@code mgr.getAllRecipesFor(RecipeType.SMITHING)} (returns
     * {@code List<SmithingRecipe>} in 1.20.1 — no unwrap needed), skips non-transform
     * recipes via {@code instanceof SmithingTransformRecipe}, matches by
     * {@code getResultItem(RegistryAccess).getItem() == output}, and returns the first
     * match. Returns {@link Optional#empty()} on no match or if reflection is unavailable.
     */
    @Override
    public Optional<SmithingResolution> resolve(
            RecipeManager mgr, Item output, RegistryAccess registries) {

        if (!REFLECTION_OK) {
            // Reflection setup failed at class-load time; log once so the failure is visible
            // (the static initialiser already logged the root cause at WARN; this line keeps
            // every call from spam-logging by relying on the class-init warning being sufficient).
            // Return empty — the caller's NO_RECIPE path gives the player a truthful message.
            return Optional.empty();
        }

        // 1.20.1: getAllRecipesFor returns List<SmithingRecipe> directly (RecipeManager.java:116).
        List<SmithingRecipe> recipes = mgr.getAllRecipesFor(RecipeType.SMITHING);
        for (SmithingRecipe recipe : recipes) {
            // Skip SmithingTrimRecipe and any future non-transform subtype.
            if (!(recipe instanceof SmithingTransformRecipe transform)) {
                continue;
            }

            // Match by Item identity (non-negotiable #3).
            // getResultItem(RegistryAccess) — 1.20.1 signature (SmithingTransformRecipe.java:47).
            // Call is intentionally inside this per-branch impl.
            ItemStack result = transform.getResultItem(registries);
            if (result.isEmpty() || result.getItem() != output) {
                continue;
            }

            // Extract the three role ingredients via reflection.
            Optional<SmithingResolution> resolution = extractResolution(transform, result);
            if (resolution.isPresent()) {
                return resolution;
            }
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Extracts {@code template}, {@code base}, {@code addition} from the recipe via reflection,
     * maps each {@link Ingredient} to a {@code List<Item>} (non-empty, deduped, no AIR),
     * and builds the {@link SmithingResolution}.
     *
     * <p>Returns {@link Optional#empty()} if reflection access fails for any field.
     */
    private static Optional<SmithingResolution> extractResolution(
            SmithingTransformRecipe transform, ItemStack result) {
        try {
            Ingredient templateIngredient = (Ingredient) FIELD_TEMPLATE.get(transform);
            Ingredient baseIngredient     = (Ingredient) FIELD_BASE.get(transform);
            Ingredient additionIngredient = (Ingredient) FIELD_ADDITION.get(transform);

            List<Item> templateItems = ingredientToItems(templateIngredient);
            List<Item> baseItems     = ingredientToItems(baseIngredient);
            List<Item> additionItems = ingredientToItems(additionIngredient);

            // Each role list must be non-empty for a well-formed transform recipe.
            if (templateItems.isEmpty() || baseItems.isEmpty() || additionItems.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(new SmithingResolution(result.copy(), templateItems, baseItems, additionItems));
        } catch (IllegalAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Maps an {@link Ingredient} to a deduplicated {@code List<Item>} of its accepted items,
     * filtering out AIR and empty stacks.
     *
     * <p>In 1.20.1 {@code Ingredient.getItems()} returns {@code ItemStack[]}
     * (confirmed {@code Ingredient.java:57-60}). This call is intentionally inside the
     * per-branch impl; the 1.21.1 sibling uses Holder-based enumeration instead.
     */
    private static List<Item> ingredientToItems(Ingredient ingredient) {
        if (ingredient == null) {
            return List.of();
        }
        // 1.20.1: Ingredient.getItems() returns ItemStack[] (Ingredient.java:57-60).
        ItemStack[] stacks = ingredient.getItems();
        List<Item> items = new ArrayList<>(stacks.length);
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            Item item = stack.getItem();
            if (item == Items.AIR) {
                continue;
            }
            if (!items.contains(item)) {
                items.add(item);
            }
        }
        return List.copyOf(items);
    }
}
