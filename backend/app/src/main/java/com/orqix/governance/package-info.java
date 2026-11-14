/**
 * Governance (docs/build/S151, CG-I06): verification of the audit hash chain (endpoint and scheduled check) and signed
 * audit exports (JWS, ES256) with the public signing keys. Reads audit data with the same permission as before
 * ({@code audit.read}); it never changes who can read it. Nothing depends on this module.
 */
@ApplicationModule(displayName = "Governance", allowedDependencies = {"shared", "identity", "audit", "integration"})
package com.orqix.governance;

import org.springframework.modulith.ApplicationModule;
