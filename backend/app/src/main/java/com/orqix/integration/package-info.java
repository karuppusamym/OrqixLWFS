/**
 * Connections, bindings, secrets, egress policy, HTTP executor and effect journal. Never calls Flowable. Reads
 * connection definitions v1 through the compiler's pure-Java {@code ConnectionContracts} (S130), so registration, the
 * compiler and the executor share one reading.
 */
@ApplicationModule(displayName = "Integration", allowedDependencies = {"shared", "identity", "audit", "compiler"})
package com.orqix.integration;

import org.springframework.modulith.ApplicationModule;
