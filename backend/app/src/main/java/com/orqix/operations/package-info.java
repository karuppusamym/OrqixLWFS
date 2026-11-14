/**
 * Operations (docs/build/S144): alert rules on operational signals (CG-O06) and run retention, archiving, legal hold
 * and engine history purge (CG-O07, CG-P04). Nothing depends on this module.
 */
@ApplicationModule(displayName = "Operations",
        allowedDependencies = {"shared", "identity", "audit", "integration", "runtime"})
package com.orqix.operations;

import org.springframework.modulith.ApplicationModule;
