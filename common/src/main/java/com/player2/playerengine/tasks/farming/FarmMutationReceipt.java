package com.player2.playerengine.tasks.farming;

/** Exact owned world/inventory receipt retained while a farm mutation child is detached. */
interface FarmMutationReceipt {
    /** True only after this child sent an input that could still own a transition. */
    boolean hasIssuedMutation();

    /** Settles a visible exact transition, leaves an unchanged issued action pending, or fails drift. */
    boolean settlePendingTransition();

    /** Prevents priority interruption from misclassifying this retained receipt as operator cancel. */
    void prepareForTransientDetach();

    boolean isSuccessful();

    FarmTaskReason reason();
}
