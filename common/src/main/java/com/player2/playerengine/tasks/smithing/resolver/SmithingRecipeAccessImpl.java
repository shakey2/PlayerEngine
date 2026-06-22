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
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import org.slf4j.Logger;

/**
 * MC 1.21.1 body of {@link SmithingRecipeAccess}.
 *
 * <h3>1.21.1 version characteristics (confirmed against decompiled-sources/1.21.1)</h3>
 * <ul>
 *   <li>{@code RecipeManager.getAllRecipesFor(RecipeType.SMITHING)} returns
 *       {@code List<RecipeHolder<SmithingRecipe>>} — each recipe is wrapped in a
 *       {@link RecipeHolder} ({@code RecipeManager.java:107}; {@code RecipeType.SMITHING} is
 *       typed {@code RecipeType<SmithingRecipe>}, {@code RecipeType.java:14}). Each holder is
 *       unwrapped via {@link RecipeHolder#value()} before reaching the concrete recipe. This is
 *       the sole structural divergence from the 1.20.1 sibling, which receives an unwrapped
 *       {@code List<SmithingRecipe>}.</li>
 *   <li>{@code SmithingTransformRecipe.getResultItem(HolderLookup.Provider)} takes a
 *       {@code HolderLookup.Provider} ({@code SmithingTransformRecipe.java:36}), NOT a plain
 *       {@code RegistryAccess} as in 1.20.1. Passing a {@code RegistryAccess} satisfies the
 *       1.21.1 parameter because {@code RegistryAccess extends HolderLookup.Provider}; the call
 *       site is unchanged in source text but binds to the 1.21.1 signature here.</li>
 *   <li>{@code Ingredient.getItems()} returns {@code ItemStack[]}
 *       ({@code Ingredient.java:96-105}) — same accessor name and return type as 1.20.1, so the
 *       accepted-item enumeration ({@link #ingredientToItems}) is identical to the 1.20.1 sibling.</li>
 *   <li>The {@code template}, {@code base}, {@code addition} fields on
 *       {@code SmithingTransformRecipe} are package-private ({@code SmithingTransformRecipe.java:13-15})
 *       — not directly accessible from this package. They are accessed here via reflection with
 *       {@code setAccessible(true)}. The fields are resolved by <strong>type + declaration order</strong>
 *       (the three {@code Ingredient}-typed fields, in order: template, base, addition), NOT by name:
 *       at runtime the Fabric 1.21.1 environment remaps these package-private field names to
 *       intermediary names, so {@code getDeclaredField("template")} throws {@code NoSuchFieldException}.
 *       Selecting by type + order survives that remapping (the remapper preserves field types and
 *       declaration order).</li>
 * </ul>
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
 * <h3>Why reflection (and not the public {@code is*Ingredient} predicates)</h3>
 * The resolver needs the <em>enumerated accepted items</em> ({@code List<Item>}) for each of the
 * three roles, not a boolean test. The 1.21.1 public surface does NOT expose that:
 * <ul>
 *   <li>{@code SmithingRecipe.isTemplateIngredient/isBaseIngredient/isAdditionIngredient(ItemStack)}
 *       are test-only predicates (each delegates to {@code Ingredient.test(stack)},
 *       {@code SmithingTransformRecipe.java:40-53}) — they cannot enumerate the accepted items, and
 *       there is no candidate-stack source to feed them.</li>
 *   <li>{@code Recipe.getIngredients()} is a {@code default} returning an empty
 *       {@code NonNullList.create()} ({@code Recipe.java:48-50}) and {@code SmithingTransformRecipe}
 *       does NOT override it — so it yields zero stacks for transform recipes and cannot be used to
 *       reconstruct the role lists.</li>
 * </ul>
 * Reflection over the three package-private {@code Ingredient} fields is therefore the only
 * mechanism that produces the per-role {@code List<Item>} this resolver returns. Do not "simplify"
 * this to the {@code is*Ingredient} predicates — that path cannot build the enumeration.
 *
 * <p>The 1.20.1 sibling receives the recipe list unwrapped (no {@code RecipeHolder}) and calls
 * {@code getResultItem(RegistryAccess)} — both divergences stay in the per-branch impl.
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
            // decompiled-sources/1.21.1 (SmithingTransformRecipe.java:13-15), but at runtime the
            // Fabric 1.21.1 environment remaps these package-private fields to intermediary names
            // (e.g. field_xxxxx). getDeclaredField("template") therefore throws
            // NoSuchFieldException against the running JVM (observed at startup). Enumerating
            // getDeclaredFields() and selecting the Ingredient-typed fields in declaration order is
            // immune to that remapping: the remapper preserves field types and declaration order.
            //
            // SmithingTransformRecipe declares exactly four instance fields, in order
            // (SmithingTransformRecipe.java:13-16):
            //   [0] Ingredient template   [1] Ingredient base   [2] Ingredient addition   [3] ItemStack result
            // so the Ingredient-typed fields in declaration order are template, base, addition.
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
     * {@code List<RecipeHolder<SmithingRecipe>>} in 1.21.1 — unwrapped per holder via
     * {@code holder.value()}), skips non-transform recipes via
     * {@code instanceof SmithingTransformRecipe}, matches by
     * {@code getResultItem(registries).getItem() == output}, and returns the first
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

        // 1.21.1: getAllRecipesFor(RecipeType<SmithingRecipe>) returns
        // List<RecipeHolder<SmithingRecipe>> (RecipeManager.java:107). Unwrap each holder.
        List<RecipeHolder<SmithingRecipe>> recipes = mgr.getAllRecipesFor(RecipeType.SMITHING);
        for (RecipeHolder<SmithingRecipe> holder : recipes) {
            SmithingRecipe recipe = holder.value();
            // Skip SmithingTrimRecipe and any future non-transform subtype.
            if (!(recipe instanceof SmithingTransformRecipe transform)) {
                continue;
            }

            // Match by Item identity (non-negotiable #3).
            // getResultItem(HolderLookup.Provider) — 1.21.1 signature (SmithingTransformRecipe.java:36);
            // RegistryAccess satisfies it (RegistryAccess extends HolderLookup.Provider).
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
     * <p>In 1.21.1 {@code Ingredient.getItems()} returns {@code ItemStack[]}
     * (confirmed {@code Ingredient.java:96-105}) — same accessor and return type as 1.20.1, so
     * this enumeration is identical to the 1.20.1 sibling. This call is intentionally inside the
     * per-branch impl.
     */
    private static List<Item> ingredientToItems(Ingredient ingredient) {
        if (ingredient == null) {
            return List.of();
        }
        // 1.21.1: Ingredient.getItems() returns ItemStack[] (Ingredient.java:96-105).
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
