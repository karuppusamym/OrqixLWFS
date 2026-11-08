/**
 * Analytics read model (docs/build/S145, doc 29 section 9.7): heatmaps (CG-N01), KPI targets with alert hooks
 * (CG-N03) and human work analytics (CG-N05). Reads only the reporting tables that the runtime projector fills from the
 * run timeline; outputs are aggregates for people with {@code run.read}, never request titles, input or people.
 * {@link com.orqix.analytics.KpiAlertHook} is the public hook alert rules implement.
 */
@ApplicationModule(displayName = "Analytics", allowedDependencies = {"shared", "identity", "audit"})
package com.orqix.analytics;

import org.springframework.modulith.ApplicationModule;
