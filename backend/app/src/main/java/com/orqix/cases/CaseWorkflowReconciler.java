package com.orqix.cases;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Applies the results of workflow runs started by case tasks (WM04, S110). Results are read from the run records, so a
 * restart loses nothing: an ended run is applied on the next pass, each in its own transaction.
 */
@Component
public class CaseWorkflowReconciler {
    private static final Logger LOG = LoggerFactory.getLogger(CaseWorkflowReconciler.class);
    private final CaseService cases;

    public CaseWorkflowReconciler(CaseService cases) { this.cases = cases; }

    @Scheduled(fixedDelayString = "${orqix.cases.workflow-reconcile-delay:2s}", initialDelayString = "${orqix.cases.workflow-reconcile-delay:2s}")
    public void sweep() {
        try {
            run();
        } catch (RuntimeException e) {
            LOG.warn("Case workflow reconciliation failed; it runs again shortly ({})", e.getClass().getSimpleName());
        }
    }

    /** One pass (also called by tests). @return the number of results applied, fenced or recorded */
    public int run() {
        int applied = 0;
        for (UUID task : cases.runningWorkflowTasks(100)) {
            try {
                if (cases.reconcileWorkflowTask(task)) applied++;
            } catch (RuntimeException e) {
                LOG.warn("Case workflow task {} could not be reconciled yet ({}: {})", task, e.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
        return applied;
    }
}
