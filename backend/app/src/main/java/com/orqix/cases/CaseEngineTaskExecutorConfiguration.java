package com.orqix.cases;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Flowable CMMN requires Boot's named application executor when its repository service is initialized. */
@Configuration(proxyBeanMethods=false)
class CaseEngineTaskExecutorConfiguration {
    @Bean(name="applicationTaskExecutor")
    @ConditionalOnMissingBean(name="applicationTaskExecutor")
    ThreadPoolTaskExecutor applicationTaskExecutor() {
        var executor=new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("orqix-case-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
