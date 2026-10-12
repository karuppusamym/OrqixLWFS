# S27: Skill-constrained work queues, working calendars and recurring overdue notices

Updated 12 October 2026. Implementation is included in the current competitive-gap batch; combined verification is recorded by the product build tracker.

## Supported behavior

An approval's existing candidate group is its work queue. An optional `requiredSkills` array contains up to ten **existing workspace group keys**. A caller must hold `task.act`, belong to the candidate group and every selected skill group, and satisfy self-approval policy. A skill group does not itself grant a permission. These are binary qualifications represented by managed group membership; proficiency levels, automatic least-load dispatch and workforce planning are not implemented.

Studio's approval inspector lists these skill groups. The task inbox's Work queue selector filters the currently loaded tasks by candidate group; its description states this boundary, and Load more remains available when a selected queue has no tasks in the current page. Server claimable filtering applies skill intersection across the entire query before pagination and counts. Existing priority/age ordering, claiming, release, eligible delegation and operator reassignment remain available.

Eligibility is checked on the server for claim, decision, delegation targets, assignee priority edits, reminder delivery and candidate request-data access. Task entry points and run listing/detail refresh effective workspace access rather than relying on the session policy cache. Existing assignee and past-participant visibility is preserved; losing a skill prevents further decisions even when the caller previously held the task. Required skills are copied into the durable task projection from the immutable release when Flowable creates the task. Existing tasks default to no extra skills.

## Working calendar

An optional approval `calendar` is pinned in the immutable workflow release and compiled BPMN metadata. It contains a Java/IANA time zone, working weekdays (Monday 1 through Sunday 7), same-day opening/closing times and up to 366 explicit holiday dates. With a calendar, `dueAfterHours` consumes elapsed working time inside these windows. Outside working hours, weekends and holidays are skipped. Daylight-saving transitions use actual instants in the selected zone. The due date is durably recorded when the task is created; later draft edits do not change it.

Example:

```json
{
  "candidateGroupRef": "managers",
  "rejectReasonRequired": true,
  "requiredSkills": ["finance-approvers"],
  "dueAfterHours": 8,
  "calendar": {
    "zone": "America/New_York",
    "weekdays": [1, 2, 3, 4, 5],
    "start": "09:00",
    "end": "17:00",
    "holidays": ["2026-12-25"]
  },
  "escalation": { "intervalHours": 24, "maxNotices": 3 }
}
```

Invalid zones/dates, an empty work week, closing before opening, calendars without a due duration and schedules unable to resolve the duration within the bounded ten-year search are rejected during validation. Overnight shifts, calculated public holidays and shared mutable calendar administration are outside this slice. **`timeoutAfter` remains a wall-clock execution timer**; the working calendar changes the displayed/overdue deadline, not timer cancellation semantics. Pure dry-run simulations do not exercise real scheduler timing.

## Recurring overdue notices

`escalation` configures an interval of 1–720 elapsed hours and a cap of 1–20 notices. It requires a due date. The existing durable reminder worker first schedules the approval's due date; at each delivery it locks the queue row, tries the task lock without blocking, and checks that the task remains open and the current assignee is still eligible using fresh policy. Unassigned or ineligible tasks retry later; they do not broadcast request details to a group.

Each delivered notice has a unique task/ordinal deduplication key. Notification creation and queue advancement occur in the same transaction. The next attempt is scheduled from delivery time, so missed polling intervals do not generate a catch-up storm. The cap marks the queue complete, and an ended task is removed from the active reminder queue. No external email/Teams message, reassignment or workflow route change is implied. Workflow designers can separately use the existing explicit timed-out route for an escalation approval.

Approvals without `escalation` retain the existing configured one-shot reminder behavior. Notices are in-app notifications, not new run-event or audit payloads. Operational overdue reporting uses the recorded due dates.

## Verification

- `BusinessCalendarTest`: opening/closing boundaries, weekend/holiday carry, daylight-saving offset change, invalid schedules and sparse-calendar horizon.
- `WorkManagementRulesTest`: schema/binding validation, unknown skills, invalid calendar dates/zones, required deadlines, pinned generated metadata and bounded notice settings.
- `WorkManagementTest`: real PostgreSQL/Flowable task creation, skill-aware inbox/claim/data/delegation, skill revocation without cache eviction, deadline projection, deduplicated bounded notices and stopped delivery after completion.
- `WorkManagementFields.test.tsx`: editable skill/calendar/notice controls and published/read-only restrictions. Existing task inbox, panel and delegation tests remain part of the combined regression suite.

These tests establish the supported local behavior. Customer staffing policy, actual enterprise calendar definitions, production scheduler availability and customer acceptance remain separate requirements. This work does not establish Pega adaptive-case/CMMN parity.
