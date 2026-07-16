package com.player2.playerengine.tasks.farming;

import com.player2.playerengine.agentic.elliegps.FarmCropCount;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;

/** Deterministic max-age, registry, clone-metadata, and scan/action race fixtures. */
public final class CropBlockHarvestBehaviorSelfTest {
    private CropBlockHarvestBehaviorSelfTest() {
    }

    public static void runAll() {
        vanillaMaturityBoundaries();
        cropSubclassUsesItsOwnMaxAge();
        scannerEnumeratesAllSupportedCellsAndCounts();
        queuedStateSourceModelsActionRaces();
        ageOnlyAndKnownUnsupportedFamiliesAreRejected();
        plantingMetadataUsesActualContextAndRejectsInvalidClones();
        FarmPlantingBehaviorSelfTest.runAll();
        FarmPlantingOrderSelfTest.runAll();
        FarmPlantingReceiptClassifierSelfTest.runAll();
        FarmWaypointCapacitySelfTest.runAll();
    }

    private static void vanillaMaturityBoundaries() {
        assertBoundary((CropBlock) Blocks.WHEAT, "wheat");
        assertBoundary((CropBlock) Blocks.CARROTS, "carrot");
        assertBoundary((CropBlock) Blocks.BEETROOTS, "beetroot");
    }

    private static void assertBoundary(CropBlock crop, String name) {
        CropBlockHarvestBehavior behavior = new CropBlockHarvestBehavior();
        require(behavior.recognizes(crop.defaultBlockState()), name + " recognized");
        require(!behavior.isMature(crop.getStateForAge(crop.getMaxAge() - 1)),
                name + " below max age remains immature");
        require(behavior.isMature(crop.getStateForAge(crop.getMaxAge())),
                name + " exact max age is mature");
        require(behavior.action() == HarvestBehavior.HarvestAction.BREAK,
                name + " uses normal break action");
    }

    private static void cropSubclassUsesItsOwnMaxAge() {
        CropBlock crop = (CropBlock) Blocks.BEETROOTS;
        require(crop.getClass() != CropBlock.class,
                "CropBlock subclass contract is exercised");
        HarvestBehavior behavior = HarvestBehaviorRegistry.DEFAULT
                .resolve(crop.defaultBlockState())
                .orElseThrow(() -> new AssertionError("CropBlock subclass was not recognized"));
        require(crop.getMaxAge() == 3 && !behavior.isMature(crop.getStateForAge(2)),
                "subclass-specific age below its maximum remains immature");
        require(behavior.isMature(crop.getStateForAge(3)),
                "subclass-specific maximum age is honored without a namespace allowlist");
    }

    private static void scannerEnumeratesAllSupportedCellsAndCounts() {
        LevelReader level = levelReaderStub();
        BlockPos first = new BlockPos(1, 65, 1);
        BlockPos second = new BlockPos(2, 65, 1);
        BlockPos third = new BlockPos(3, 65, 1);
        BlockPos ignored = new BlockPos(4, 65, 1);
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        CropBlock carrots = (CropBlock) Blocks.CARROTS;
        QueuedStateSource source = new QueuedStateSource(
                wheat.getStateForAge(0),
                wheat.getStateForAge(wheat.getMaxAge()),
                carrots.getStateForAge(carrots.getMaxAge()),
                Blocks.STONE.defaultBlockState());

        List<FarmCropScanner.CropCell> cells = FarmCropScanner.enumerateRecognized(
                level,
                List.of(first, second, third, ignored),
                source,
                HarvestBehaviorRegistry.DEFAULT);
        require(cells.size() == 3, "scanner retains mature plus immature supported crops only");
        require(!cells.get(0).fullyGrown() && cells.get(1).fullyGrown()
                        && cells.get(2).fullyGrown(),
                "scanner records current maturity per recognized cell");
        require(cells.get(0).position().equals(first)
                        && cells.get(1).position().equals(second)
                        && cells.get(2).position().equals(third),
                "scanner preserves deterministic input order");

        List<FarmCropCount> counts = FarmCropScanner.aggregateCounts(cells);
        require(counts.size() == 2, "scanner emits one count per supported crop/planting pair");
        require(countFor(counts, "minecraft:wheat") == 2,
                "scanner count includes immature and mature wheat");
        require(countFor(counts, "minecraft:carrots") == 1,
                "scanner counts the other supported crop");
    }

