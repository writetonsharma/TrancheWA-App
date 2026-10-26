package com.tranche.bakery.payment;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs coordinator work outside even Spring's empty NOT_SUPPORTED synchronization. */
public final class PostTransactionEffects {
    private PostTransactionEffects() { }

    public static void run(Runnable work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Post-transaction effects cannot run in a transaction");
        }
        boolean emptyScope = TransactionSynchronizationManager.isSynchronizationActive();
        if (emptyScope) {
            // Never discard callbacks/resources from a real commit or an enclosing owner.
            // At coordinator entry NOT_SUPPORTED has just created an empty scope. Keeping
            // it alive would bind nontransactional JDBC connections across provider I/O.
            if (!TransactionSynchronizationManager.getSynchronizations().isEmpty()) {
                throw new IllegalStateException("Post-transaction effects require completed resource cleanup");
            }
            TransactionSynchronizationManager.clearSynchronization();
        }
        try {
            work.run();
        } finally {
            // Restore the interceptor's scope so its normal completion/cleanup still runs.
            if (emptyScope) TransactionSynchronizationManager.initSynchronization();
        }
    }
}