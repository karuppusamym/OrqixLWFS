/**
 * Cross-cutting building blocks: configuration, problem details, ids, idempotency, pagination, JSON and web filters.
 * Depends on no other module (S1 section 3). Open: every sub-package may be used by the business modules.
 */
@ApplicationModule(displayName = "Shared", type = ApplicationModule.Type.OPEN, allowedDependencies = {})
package com.orqix.shared;

import org.springframework.modulith.ApplicationModule;
