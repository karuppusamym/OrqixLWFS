package com.orqix.runtime.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** A slow SMTP server must not occupy the scheduler responsible for SLA detection and operational maintenance. */
@Configuration
@Profile("!migrate")
class StageMailScheduling {
    // A scheduler also implements Executor. A non-default candidate preserves Boot's applicationTaskExecutor,
    // which Flowable CMMN and Spring MVC require; scheduled mail explicitly selects this bean by name.
    @Bean(name="stageMailScheduler",defaultCandidate=false)
    ThreadPoolTaskScheduler stageMailScheduler() {return scheduler("orqix-mail-",2);}
    private ThreadPoolTaskScheduler scheduler(String prefix,int size) {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(size);scheduler.setThreadNamePrefix(prefix);
        scheduler.setRemoveOnCancelPolicy(true);scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);return scheduler;
    }
}
