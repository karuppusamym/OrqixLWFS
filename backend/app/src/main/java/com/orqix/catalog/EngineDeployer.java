package com.orqix.catalog;

import java.util.Map;

/**
 * Deployment port (S1 section 3): catalog deploys compiled artifacts only through this interface, implemented by the
 * runtime module (the only module that imports Flowable). The deployment joins the caller's transaction, so a rolled
 * back publish leaves no engine deployment.
 */
public interface EngineDeployer {

    record Deployed(String deploymentId, String processDefinitionId) {
    }

    /**
     * @param name      {@code <workflowKey> r<n>}
     * @param key       workflow UUID
     * @param tenantId  workspace engine tenant
     * @param resources resource name to bytes ({@code process.bpmn20.xml}, {@code decisions/<key>.dmn})
     */
    Deployed deploy(String name, String key, String tenantId, Map<String, byte[]> resources);
}