    private static void queuedStateSourceModelsActionRaces() {
        LevelReader level = levelReaderStub();
        BlockPos target = new BlockPos(7, 65, 9);
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        BlockState mature = wheat.getStateForAge(wheat.getMaxAge());
        BlockState immature = wheat.getStateForAge(wheat.getMaxAge() - 1);
        QueuedStateSource immatureRace = new QueuedStateSource(mature, immature);

        List<FarmCropScanner.CropCell> scan = FarmCropScanner.enumerateRecognized(
                level, List.of(target), immatureRace, HarvestBehaviorRegistry.DEFAULT);
        require(scan.size() == 1 && scan.get(0).fullyGrown(),
                "queued source exposes a mature scan candidate");
        BlockState actionState = immatureRace.read(level, target);
        HarvestBehavior actionBehavior = HarvestBehaviorRegistry.DEFAULT.resolve(actionState)
                .orElseThrow(() -> new AssertionError("immature CropBlock lost recognition"));
        require(!actionBehavior.isMature(actionState),
                "fresh action gate detects mature-to-immature race");

        QueuedStateSource airRace = new QueuedStateSource(mature, Blocks.AIR.defaultBlockState());
        require(FarmCropScanner.enumerateRecognized(
                        level, List.of(target), airRace, HarvestBehaviorRegistry.DEFAULT)
                        .get(0).fullyGrown(),
                "air race begins with mature scan state");
        require(HarvestBehaviorRegistry.DEFAULT.resolve(airRace.read(level, target)).isEmpty(),
                "fresh action gate detects mature-to-air race");
    }

    private static void ageOnlyAndKnownUnsupportedFamiliesAreRejected() {
        HarvestBehaviorRegistry registry = HarvestBehaviorRegistry.DEFAULT;
        require(registry.resolve(Blocks.NETHER_WART.defaultBlockState()).isEmpty(),
                "AGE property alone does not opt nether wart into harvesting");
        require(registry.resolve(Blocks.MELON_STEM.defaultBlockState()).isEmpty(),
                "known unsupported stem family remains untouched");
        require(registry.resolve(Blocks.SWEET_BERRY_BUSH.defaultBlockState()).isEmpty(),
                "known unsupported berry family remains untouched");
        require(registry.resolve(Blocks.TORCHFLOWER_CROP.defaultBlockState())
                        .filter(TorchflowerHarvestBehavior.class::isInstance).isPresent(),
                "crop whose mature state transforms receives its explicit stable behavior");
    }

    private static void plantingMetadataUsesActualContextAndRejectsInvalidClones() {
        CropBlockHarvestBehavior behavior = new CropBlockHarvestBehavior();
        LevelReader level = levelReaderStub();
        BlockPos position = new BlockPos(11, 72, -4);
        BlockState state = Blocks.WHEAT.defaultBlockState();
        Object[] seen = new Object[3];

        Optional<ResourceLocation> planting = CropBlockHarvestBehavior.plantingItemIdFromClone(
                level, position, state, (actualLevel, actualPosition, actualState) -> {
                    seen[0] = actualLevel;
                    seen[1] = actualPosition;
                    seen[2] = actualState;
                    return new ItemStack(Items.WHEAT_SEEDS);
                });
        require(planting.map(ResourceLocation::toString)
                        .filter("minecraft:wheat_seeds"::equals).isPresent(),
                "registered clone item becomes optional planting metadata");
        require(seen[0] == level && position.equals(seen[1]) && seen[2] == state,
                "clone lookup receives the actual level, position, and state");

        require(CropBlockHarvestBehavior.plantingItemIdFromClone(
                        level, position, state, (l, p, s) -> ItemStack.EMPTY).isEmpty(),
                "empty clone metadata is omitted");
        require(CropBlockHarvestBehavior.plantingItemIdFromClone(
                        level, position, state, (l, p, s) -> new ItemStack(Items.AIR)).isEmpty(),
                "AIR clone metadata is omitted");
        require(CropBlockHarvestBehavior.plantingItemIdFromClone(
                        level, position, state,
                        (l, p, s) -> new ItemStack(Items.WHEAT_SEEDS),
                        item -> null).isEmpty(),
                "unregistered clone metadata is omitted");
        require(behavior.plantingItemId(level, position, Blocks.AIR.defaultBlockState()).isEmpty(),
                "AIR block state is never treated as planting metadata");
    }

    private static int countFor(List<FarmCropCount> counts, String blockId) {
        return counts.stream()
                .filter(count -> count.blockId().equals(blockId))
                .mapToInt(FarmCropCount::count)
                .sum();
    }

    private static LevelReader levelReaderStub() {
        return (LevelReader) Proxy.newProxyInstance(
                CropBlockHarvestBehaviorSelfTest.class.getClassLoader(),
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

    private static final class QueuedStateSource implements FarmActionStateSource {
        private final ArrayDeque<BlockState> states = new ArrayDeque<>();

        private QueuedStateSource(BlockState... states) {
            this.states.addAll(List.of(states));
        }

        @Override
        public BlockState read(LevelReader level, BlockPos pos) {
            BlockState state = states.pollFirst();
            if (state == null) {
                throw new AssertionError("queued state source exhausted at " + pos);
            }
            return state;
        }
    }
}
