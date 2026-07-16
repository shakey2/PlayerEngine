package com.player2.playerengine.tasks.farming;

/** Pure one-cell planting receipt matrix. */
public final class FarmPlantingReceiptClassifierSelfTest {
    private static final String WHEAT = "minecraft:wheat";

    private FarmPlantingReceiptClassifierSelfTest() {
    }

    public static void runAll() {
        pairedReceiptSucceeds();
        unchangedReceiptRemainsPending();
        survivalFoodConsumptionIsReconciledExactly();
        splitWrongAndDriftedReceiptsAreInvalid();
    }

    private static void pairedReceiptSucceeds() {
        require(classify(true, false, true, WHEAT, 7, 6)
                        == FarmPlantingReceiptClassifier.Disposition.SUCCESS,
                "expected canonical crop plus exactly one consumed item succeeds");
    }

    private static void unchangedReceiptRemainsPending() {
        require(FarmPlantingReceiptClassifier.classify(
                        0, false, false, false, null, null, 0, 0)
                        == FarmPlantingReceiptClassifier.Disposition.PENDING,
                "no issued click has no receipt to settle");
        require(classify(true, true, true, null, 7, 7)
                        == FarmPlantingReceiptClassifier.Disposition.PENDING,
                "unchanged target, support, and inventory remain retryable");
    }

    private static void survivalFoodConsumptionIsReconciledExactly() {
        require(classify(true, true, true, null, 1, 7, 6)
                        == FarmPlantingReceiptClassifier.Disposition.PENDING,
                "a confirmed food-only delta leaves the planting receipt pending for reacquisition");
        require(classify(true, false, true, WHEAT, 1, 7, 5)
                        == FarmPlantingReceiptClassifier.Disposition.SUCCESS,
                "paired planting plus confirmed food deltas still prove exactly one planted item");
        require(classify(true, true, true, null, 0, 7, 6)
                        == FarmPlantingReceiptClassifier.Disposition.INVALID,
                "an untracked item-only delta remains invalid");
        require(classify(true, true, true, null, 1, 7, 7)
                        == FarmPlantingReceiptClassifier.Disposition.INVALID,
                "survival accounting cannot credit consumption absent from inventory");
    }

    private static void splitWrongAndDriftedReceiptsAreInvalid() {
        require(classify(true, true, true, null, 7, 6)
                        == FarmPlantingReceiptClassifier.Disposition.INVALID,
                "item-only receipt is invalid");
        require(classify(true, false, true, WHEAT, 7, 7)
                        == FarmPlantingReceiptClassifier.Disposition.INVALID,
                "block-only receipt is invalid");
        require(classify(true, false, true, "minecraft:carrots", 7, 6)
                        == FarmPlantingReceiptClassifier.Disposition.INVALID,
                "wrong canonical family is invalid");
        require(classify(true, false, false, WHEAT, 7, 6)
                        == FarmPlantingReceiptClassifier.Disposition.INVALID,
                "support drift invalidates an otherwise paired receipt");
        require(FarmPlantingReceiptClassifier.classify(
                        1, false, true, true, WHEAT, null, 7, 7)
                        == FarmPlantingReceiptClassifier.Disposition.INVALID,
                "missing baseline cannot be retried optimistically");
    }

    private static FarmPlantingReceiptClassifier.Disposition classify(
            boolean baseline,
            boolean unchangedTarget,
            boolean farmlandSupport,
            String observedCrop,
            int before,
            int after) {
        return classify(baseline, unchangedTarget, farmlandSupport, observedCrop, 0, before, after);
    }

    private static FarmPlantingReceiptClassifier.Disposition classify(
            boolean baseline,
            boolean unchangedTarget,
            boolean farmlandSupport,
            String observedCrop,
            int confirmedSurvivalConsumptions,
            int before,
            int after) {
        return FarmPlantingReceiptClassifier.classify(
                1, baseline, unchangedTarget, farmlandSupport,
                WHEAT, observedCrop, confirmedSurvivalConsumptions, before, after);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
