/**
 * Engine facade: start, tasks, delegates, projection listener, events, recovery. The only module that imports
 * Flowable; Flowable types never leave it.
 */
@ApplicationModule(displayName = "Runtime",
        allowedDependencies = {"shared", "identity", "catalog", "compiler", "integration", "notification", "audit"})
package com.orqix.runtime;

import org.springframework.modulith.ApplicationModule;
