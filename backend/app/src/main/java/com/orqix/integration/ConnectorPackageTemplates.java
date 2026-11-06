package com.orqix.integration;

import java.util.Optional;
import java.util.UUID;

/**
 * Starter workflows of installed connector packages (CG-C03/CG-C04, docs/build/S133), for the catalog module, which
 * creates the workflow. Only an approved, actively installed package version of the workspace gives one.
 */
public interface ConnectorPackageTemplates {

    /**
     * @param definitionJson workflow-v0 document whose package connection dependencies are pinned to the connection
     *                       versions installed in the workspace
     */
    record Starter(String definitionJson, String packageId, String packageVersion) {
    }

    Optional<Starter> starterWorkflow(UUID workspaceId, UUID packageVersionId, String templateKey);
}
