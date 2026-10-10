package com.orqix.runtime.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.common.engine.impl.async.AsyncTaskExecutorConfiguration;
import org.flowable.common.engine.impl.async.DefaultAsyncTaskExecutor;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.orqix.shared.config.OrqixProperties;

/**
 * Engine configuration (S5 section 1): BPMN + DMN only; explicit expression bean map (only the five Orqix beans are
 * reachable from expressions, BD-05); one engine-wide projection listener; custom failed-job command factory; a bounded
 * job executor pool (core 4 / max 6, below the connection pool minus API headroom).
 */
@Configuration(proxyBeanMethods = false)
public class FlowableEngineConfiguration {

    /** The only names an expression in generated BPMN may resolve. */
    public static final List<String> EXPRESSION_BEANS =
            List.of("orqixRoute", "orqixDecisionTask", "orqixHttpTask", "orqixNotificationTask", "orqixChildTask", "orqixWait");

    @Bean(destroyMethod = "shutdown")
    DefaultAsyncTaskExecutor orqixJobTaskExecutor(OrqixProperties properties) {
        AsyncTaskExecutorConfiguration configuration = new AsyncTaskExecutorConfiguration();
        configuration.setCorePoolSize(properties.runtime().executorCorePoolSize());
        configuration.setMaxPoolSize(properties.runtime().executorMaxPoolSize());
        configuration.setQueueSize(properties.runtime().executorQueueSize());
        configuration.setThreadPoolNamingPattern("orqix-job-%d");
        DefaultAsyncTaskExecutor executor = new DefaultAsyncTaskExecutor(configuration);
        executor.start();
        return executor;
    }

    @Bean
    EngineConfigurationConfigurer<SpringProcessEngineConfiguration> orqixProcessEngineConfigurer(
            OrqixRoute orqixRoute,
            OrqixWait orqixWait,
            DecisionTaskDelegate orqixDecisionTask,
            HttpTaskDelegate orqixHttpTask,
            NotificationTaskDelegate orqixNotificationTask,
            ChildTaskDelegate orqixChildTask,
            RuntimeProjectionListener projectionListener,
            OrqixFailedJobCommandFactory failedJobCommandFactory,
            DefaultAsyncTaskExecutor orqixJobTaskExecutor) {
        return configuration -> {
            Map<Object, Object> beans = new LinkedHashMap<>();
            beans.put("orqixRoute", orqixRoute);
            beans.put("orqixWait", orqixWait);
            beans.put("orqixDecisionTask", orqixDecisionTask);
            beans.put("orqixHttpTask", orqixHttpTask);
            beans.put("orqixNotificationTask", orqixNotificationTask);
            beans.put("orqixChildTask", orqixChildTask);
            configuration.setBeans(beans);

            configuration.setEnableEventDispatcher(true);
            List<FlowableEventListener> listeners = configuration.getEventListeners() == null
                    ? new java.util.ArrayList<>() : new java.util.ArrayList<>(configuration.getEventListeners());
            listeners.add(projectionListener);
            configuration.setEventListeners(listeners);

            configuration.setFailedJobCommandFactory(failedJobCommandFactory);
            configuration.setAsyncTaskExecutor(orqixJobTaskExecutor);
            // Generated BPMN is validated against the BPMN XSD at deployment (S4 section 10 step 5).
            configuration.setEnableSafeBpmnXml(true);
        };
    }
}
