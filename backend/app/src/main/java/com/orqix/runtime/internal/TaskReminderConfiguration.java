package com.orqix.runtime.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Profile;

@Configuration
@EnableConfigurationProperties(TaskReminderSettings.class)
class TaskReminderConfiguration {}

@Configuration
@Profile("!migrate")
@EnableScheduling
class TaskReminderSchedulingConfiguration {}
