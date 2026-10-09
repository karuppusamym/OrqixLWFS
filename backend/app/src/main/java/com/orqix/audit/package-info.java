/** Append-only audit writer (S6 section 7). Depends only on shared. */
@ApplicationModule(displayName = "Audit", allowedDependencies = {"shared"})
package com.orqix.audit;

import org.springframework.modulith.ApplicationModule;
