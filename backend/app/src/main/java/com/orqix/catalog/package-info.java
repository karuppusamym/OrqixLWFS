/**
 * Workflows, drafts, releases, templates and export. Deploys through the {@link com.orqix.catalog.EngineDeployer}
 * port implemented by runtime; never imports Flowable.
 */
@ApplicationModule(displayName = "Catalog", allowedDependencies = {"shared", "identity", "compiler", "integration", "audit"})
package com.orqix.catalog;

import org.springframework.modulith.ApplicationModule;
