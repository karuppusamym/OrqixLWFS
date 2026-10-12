package com.orqix.runtime.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import com.orqix.notification.NotificationService;
import com.orqix.shared.web.Sql;

/** Durable one-shot reminders or bounded recurring overdue notices, always checking current assignee eligibility. */
@Service
@org.springframework.context.annotation.Profile("!migrate")
public class TaskReminders {
    private record Pending(UUID workspace, String task, int count, int max, Integer interval) {}
    private final JdbcClient jdbc;
    private final TaskStore tasks;
    private final TaskService taskService;
    private final NotificationService notifications;
    private final TaskReminderSettings settings;
    private final Clock clock;
    public TaskReminders(JdbcClient jdbc, TaskStore tasks, TaskService taskService, NotificationService notifications,
            TaskReminderSettings settings, Clock clock) {
        this.jdbc=jdbc; this.tasks=tasks; this.taskService=taskService; this.notifications=notifications;
        this.settings=settings; this.clock=clock;
    }
    @Scheduled(fixedDelayString="${orqix.reminders.poll-delay:60s}")
    @Transactional
    public void dispatch() {
        if (!settings.enabled()) return;
        // Adopt open tasks that predate this feature, using the current configured delay.
        jdbc.sql("INSERT INTO task_reminder(workspace_id,task_id,next_attempt_at) SELECT t.workspace_id,t.engine_task_id,t.created_at+(:seconds * interval '1 second') FROM task_context t WHERE t.status='open' AND NOT EXISTS (SELECT 1 FROM task_reminder r WHERE r.workspace_id=t.workspace_id AND r.task_id=t.engine_task_id) ORDER BY t.created_at,t.engine_task_id LIMIT 100 ON CONFLICT DO NOTHING")
                .param("seconds",settings.after().toSeconds()).update();
        var pending=jdbc.sql("SELECT workspace_id,task_id,notice_count,max_notices,interval_hours FROM task_reminder WHERE delivered_at IS NULL AND next_attempt_at<=:now ORDER BY next_attempt_at,task_id LIMIT 100 FOR UPDATE SKIP LOCKED")
                .param("now",Sql.ts(clock.instant())).query((rs,n)->new Pending(Sql.uuid(rs,"workspace_id"),rs.getString("task_id"),
                        rs.getInt("notice_count"),rs.getInt("max_notices"),(Integer)rs.getObject("interval_hours"))).list();
        for (var p:pending) {
            var task=tasks.tryLock(p.workspace(),p.task()).orElse(null);
            // A busy task remains queued. Its FK guarantees it exists; retry on the next poll.
            if (task==null) continue;
            if (!task.isOpen()) {
                jdbc.sql("DELETE FROM task_reminder WHERE workspace_id=:ws AND task_id=:task")
                        .param("ws",p.workspace()).param("task",p.task()).update();
                continue;
            }
            if (task.assignee()==null || !taskService.eligibleSubject(p.workspace(),task,task.assignee())) {
                jdbc.sql("UPDATE task_reminder SET next_attempt_at=:retry WHERE workspace_id=:ws AND task_id=:task")
                        .param("retry",Sql.ts(clock.instant().plus(Duration.ofHours(1))))
                        .param("ws",p.workspace()).param("task",p.task()).update();
                continue;
            }
            boolean recurring=p.max()>0;
            if (recurring && (task.dueAt()==null || task.dueAt().isAfter(clock.instant()))) continue;
            notifications.create(p.workspace(),task.assignee(),task.runId(),NotificationService.Kind.WORKFLOW_MESSAGE,
                    "task-reminder:"+p.workspace()+":"+p.task()+(recurring ? ":"+(p.count()+1) : ""),
                    (recurring ? "Overdue notice "+(p.count()+1)+" of "+p.max()+": " : "Reminder: ")
                            +"your task \""+task.label()+"\" is still waiting for a decision.");
            boolean finished=!recurring || p.count()+1>=p.max();
            jdbc.sql("UPDATE task_reminder SET delivered_at=:delivered,notice_count=notice_count+1,next_attempt_at=:next WHERE workspace_id=:ws AND task_id=:task")
                    .param("delivered",finished ? Sql.ts(clock.instant()) : null)
                    .param("next",Sql.ts(clock.instant().plus(Duration.ofHours(recurring ? p.interval() : 1))))
                    .param("ws",p.workspace()).param("task",p.task()).update();
        }
    }
}
