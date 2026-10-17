package com.orqix.runtime.engine;

import java.util.Map;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.DeploymentBuilder;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.catalog.EngineDeployer;

/**
 * Runtime implementation of the catalog deployment port (S4 section 10 steps 5-6): one tenant deployment with the
 * BPMN and its DMN resources, category {@code orqix}, XML schema validation on, inside the caller's transaction.
 */
@Component
public class EngineDeploymentAdapter implements EngineDeployer {

    private final RepositoryService repositoryService;

    public EngineDeploymentAdapter(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Deployed deploy(String name, String key, String tenantId, Map<String, byte[]> resources) {
        DeploymentBuilder builder = repositoryService.createDeployment()
                .name(name)
                .key(key)
                .tenantId(tenantId)
                .category("orqix");
        resources.forEach(builder::addBytes);
        Deployment deployment = builder.deploy();
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
        if (definition == null) {
            throw new IllegalStateException("Deployment " + deployment.getId() + " has no process definition");
        }
        return new Deployed(deployment.getId(), definition.getId());
    }
}
