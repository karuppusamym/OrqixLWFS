package com.orqix.runtime.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Profile;

@Configuration
@EnableConfigurationProperties(TaskReminderSettings.class)
class TaskReminderConfiguration {}

/** All scheduled background work (and the Kafka consumer reconcile loop) runs on all-in-one and worker pods only. */
@Configuration
@Profile("!migrate & !api")
@EnableScheduling
class TaskReminderSchedulingConfiguration {}
