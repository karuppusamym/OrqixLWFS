/**
 * Workflow analytics (Operate/Optimize): aggregates over the run projection tables for people with {@code run.read}.
 * Read-only; never exposes request titles or input data.
 */
@ApplicationModule(displayName = "Insights", allowedDependencies = {"shared", "identity"})
package com.orqix.insights;

import org.springframework.modulith.ApplicationModule;
