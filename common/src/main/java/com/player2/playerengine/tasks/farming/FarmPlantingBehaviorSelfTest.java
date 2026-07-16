package com.player2.playerengine.tasks.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.lang.reflect.Proxy;
import java.util.List;

/** Deterministic behavior, resolver, stable-identity, and crop-stance contract checks. */
public final class FarmPlantingBehaviorSelfTest {
    private FarmPlantingBehaviorSelfTest() {
    }

    public static void runAll() {
        registryOrdersSpecificFamiliesBeforeGeneric();
        torchflowerIdentitySurvivesItsMatureBlockTransition();
        pitcherRecognizesBothHalvesAndExactMaturity();
        resolverSupportsSeedsRootsAndSpecialFamilies();
        resolverRejectsStemsAndNonPlantingItems();
        lowCollisionExceptionIsNarrowAndHazardSafe();
    }

    private static void registryOrdersSpecificFamiliesBeforeGeneric() {
        List<HarvestBehavior> behaviors = HarvestBehaviorRegistry.DEFAULT.behaviors();
        require(behaviors.size() == 3
                        && behaviors.get(0) instanceof TorchflowerHarvestBehavior
                        && behaviors.get(1) instanceof PitcherCropHarvestBehavior
                        && behaviors.get(2) instanceof CropBlockHarvestBehavior,
                "specific transformed crop families precede the generic CropBlock behavior");
        require(new CropBlockHarvestBehavior().canonicalCropId(Blocks.WHEAT.defaultBlockState())
                        .map(ResourceLocation::toString)
                        .filter("minecraft:wheat"::equals).isPresent(),
                "ordinary CropBlock canonical identity is its stable registered block id");
    }

    private static void torchflowerIdentitySurvivesItsMatureBlockTransition() {
        HarvestBehavior behavior = HarvestBehaviorRegistry.DEFAULT
                .resolve(Blocks.TORCHFLOWER_CROP.defaultBlockState())
                .orElseThrow(() -> new AssertionError("torchflower crop was not recognized"));
        require(behavior instanceof TorchflowerHarvestBehavior,
                "torchflower resolves to its explicit behavior");
        require(!behavior.isMature(Blocks.TORCHFLOWER_CROP.defaultBlockState())
                        && behavior.isMature(Blocks.TORCHFLOWER.defaultBlockState()),
                "only the transformed torchflower block is mature");
        require(behavior.canonicalCropId(Blocks.TORCHFLOWER_CROP.defaultBlockState())
                        .equals(behavior.canonicalCropId(Blocks.TORCHFLOWER.defaultBlockState()))
                        && behavior.canonicalCropId(Blocks.TORCHFLOWER.defaultBlockState())
                        .map(ResourceLocation::toString)
                        .filter("minecraft:torchflower_crop"::equals).isPresent(),
                "immature and mature torchflowers retain one canonical crop identity");
        require(behavior.plantingItemId(
                        levelReaderStub(), BlockPos.ZERO, Blocks.TORCHFLOWER.defaultBlockState())
                        .map(ResourceLocation::toString)
                        .filter("minecraft:torchflower_seeds"::equals).isPresent(),
                "mature torchflower metadata points back to seeds rather than the flower item");
    }

    private static void pitcherRecognizesBothHalvesAndExactMaturity() {
        BlockState lowerThree = pitcher(3, DoubleBlockHalf.LOWER);
        BlockState upperThree = pitcher(3, DoubleBlockHalf.UPPER);
        BlockState lowerFour = pitcher(4, DoubleBlockHalf.LOWER);
        BlockState upperFour = pitcher(4, DoubleBlockHalf.UPPER);
        HarvestBehavior behavior = HarvestBehaviorRegistry.DEFAULT.resolve(lowerThree)
                .orElseThrow(() -> new AssertionError("pitcher lower was not recognized"));
        require(behavior instanceof PitcherCropHarvestBehavior
                        && HarvestBehaviorRegistry.DEFAULT.resolve(upperThree).orElse(null) == behavior,
                "both pitcher halves resolve to the explicit family behavior");
        require(!behavior.isMature(lowerThree) && !behavior.isMature(upperThree)
                        && behavior.isMature(lowerFour) && behavior.isMature(upperFour),
                "pitcher maturity is exact at age four for both preserved halves");
        require(behavior.canonicalCropId(upperFour)
                        .map(ResourceLocation::toString)
                        .filter("minecraft:pitcher_crop"::equals).isPresent()
                        && behavior.plantingItemId(levelReaderStub(), BlockPos.ZERO, upperFour)
                        .map(ResourceLocation::toString)
                        .filter("minecraft:pitcher_pod"::equals).isPresent(),
                "pitcher upper half retains lower-family and pod metadata");
        require(behavior.allowsLowCollisionStance(lowerFour)
                        && !behavior.allowsLowCollisionStance(upperFour),
                "only the known low-collision pitcher lower half opts into step traversal");
    }

