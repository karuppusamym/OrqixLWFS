/** Connections, bindings, secrets, egress policy, HTTP executor and effect journal. Never calls Flowable. */
@ApplicationModule(displayName = "Integration", allowedDependencies = {"shared", "identity", "audit"})
package com.orqix.integration;

import org.springframework.modulith.ApplicationModule;
