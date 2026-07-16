package com.player2.playerengine.agentic.elliegps;

import com.player2.playerengine.executor.TaskStepExecutorAdapterSelfTest;
import com.player2.playerengine.commands.BlockScannerWindowSelfTest;
import com.player2.playerengine.commands.base.GotoTargetSelfTest;
import com.player2.playerengine.commands.SetupFarmCommandSelfTest;
import com.player2.playerengine.tasks.base.TransientTaskResumeSelfTest;
import com.player2.playerengine.tasks.agentic.MarkedChestItemLocatorSelfTest;
import com.player2.playerengine.tasks.container.BoundedContainerTransferTaskSelfTest;
import com.player2.playerengine.chains.UserTaskChainSelfTest;
import com.player2.playerengine.chains.FoodChainRecoverySelfTest;
import com.player2.playerengine.automaton.behavior.LookBehaviorSelfTest;
import com.player2.playerengine.agentic.steps.SetupFarmStepFactorySelfTest;
import com.player2.playerengine.agentic.steps.HarvestFarmStepFactorySelfTest;
import com.player2.playerengine.agentic.steps.PlantFarmStepFactorySelfTest;
import com.player2.playerengine.agentic.AgenticFarmPlanContractSelfTest;
import com.player2.playerengine.tasks.farming.FarmGeometrySelfTest;
import com.player2.playerengine.tasks.farming.FarmPlotRepairPlanSelfTest;
import com.player2.playerengine.tasks.farming.FarmRepairToolPlanSelfTest;
import com.player2.playerengine.tasks.farming.FarmSetupContractSelfTest;
import com.player2.playerengine.tasks.farming.CropBlockHarvestBehaviorSelfTest;
import com.player2.playerengine.tasks.farming.HarvestFarmTaskSelfTest;
import com.player2.playerengine.tasks.farming.FarmFeedbackSelfTest;
import com.player2.playerengine.tasks.farming.FarmPlantingRequestParserSelfTest;
import com.player2.playerengine.tasks.farming.FarmPlantingBehaviorSelfTest;
import com.player2.playerengine.tasks.farming.FarmPlantingItemAcquisitionTaskSelfTest;
import com.player2.playerengine.tasks.farming.FarmPlantingOrderSelfTest;
import com.player2.playerengine.tasks.farming.FarmPlantingReceiptClassifierSelfTest;
import com.player2.playerengine.tasks.farming.FarmPlantingSelectorSelfTest;
import com.player2.playerengine.tasks.farming.PlantFarmFeedbackSelfTest;
import com.player2.playerengine.tasks.farming.PlantFarmTaskSelfTest;
import com.player2.playerengine.player2api.PassiveConversationInfoSelfTest;
import com.player2.playerengine.player2api.ConversationControlSelfTest;
import com.player2.playerengine.player2api.ConversationHistoryRetentionSelfTest;
import com.player2.playerengine.player2api.LogEgressGuardSelfTest;
import com.player2.playerengine.trackers.storage.SurvivalConsumptionLedgerSelfTest;
import com.player2.playerengine.trackers.storage.SurvivalConsumptionReceiptClassifierSelfTest;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** Standalone entry point for deterministic farming and checked-waypoint regression checks. */
public final class FarmingSelfTestSuite {

    private FarmingSelfTestSuite() {}

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        FarmWaypointDataSelfTest.runAll();
        WaypointMutationSelfTest.runAll();
        EllieGPSBoundedSnapshotSelfTest.runAll();
        TaskStepExecutorAdapterSelfTest.runAll();
        TransientTaskResumeSelfTest.runAll();
        UserTaskChainSelfTest.runAll();
        FoodChainRecoverySelfTest.runAll();
        SurvivalConsumptionLedgerSelfTest.runAll();
        SurvivalConsumptionReceiptClassifierSelfTest.runAll();
        PassiveConversationInfoSelfTest.runAll();
        ConversationControlSelfTest.runAll();
        ConversationHistoryRetentionSelfTest.runAll();
        LogEgressGuardSelfTest.main(new String[0]);
        GotoTargetSelfTest.runAll();
        LookBehaviorSelfTest.runAll();
        FarmGeometrySelfTest.runAll();
        FarmPlotRepairPlanSelfTest.runAll();
        FarmRepairToolPlanSelfTest.runAll();
        FarmSetupContractSelfTest.runAll();
        CropBlockHarvestBehaviorSelfTest.runAll();
        FarmPlantingBehaviorSelfTest.runAll();
        FarmPlantingOrderSelfTest.runAll();
        FarmPlantingReceiptClassifierSelfTest.runAll();
        FarmPlantingItemAcquisitionTaskSelfTest.runAll();
        BlockScannerWindowSelfTest.run();
        MarkedChestItemLocatorSelfTest.runAll();
        BoundedContainerTransferTaskSelfTest.runAll();
        HarvestFarmTaskSelfTest.runAll();
        FarmFeedbackSelfTest.runAll();
        FarmPlantingRequestParserSelfTest.runAll();
        FarmPlantingSelectorSelfTest.runAll();
        PlantFarmFeedbackSelfTest.runAll();
        PlantFarmTaskSelfTest.runAll();
        SetupFarmStepFactorySelfTest.runAll();
        SetupFarmCommandSelfTest.runAll();
        HarvestFarmStepFactorySelfTest.runAll();
        PlantFarmStepFactorySelfTest.runAll();
        AgenticFarmPlanContractSelfTest.runAll();
        System.out.println("FarmingSelfTestSuite: all checks passed");
    }
}
