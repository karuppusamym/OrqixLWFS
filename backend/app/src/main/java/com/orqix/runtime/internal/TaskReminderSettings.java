package com.orqix.runtime.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("orqix.reminders")
public record TaskReminderSettings(@DefaultValue("true") boolean enabled,
        @DefaultValue("24h") Duration after) {
    public TaskReminderSettings {
        if (after == null || after.compareTo(Duration.ofMinutes(1)) < 0 || after.compareTo(Duration.ofDays(30)) > 0)
            throw new IllegalArgumentException("Task reminder delay must be between one minute and 30 days.");
    }
}
