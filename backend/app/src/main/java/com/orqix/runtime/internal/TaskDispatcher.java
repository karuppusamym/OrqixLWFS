package com.orqix.runtime.internal;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.identity.SubjectService;
import com.orqix.shared.web.Sql;

/**
 * Explicit, explainable routing of a new approval task to one eligible person (docs/build/S39). The approval step opts
 * in with {@code dispatch.policy}; without it the task stays in the candidate group queue exactly as before.
 * Eligibility is the same as for delegation: candidate group, every required skill group, {@code task.act} and the
 * self-approval rule, evaluated against the latest stored groups of each known person.
 */
@Component
public class TaskDispatcher {

    public static final String LEAST_LOADED = "leastLoaded";
    public static final String ROUND_ROBIN = "roundRobin";

    /** Most people considered; the directory itself is bounded to 500 known subjects. */
    static final int MAX_CANDIDATES = 500;

    /** The person chosen and the facts that explain the choice. */
    public record Choice(UUID subjectId, String displayName, String policy, int eligible, int openTasks, String rule) {

        public Map<String, Object> eventData() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("policy", policy);
            data.put("assignee", displayName);
            data.put("eligible", eligible);
            data.put("openTasks", openTasks);
            data.put("rule", rule);
            return data;
        }
    }

    private record Candidate(UUID id, String name, int open, java.time.Instant lastAssigned) {
    }

    private final SubjectService subjects;
    private final TaskService taskService;
    private final TaskStore tasks;
    private final JdbcClient jdbc;
    private final Clock clock;

    public TaskDispatcher(SubjectService subjects, @org.springframework.context.annotation.Lazy TaskService taskService,
            TaskStore tasks, JdbcClient jdbc, Clock clock) {
        this.subjects = subjects;
        this.taskService = taskService;
        this.tasks = tasks;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Picks the person for the open task, or empty when the step has no valid policy or nobody is eligible. Nothing is
     * written; {@link #assign} applies the choice.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Choice> choose(UUID workspaceId, String taskId, String policy) {
        if (!LEAST_LOADED.equals(policy) && !ROUND_ROBIN.equals(policy)) {
            return Optional.empty();
        }
        TaskStore.TaskRow task = tasks.find(workspaceId, taskId).orElse(null);
        if (task == null || !task.isOpen() || task.assignee() != null) {
            return Optional.empty();
        }
        Map<UUID, int[]> open = new LinkedHashMap<>();
        Map<UUID, java.time.Instant> last = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT assignee_subject_id, count(*) FILTER (WHERE status = 'open') AS open_count, max(claimed_at) AS last_at
                FROM task_context WHERE workspace_id = :ws AND assignee_subject_id IS NOT NULL
                GROUP BY assignee_subject_id
                """)
                .param("ws", workspaceId)
                .query((rs, n) -> {
                    UUID id = Sql.uuid(rs, "assignee_subject_id");
                    open.put(id, new int[] {rs.getInt("open_count")});
                    last.put(id, Sql.instant(rs, "last_at"));
                    return id;
                }).list();
        List<Candidate> eligible = new ArrayList<>();
        for (SubjectService.Subject subject : subjects.knownSubjects(MAX_CANDIDATES)) {
            if (taskService.eligibleSubject(workspaceId, task, subject.id())) {
                eligible.add(new Candidate(subject.id(), subject.displayName(),
                        open.getOrDefault(subject.id(), new int[] {0})[0], last.get(subject.id())));
            }
        }
        if (eligible.isEmpty()) {
            return Optional.empty();
        }
        // Never-assigned people come first, then the least recently assigned; the id makes the order total.
        Comparator<Candidate> byRecency = Comparator
                .comparing((Candidate c) -> c.lastAssigned() != null)
                .thenComparing(Candidate::lastAssigned, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Candidate::id);
        Comparator<Candidate> order = LEAST_LOADED.equals(policy)
                ? Comparator.comparingInt(Candidate::open).thenComparing(byRecency) : byRecency;
        Candidate chosen = eligible.stream().min(order).orElseThrow();
        String rule = LEAST_LOADED.equals(policy)
                ? "Fewest open tasks among " + eligible.size() + " eligible people; ties go to the person assigned longest ago."
                : "Taken in turn among " + eligible.size() + " eligible people; the person assigned longest ago goes first.";
        return Optional.of(new Choice(chosen.id(), chosen.name(), policy, eligible.size(), chosen.open(), rule));
    }

    /** Policy name recorded when the person was proposed on the request item (S104). */
    public static final String PROPOSED = "proposed";

    /**
     * The person proposed for one item of a list-driven approval (S104), when that person may decide the task now:
     * exactly one active person with that email, who passes the same eligibility as any dispatch (candidate group,
     * required skills, {@code task.act}, self-approval and separation of duties). Empty otherwise; nothing is written.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Choice> proposed(UUID workspaceId, String taskId, String email) {
        TaskStore.TaskRow task = tasks.find(workspaceId, taskId).orElse(null);
        if (task == null || !task.isOpen() || task.assignee() != null) {
            return Optional.empty();
        }
        return subjects.findActiveByEmail(email)
                .filter(subject -> taskService.eligibleSubject(workspaceId, task, subject.id()))
                .map(subject -> new Choice(subject.id(), subject.displayName(), PROPOSED, 1, 0,
                        "Proposed for this item on the request."));
    }

    /** Records the choice on the task row. The caller sets the engine assignee and updates the run projection. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean assign(UUID workspaceId, String taskId, Choice choice) {
        return tasks.setAssignee(workspaceId, taskId, 1, choice.subjectId(), clock.instant());
    }
}