    private static void resolverSupportsSeedsRootsAndSpecialFamilies() {
        assertSupported(Items.WHEAT_SEEDS, "minecraft:wheat");
        assertSupported(Items.BEETROOT_SEEDS, "minecraft:beetroots");
        assertSupported(Items.CARROT, "minecraft:carrots");
        assertSupported(Items.POTATO, "minecraft:potatoes");
        assertSupported(Items.TORCHFLOWER_SEEDS, "minecraft:torchflower_crop");
        assertSupported(Items.PITCHER_POD, "minecraft:pitcher_crop");
    }

    private static void resolverRejectsStemsAndNonPlantingItems() {
        require(FarmPlantingItemResolver.resolve(Items.PUMPKIN_SEEDS).status()
                        == FarmPlantingItemResolver.Status.EXCLUDED_STEM
                        && FarmPlantingItemResolver.resolve(Items.MELON_SEEDS).status()
                        == FarmPlantingItemResolver.Status.EXCLUDED_STEM,
                "horizontal fruit stems are explicitly excluded by placement-block class");
        require(FarmPlantingItemResolver.resolve(Items.WHEAT).status()
                        == FarmPlantingItemResolver.Status.NOT_BLOCK_ITEM,
                "ordinary produce that cannot place a crop is not guessed from its name");
        require(FarmPlantingItemResolver.resolve(Items.TORCHFLOWER).status()
                        == FarmPlantingItemResolver.Status.UNSUPPORTED_FAMILY
                        && FarmPlantingItemResolver.resolve(Items.PITCHER_PLANT).status()
                        == FarmPlantingItemResolver.Status.UNSUPPORTED_FAMILY,
                "mature decorative items are not confused with their planting items");
        require(FarmPlantingItemResolver.resolve(Items.AIR).status()
                        == FarmPlantingItemResolver.Status.INVALID_ITEM,
                "AIR sentinel cannot become a planting request");
    }

    private static void lowCollisionExceptionIsNarrowAndHazardSafe() {
        require(FarmStanceNavigation.cropStanceGeometryAllowed(
                        true, false, true, 0.0D, true, false, 0.0F),
                "ordinary collision-empty recognized crops remain passable");
        require(FarmStanceNavigation.cropStanceGeometryAllowed(
                        true, true, false, 0.3125D, true, false, 0.0F),
                "explicit low pitcher collision remains step-safe");
        require(!FarmStanceNavigation.cropStanceGeometryAllowed(
                        true, false, false, 0.3125D, true, false, 0.0F)
                        && !FarmStanceNavigation.cropStanceGeometryAllowed(
                        true, true, false, 1.0D, true, false, 0.0F),
                "arbitrary colliding crops and tall collision shapes remain denied");
        require(!FarmStanceNavigation.cropStanceGeometryAllowed(
                        true, true, false, 0.3125D, false, false, 0.0F)
                        && !FarmStanceNavigation.cropStanceGeometryAllowed(
                        true, true, false, 0.3125D, true, true, 0.0F)
                        && !FarmStanceNavigation.cropStanceGeometryAllowed(
                        true, true, false, 0.3125D, true, false, -1.0F),
                "fluid, block-entity, and unbreakable hazards override crop recognition");
    }

    private static BlockState pitcher(int age, DoubleBlockHalf half) {
        return Blocks.PITCHER_CROP.defaultBlockState()
                .setValue(PitcherCropBlock.AGE, age)
                .setValue(DoublePlantBlock.HALF, half);
    }

    private static void assertSupported(net.minecraft.world.item.Item item, String cropId) {
        FarmPlantingItemResolver.Result result = FarmPlantingItemResolver.resolve(item);
        require(result.supported() && result.descriptor() != null
                        && result.descriptor().item() == item
                        && result.descriptor().canonicalCropId().toString().equals(cropId),
                item + " resolves to canonical crop " + cropId);
    }

    private static LevelReader levelReaderStub() {
        return (LevelReader) Proxy.newProxyInstance(
                FarmPlantingBehaviorSelfTest.class.getClassLoader(),
                new Class<?>[]{LevelReader.class},
                (proxy, method, arguments) -> defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        throw new AssertionError("unexpected primitive type " + type);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
