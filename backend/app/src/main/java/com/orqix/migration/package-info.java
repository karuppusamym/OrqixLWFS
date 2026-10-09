/**
 * Migration adapters (E16, docs/build/S11-camunda-import.md): the first adapter imports Camunda 7/8 BPMN 2.0 into a
 * reviewable canonical draft with a compatibility report. Pure parsing, analysis and conversion live in
 * {@code camunda}; persistence, authorization and the REST API sit around them. Drafts are created only through the
 * catalog's public facade; nothing imported becomes executable without the normal validate and publish steps.
 */
@ApplicationModule(displayName = "Migration",
        allowedDependencies = {"shared", "identity", "audit", "catalog", "compiler", "integration"})
package com.orqix.migration;

import org.springframework.modulith.ApplicationModule;
