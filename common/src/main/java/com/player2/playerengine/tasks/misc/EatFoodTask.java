package com.player2.playerengine.tasks.misc;

import com.player2.playerengine.tasks.base.Task;
import com.player2.playerengine.trackers.storage.SurvivalConsumptionReceiptClassifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Animation-aware food eating task (WS5).
 *
 * State machine:
 *  1. EQUIP  — guard full hunger, equip the food item into main hand.
 *  2. BEGIN_USE — call entity.startUsingItem(MAIN_HAND) once to start the vanilla use-item path.
 *              This sets DATA_LIVING_ENTITY_FLAGS so remote clients see the eating animation;
 *              vanilla updateUsingItem drives particles + eating sound automatically.
 *  3. WAIT   — tick while entity.isUsingItem() is true and the watchdog has not expired.
 *              Do NOT touch hunger or inventory here.
 *  4. COMPLETE — when !entity.isUsingItem() after startUsingItem, vanilla has already called
 *              LivingEntity.eat() which calls food.consume(1, entity) on 1.21.1. The task's
 *              only job here is to call hungerManager().eat(foodItem) to refill the bot's
 *              hunger/saturation. Do NOT also call removeItem/shrink — that would double-consume.
 *  5. INTERRUPT — if stopped mid-use, call entity.stopUsingItem(); vanilla never reached
 *              completeUsingItem, so the item is NOT consumed and hunger is NOT refilled.
 */
public class EatFoodTask extends Task implements com.player2.playerengine.tasks.base.SurvivalInterruptTask {

    // States
    private enum State { EQUIP, BEGIN_USE, WAIT, COMPLETE }

    private final Item foodItem;
    private State state = State.EQUIP;
    private boolean hasAte = false;
    private boolean consumptionRecorded = false;
    private boolean foodCountBaselineCaptured = false;
    private int foodCountBeforeUse = 0;

    // Watchdog: derived from the item's actual use duration + a small margin.
    // A flat cap (e.g. 40 ticks) would silently abort slow/modded foods before they complete.
    private int watchdogTicks = 0;
    private int watchdogMax = 0;

    public EatFoodTask(Item foodItem) {
        this.foodItem = foodItem;
    }

    @Override
    protected void onStart() {
        this.state = State.EQUIP;
        this.hasAte = false;
        this.consumptionRecorded = false;
        this.foodCountBaselineCaptured = false;
        this.foodCountBeforeUse = 0;
        this.watchdogTicks = 0;
        this.watchdogMax = 0;
    }

    @Override
    protected Task onTick() {
        LivingEntity entity = this.controller.getEntity();

        switch (this.state) {
            case EQUIP: {
                // Guard: if hunger is already full and the food is not always-edible, nothing to do.
                int foodLevel = this.controller.getBaritone().getEntityContext().hungerManager().getFoodLevel();
                if (foodLevel >= 20) {
                    this.fail("Hunger already at 20 (full), cannot eat.");
                    this.hasAte = true;
                    return null;
                }
                // Equip the food into main hand. forceEquipItem returns true when the item is equipped.
                if (this.controller.getSlotHandler().forceEquipItem(foodItem)) {
                    this.state = State.BEGIN_USE;
                    setDebugState("Equipped food, starting use");
                }
                // Not yet equipped — stay in EQUIP and retry next tick.
                // Return null (not this): the chain re-ticks while !isFinished(); returning
                // this would make Task.tick() set sub = this and recurse infinitely.
                return null;
            }

            case BEGIN_USE: {
                // Capture watchdog from the item actually in hand (may differ from foodItem for modded stacks).
                ItemStack inHand = entity.getItemInHand(InteractionHand.MAIN_HAND);
                // 1.21.1: ItemStack.getUseDuration() takes a LivingEntity parameter (entity performing the use).
                // This differs from 1.20.1 where getUseDuration() takes no parameters.
                int useDuration = inHand.isEmpty() ? 32 : inHand.getUseDuration(entity);
                // +10 ticks margin so the watchdog is a true safety net, not a normal-case truncator.
                this.watchdogMax = useDuration + 10;
                this.watchdogTicks = 0;

                this.foodCountBeforeUse = countFoodItem();
                this.foodCountBaselineCaptured = true;
                entity.startUsingItem(InteractionHand.MAIN_HAND);
                this.state = State.WAIT;
                setDebugState("startUsingItem called, waiting for animation to complete");
                return null;
            }

            case WAIT: {
                this.watchdogTicks++;

                if (!entity.isUsingItem()) {
                    // Vanilla completed the use-item sequence (completeUsingItem -> finishUsingItem -> LivingEntity.eat
                    // which already called food.consume(1, entity) on 1.21.1). Move to COMPLETE to refill hunger.
                    this.state = State.COMPLETE;
                    setDebugState("Use complete, refilling hunger");
                    return null; // chain re-ticks; COMPLETE runs next tick (returning this would recurse)
                }

                if (this.watchdogTicks >= this.watchdogMax) {
                    // Safety net: vanilla took too long — abort without refill or consume.
                    // Move out of WAIT before failing so onStop()'s WAIT guard skips a second
                    // stopUsingItem() (fail() -> stop() -> onStop()); this call is the single one.
                    entity.stopUsingItem();
                    this.state = State.COMPLETE;
                    this.fail("Eat watchdog expired (" + this.watchdogTicks + " ticks), aborting without refill.");
                    this.hasAte = true;
                    return null;
                }

                return null; // still waiting; chain re-ticks while !isFinished()
            }

            case COMPLETE: {
                // Vanilla already consumed 1 item (LivingEntity.eat -> food.consume(1, entity) on 1.21.1).
                // The task's ONLY job here is to update the bot's hunger manager.
                // Do NOT call removeItem/shrink — that would double-consume.
                // Make the confirmed vanilla consumption visible to suspended inventory-sensitive
                // tasks. The guard keeps repeated COMPLETE ticks from double-crediting the ledger.
                if (SurvivalConsumptionReceiptClassifier.shouldRecord(
                        this.consumptionRecorded,
                        this.foodCountBaselineCaptured,
                        this.foodCountBeforeUse,
                        countFoodItem())) {
                    this.controller.getSurvivalConsumptionLedger().recordConsumption(this.foodItem);
                    this.consumptionRecorded = true;
                }
                this.controller.getBaritone().getEntityContext().hungerManager().eat(foodItem);
                this.hasAte = true;
                setDebugState("Hunger refilled after animation");
                return null;
            }
        }

        return null;
    }

    @Override
    protected void onStop(Task interruptTask) {
        LivingEntity entity = this.controller.getEntity();
        // If interrupted mid-use, cancel the vanilla use so the animation stops cleanly.
        // Vanilla never reached completeUsingItem, so the item is not consumed and we must not refill.
        if (this.state == State.WAIT && entity != null && entity.isUsingItem()) {
            entity.stopUsingItem();
        }
    }

    private int countFoodItem() {
        return this.controller.getItemStorage().getItemCountInventoryOnly(this.foodItem);
    }

    @Override
    public boolean isFinished() {
        return this.hasAte;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof EatFoodTask task && task.foodItem == this.foodItem;
    }

    @Override
    protected String toDebugString() {
        return "Eating food (animation): " + this.foodItem.toString() + " [" + this.state + "]";
    }
}
