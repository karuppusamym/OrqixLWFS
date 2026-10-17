"""Docker resilience scenarios on the Compose stack with two application instances (docs/build/S9).

  killnode    T-RES-03  kill -9 of the instance executing an HTTP job (stub delay 25 s); the other instance resumes
  dboutage    T-RES-04  PostgreSQL stopped for 60 s during traffic
  shutdown    T-RES-05  SIGTERM (docker stop) of the instance executing an HTTP job
  revocation  T-SEC-09  access removed through one instance reaches the other within the 60 s policy cache bound

Run through tools/resilience/resilience.py. Every scenario restores the stack (all services running and ready) in a
finally block and writes evidence/resilience/<test>.json.
"""
import collections
import random
import threading
import time

import requests

from common import (INSTANCES, STUB, Api, Report, claim_and_approve, compose, container, container_state, ensure_stack,
                    hostname, iso, ok, open_task, psql, publish_purchase, purchasing_workspace, ready, run_events,
                    sh, start_run, stub_fault, stub_orders, stub_reset, wait_run)

PRIMARY, SECOND = "orqix", "orqix-2"
RECOVERY_BOUND_S = 120  # doc 01 acceptance bound: lock time 60 s + reset interval 15 s, with margin


def api_of(service):
    return INSTANCES[service]["api"]


def users():
    return {name: Api(name) for name in ("dana.designer", "paul.publisher", "rhea.requester", "mani.manager",
                                         "olga.operator")}


def await_in_flight(run_id, timeout=60):
    """Waits until the effect journal shows the call in flight; returns (lease owner hostname, effect row)."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        rows = psql(f"SELECT state, lease_owner, attempts, lease_until FROM orqix.effect_journal "
                    f"WHERE run_id = '{run_id}'")
        if rows and rows[0][0] == "inFlight":
            return rows[0][1], rows[0]
        time.sleep(0.25)
    raise AssertionError("the HTTP call did not reach the in-flight state")


def service_of(owner):
    for service in (PRIMARY, SECOND):
        if hostname(service) == owner:
            return service
    raise AssertionError(f"lease owner {owner} is none of the application containers")


def effect_summary(api, base, ws, run_id):
    run = ok(api.get(base, f"/workspaces/{ws}/runs/{run_id}"), 200, "run")
    events = run_events(api, base, ws, run_id)
    return run, events, collections.Counter(e["type"] for e in events)


# ------------------------------------------------------------------------------------------------ T-RES-03
def killnode(victim):
    survivor = SECOND if victim == PRIMARY else PRIMARY
    report = Report(f"T-RES-03-kill-{victim}", f"kill -9 of {victim} while it executes an HTTP job; {survivor} resumes")
    ensure_stack()
    u = users()
    ws = purchasing_workspace(u["rhea.requester"], api_of(PRIMARY))
    wf = publish_purchase(u["dana.designer"], u["paul.publisher"], api_of(PRIMARY), ws, "t-res-03")
    stub_reset()
    stub_fault("delay", times=1, delay_ms=25000)
    try:
        run = start_run(u["rhea.requester"], api_of(PRIMARY), ws, wf)
        task = open_task(u["mani.manager"], api_of(victim), ws, run["id"])
        status = claim_and_approve(u["mani.manager"], api_of(victim), ws, task)  # decision through the victim
        report.check(status == (200, 201), f"manager decision accepted through {victim} {status}")
        owner, row = await_in_flight(run["id"])
        executing = service_of(owner)
        report.measure("executingInstance", executing)
        if executing != victim:
            report.check(False, "the job runs on the instance that committed the decision")
            raise AssertionError("job executes on the other instance; rerun")
        lock = psql("SELECT lock_owner_, lock_exp_time_ FROM flowable.act_ru_job WHERE lock_owner_ IS NOT NULL")
        report.measure("jobLockAtKill", lock[0] if lock else None)
        time.sleep(3)  # call is in flight at the stub
        killed_at = time.monotonic()
        killed_iso = iso()
        sh(["docker", "kill", "--signal=SIGKILL", container(victim)])
        report.measure("killedAt", killed_iso)
        final = wait_run(u["olga.operator"], api_of(survivor), ws, run["id"],
                         lambda r: r["lifecycle"] in ("completed", "needsAttention"), timeout=RECOVERY_BOUND_S + 60)
        recovered_s = time.monotonic() - killed_at
        report.measure("recoverySeconds", round(recovered_s, 1))
        report.check(final and final["lifecycle"] == "completed" and final.get("outcome") == "Fulfilled",
                     f"run completes as Fulfilled on {survivor} (state {final and final['lifecycle']})")
        report.check(recovered_s <= RECOVERY_BOUND_S,
                     f"recovered {recovered_s:.0f} s after the kill (bound {RECOVERY_BOUND_S} s = lock time 60 s + "
                     f"reset interval 15 s + margin)")
        orders = stub_orders()
        report.measure("stubOrders", {"count": orders["count"], "createRequests": orders.get("createRequests"),
                                      "replays": orders.get("replays")})
        report.check(orders["count"] == 1, "exactly one order at the procurement stub")
        _, events, counts = effect_summary(u["olga.operator"], api_of(survivor), ws, run["id"])
        effect = final["effects"][0]
        report.measure("effect", {"state": effect["state"], "attempts": effect["attempts"]})
        report.check(effect["state"] == "succeeded" and effect["attempts"] >= 1, "effect journal: succeeded")
        report.check(counts["EFFECT_SUCCEEDED"] == 1 and counts["RUN_COMPLETED"] == 1,
                     "one EFFECT_SUCCEEDED and one RUN_COMPLETED event")
        report.measure("eventTimeline", [(e["type"], e["occurredAt"]) for e in events if e["seq"] > 3])
    finally:
        stub_fault("none")
        sh(["docker", "start", container(victim)], check=False)
        ready(victim)
        report.measure("victimRestartedAndReady", True)
    return report.finish()


# ------------------------------------------------------------------------------------------------ T-RES-05
def shutdown(victim):
    survivor = SECOND if victim == PRIMARY else PRIMARY
    report = Report(f"T-RES-05-sigterm-{victim}", f"SIGTERM (docker stop) of {victim} while it executes an HTTP job")
    ensure_stack()
    u = users()
    ws = purchasing_workspace(u["rhea.requester"], api_of(PRIMARY))
    wf = publish_purchase(u["dana.designer"], u["paul.publisher"], api_of(PRIMARY), ws, "t-res-05")
    stub_reset()
    stub_fault("delay", times=1, delay_ms=15000)
    try:
        run = start_run(u["rhea.requester"], api_of(PRIMARY), ws, wf)
        task = open_task(u["mani.manager"], api_of(victim), ws, run["id"])
        report.check(claim_and_approve(u["mani.manager"], api_of(victim), ws, task) == (200, 201),
                     f"manager decision accepted through {victim}")
        owner, _ = await_in_flight(run["id"])
        report.check(service_of(owner) == victim, "the victim executes the job")
        time.sleep(2)
        stop_started = time.monotonic()
        stop_iso = iso()
        compose("stop", "-t", "60", victim, timeout=120)
        stop_s = time.monotonic() - stop_started
        state = container_state(victim)
        report.measure("stopSeconds", round(stop_s, 1))
        report.measure("exitCode", state["ExitCode"])
        report.check(state["ExitCode"] in (0, 143), f"orderly exit (exit code {state['ExitCode']}, 137 would be SIGKILL)")
        report.check(stop_s < 60, f"drained within the grace period ({stop_s:.0f} s < 60 s)")
        final = wait_run(u["olga.operator"], api_of(survivor), ws, run["id"],
                         lambda r: r["lifecycle"] in ("completed", "needsAttention"), timeout=180)
        report.check(final and final["lifecycle"] == "completed" and final.get("outcome") == "Fulfilled",
                     f"run completes ({final and final['lifecycle']})")
        orders = stub_orders()
        report.measure("stubOrders", {"count": orders["count"], "createRequests": orders.get("createRequests"),
                                      "replays": orders.get("replays")})
        report.check(orders["count"] == 1, "exactly one order: no duplicate effect")
        effect = final["effects"][0]
        report.measure("effect", {"state": effect["state"], "attempts": effect["attempts"]})
        _, events, counts = effect_summary(u["olga.operator"], api_of(survivor), ws, run["id"])
        report.check(counts["EFFECT_SUCCEEDED"] == 1 and counts["RUN_COMPLETED"] == 1,
                     "one EFFECT_SUCCEEDED and one RUN_COMPLETED event")
        report.measure("eventTypes", [e["type"] + (("(" + str(e.get("data", {}).get("errorCode")) + ")")
                                                   if e["type"] == "EFFECT_FAILED" else "") for e in events])
        logs = sh(["docker", "logs", "--since", stop_iso, container(victim)], check=False)
        lines = (logs.stdout + logs.stderr).splitlines()
        interesting = [ln[:220] for ln in lines if any(k in ln for k in (
            "Commencing graceful shutdown", "Graceful shutdown complete", "failed", "OptimisticLocking",
            "Shutting down the async job executor"))]
        report.measure("victimShutdownLog", interesting[:12])
    finally:
        stub_fault("none")
        compose("up", "-d", "--no-build", "--no-recreate", victim, timeout=300)
        ready(victim)
        report.measure("victimRestartedAndReady", True)
    return report.finish()


# ------------------------------------------------------------------------------------------------ T-RES-04
class Traffic:
    """Background load: reads and commands against both instances; every outcome is recorded with its time."""

    def __init__(self, ws, wf, tasks, apis):
        self.ws, self.wf, self.tasks, self.apis = ws, wf, list(tasks), apis
        self.samples = []  # (monotonic time, kind, status or exception name, latency s)
        self.stop_flag = threading.Event()
        self.lock = threading.Lock()
        self.threads = []
        self.task_lock = threading.Lock()

    def record(self, kind, status, latency):
        with self.lock:
            self.samples.append((time.monotonic(), kind, status, latency))

    def _read(self, who, base, path, kind):
        t = time.monotonic()
        try:
            status = who.get(base, path, timeout=45).status_code
        except requests.RequestException as e:
            status = type(e).__name__
        self.record(kind, status, time.monotonic() - t)

    def _command_start(self, who, base):
        t = time.monotonic()
        try:
            status = who.post(base, f"/workspaces/{self.ws}/runs", {"workflowId": self.wf, "input": {
                "title": "Outage traffic", "purpose": "T-RES-04", "costCenter": "OPS", "amount": 100}},
                idem=True, timeout=45).status_code
        except requests.RequestException as e:
            status = type(e).__name__
        self.record("command:start", status, time.monotonic() - t)

    def _command_claim(self, who, base):
        with self.task_lock:
            if not self.tasks:
                return
            task = self.tasks[random.randrange(len(self.tasks))]
        t = time.monotonic()
        try:
            status = who.post(base, f"/workspaces/{self.ws}/tasks/{task['id']}/claim",
                              {"expectedVersion": task["version"]}, timeout=45).status_code
        except requests.RequestException as e:
            status = type(e).__name__
        self.record("command:claim", status, time.monotonic() - t)

    def worker(self, index):
        reader, manager = self.apis["rhea.requester"], self.apis["mani.manager"]
        rnd = random.Random(index)
        while not self.stop_flag.is_set():
            base = api_of(rnd.choice([PRIMARY, SECOND]))
            choice = rnd.random()
            if choice < 0.35:
                self._read(manager, base, f"/workspaces/{self.ws}/tasks?view=claimable&limit=20", "read:tasks")
            elif choice < 0.6:
                self._read(reader, base, f"/workspaces/{self.ws}/runs?limit=20", "read:runs")
            elif choice < 0.8:
                self._command_start(reader, base)
            else:
                self._command_claim(manager, base)
            time.sleep(rnd.uniform(0.2, 0.6))

    def start(self, workers=6):
        for i in range(workers):
            t = threading.Thread(target=self.worker, args=(i,), daemon=True)
            t.start()
            self.threads.append(t)

    def stop(self):
        self.stop_flag.set()
        for t in self.threads:
            t.join(timeout=60)


class HealthMonitor:
    """Polls liveness and readiness of both instances every 0.5 s with a 5 s timeout (like a kubelet probe)."""

    def __init__(self):
        self.samples = []  # (monotonic, instance, probe, status)
        self.stop_flag = threading.Event()
        self.threads = []

    def poll(self, service, probe):
        url = INSTANCES[service]["mgmt"] + f"/actuator/health/{probe}"
        while not self.stop_flag.is_set():
            try:
                status = requests.get(url, timeout=5).status_code
            except requests.RequestException as e:
                status = type(e).__name__
            self.samples.append((time.monotonic(), service, probe, status))
            time.sleep(0.5)

    def start(self):
        for service in (PRIMARY, SECOND):
            for probe in ("liveness", "readiness"):
                t = threading.Thread(target=self.poll, args=(service, probe), daemon=True)
                t.start()
                self.threads.append(t)

    def stop(self):
        self.stop_flag.set()
        for t in self.threads:
            t.join(timeout=15)


def percentile(values, p):
    if not values:
        return None
    values = sorted(values)
    return round(values[min(len(values) - 1, int(len(values) * p))], 2)


def dboutage(outage_seconds=60):
    report = Report("T-RES-04", f"PostgreSQL stopped for {outage_seconds} s during traffic (two instances)")
    ensure_stack()
    u = users()
    ws = purchasing_workspace(u["rhea.requester"], api_of(PRIMARY))
    wf = publish_purchase(u["dana.designer"], u["paul.publisher"], api_of(PRIMARY), ws, "t-res-04")
    stub_reset()
    # Waiting runs for the claim commands, and four runs whose HTTP call is in flight when the outage starts.
    waiting = []
    for _ in range(10):
        run = start_run(u["rhea.requester"], api_of(PRIMARY), ws, wf, amount=900, title="Outage waiting task")
        waiting.append(open_task(u["mani.manager"], api_of(PRIMARY), ws, run["id"]))
    stub_fault("delay", times=4, delay_ms=12000)
    pending = []
    for i in range(4):
        run = start_run(u["rhea.requester"], api_of(PRIMARY), ws, wf, amount=1800 + i, title="Outage pending job")
        task = open_task(u["mani.manager"], api_of(SECOND if i % 2 else PRIMARY), ws, run["id"])
        pending.append(run["id"])
        report.check(claim_and_approve(u["mani.manager"], api_of(SECOND if i % 2 else PRIMARY), ws, task)
                     == (200, 201), f"pending job {i + 1} approved")
    traffic = Traffic(ws, wf, waiting, u)
    health = HealthMonitor()
    health.start()
    traffic.start()
    stop_t = down_t = up_t = None
    try:
        time.sleep(8)  # baseline traffic; the four calls are in flight at the stub
        stop_t = time.monotonic()
        compose("stop", "-t", "30", "postgres", timeout=90)
        down_t = time.monotonic()
        report.measure("postgresStoppedAt", iso())
        time.sleep(max(0, outage_seconds - (time.monotonic() - down_t)))
        compose("start", "postgres", timeout=90)
        up_t = time.monotonic()
        report.measure("postgresStartedAt", iso())
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if sh(["docker", "exec", container("postgres"), "pg_isready", "-h", "127.0.0.1", "-U", "postgres"],
                  check=False).returncode == 0:
                break
            time.sleep(0.5)
        pg_ready_t = time.monotonic()
        report.measure("postgresReadyAfterStartSeconds", round(pg_ready_t - up_t, 1))
        for service in (PRIMARY, SECOND):
            ready(service, timeout=150)
        recovered_t = time.monotonic()
        report.measure("readinessRecoveredAfterPostgresStartSeconds", round(recovered_t - up_t, 1))
        time.sleep(15)  # keep the traffic running after recovery
    finally:
        traffic.stop()
        health.stop()
        compose("up", "-d", "--no-build", "--no-recreate", "postgres", timeout=120)
        for service in (PRIMARY, SECOND):
            ready(service)

    def window(samples, lo, hi):
        return [s for s in samples if lo <= s[0] <= hi]

    outage = window(traffic.samples, down_t, up_t)
    commands = [s for s in outage if s[1].startswith("command")]
    reads = [s for s in outage if s[1].startswith("read")]
    successes = [s for s in commands if isinstance(s[2], int) and 200 <= s[2] < 300]
    by_status = collections.Counter(str(s[2]) for s in commands)
    report.measure("outageSeconds", round(up_t - down_t, 1))
    report.measure("commandsDuringOutage", dict(by_status))
    report.measure("readsDuringOutage", dict(collections.Counter(str(s[2]) for s in reads)))
    report.measure("commandLatencySeconds", {"p50": percentile([s[3] for s in commands], 0.5),
                                             "max": round(max([s[3] for s in commands], default=0), 1)})
    report.check(commands, "commands were attempted during the outage")
    report.check(not successes, f"no command reported success during the outage ({len(successes)} of {len(commands)})")
    only_503 = [s for s in commands if s[2] == 503]
    report.check(len(only_503) >= 1, f"commands answer 503 DEPENDENCY_UNAVAILABLE ({len(only_503)} of {len(commands)}; "
                                     f"others: {dict(by_status)})")
    bad = [s for s in commands if s[2] not in (503,) and not isinstance(s[2], str)]
    report.check(not bad, f"no other status than 503 for commands during the outage ({[s[2] for s in bad][:5]})")
    # liveness / readiness
    live = [s for s in health.samples if s[2] == "liveness" and down_t <= s[0] <= up_t]
    live_bad = [s for s in live if s[3] != 200]
    report.measure("livenessSamplesDuringOutage", len(live))
    report.check(live and not live_bad, f"liveness stays up on both instances ({len(live_bad)} failures in {len(live)})")
    for service in (PRIMARY, SECOND):
        rd = [s for s in health.samples if s[1] == service and s[2] == "readiness"]
        first_bad = next((s for s in rd if s[0] >= stop_t and s[3] != 200), None)
        first_good_after = next((s for s in rd if s[0] >= up_t and s[3] == 200), None)
        drop_s = round(first_bad[0] - down_t, 1) if first_bad else None
        recover_s = round(first_good_after[0] - up_t, 1) if first_good_after else None
        report.measure(f"{service}.readinessDropSecondsAfterStop", drop_s)
        report.measure(f"{service}.readinessStatusDuringOutage",
                       dict(collections.Counter(str(s[3]) for s in rd if down_t <= s[0] <= up_t)))
        report.measure(f"{service}.readinessRecoverySecondsAfterStart", recover_s)
        report.check(first_bad is not None, f"{service}: readiness drops during the outage")
        report.check(first_good_after is not None, f"{service}: readiness recovers after PostgreSQL returns")
    # recovery of commands after the outage
    after = [s for s in traffic.samples if s[0] > up_t and s[1].startswith("command")]
    first_ok = next((s for s in after if isinstance(s[2], int) and 200 <= s[2] < 300 or s[2] == 409), None)
    report.measure("firstCommandSucceededAfterPostgresStartSeconds", round(first_ok[0] - up_t, 1) if first_ok else None)
    report.check(first_ok is not None, "commands succeed again after recovery")
    # pending jobs complete, exactly one order each
    final = {}
    for run_id in pending:
        final[run_id] = wait_run(u["olga.operator"], api_of(PRIMARY), ws, run_id,
                                 lambda r: r["lifecycle"] in ("completed", "needsAttention"), timeout=300)
    finished_t = time.monotonic()
    report.measure("pendingJobsCompletedAfterPostgresStartSeconds", round(finished_t - up_t, 1))
    states = {run_id: (r["lifecycle"], r["effects"][0]["attempts"] if r.get("effects") else None)
              for run_id, r in final.items()}
    report.measure("pendingRuns", states)
    report.check(all(r and r["lifecycle"] == "completed" for r in final.values()),
                 "all runs whose HTTP call was in flight complete after recovery")
    orders = stub_orders()
    report.measure("stubOrders", {"count": orders["count"], "createRequests": orders.get("createRequests"),
                                  "replays": orders.get("replays")})
    keys = [o.get("idempotencyKey") for o in orders["orders"]]
    report.check(len(keys) == len(set(keys)), "no duplicate idempotency key at the stub")
    pending_orders = [o for o in orders["orders"] if (o.get("request") or {}).get("title") == "Outage pending job"]
    report.check(len(pending_orders) == 4, f"exactly one order per pending run ({len(pending_orders)} of 4)")
    report.measure("trafficSamples", len(traffic.samples))
    return report.finish()


# ------------------------------------------------------------------------------------------------ T-SEC-09
def revocation():
    report = Report("T-SEC-09", "access removed through one instance reaches the other within the 60 s policy cache bound")
    ensure_stack()
    admin, otto, fred = Api("wade.admin"), Api("otto.outsider"), Api("fred.facilities")
    one, two = api_of(PRIMARY), api_of(SECOND)
    ws = purchasing_workspace(Api("rhea.requester"), one)
    otto_id = ok(otto.get(one, "/me"), 200, "me")["subject"]["id"]
    fred_id = ok(fred.get(one, "/me"), 200, "me")["subject"]["id"]

    def bind(role, subject):
        created = ok(admin.post(one, f"/workspaces/{ws}/access/role-bindings", {
            "role": role, "principal": {"type": "subject", "value": subject}}), 201, f"bind {role}")
        return created["id"]

    created = []
    try:
        fred_viewer = bind("VIEWER", fred_id)          # membership that will be removed
        otto_viewer = bind("VIEWER", otto_id)          # membership that stays
        otto_admin = bind("WORKSPACE_ADMIN", otto_id)  # permission that will be removed
        created = [fred_viewer, otto_viewer, otto_admin]
        # Fill the cache of the second instance (and the first) with the granted state.
        report.check(fred.get(two, f"/workspaces/{ws}").status_code == 200, "fred: member through the second instance")
        report.check(otto.get(two, f"/workspaces/{ws}/access").status_code == 200, "otto: admin through the second instance")
        report.check(fred.get(one, f"/workspaces/{ws}").status_code == 200, "fred: member through the first instance")
        removed_t = time.monotonic()
        for binding in (fred_viewer, otto_admin):
            ok(admin.delete(one, f"/workspaces/{ws}/access/role-bindings/{binding}"), 204, "remove binding")
        created = [otto_viewer]
        report.measure("removedAt", iso())
        report.check(fred.get(one, f"/workspaces/{ws}").status_code == 404, "first instance (changed it): immediate 404")
        report.check(otto.get(one, f"/workspaces/{ws}/access").status_code == 403, "first instance: immediate 403")
        converged = {}
        deadline = removed_t + 100
        while time.monotonic() < deadline and len(converged) < 2:
            if "membership" not in converged and fred.get(two, f"/workspaces/{ws}").status_code == 404:
                converged["membership"] = round(time.monotonic() - removed_t, 1)
            if "permission" not in converged and otto.get(two, f"/workspaces/{ws}/access").status_code == 403:
                converged["permission"] = round(time.monotonic() - removed_t, 1)
            time.sleep(1)
        report.measure("secondInstanceConvergedSecondsAfterRemoval", converged)
        for kind, seconds in converged.items():
            report.check(seconds <= 62, f"{kind} removal reached the second instance after {seconds} s (bound 60 s + 2 s polling)")
        report.check(len(converged) == 2, "both removals reached the second instance")
        report.check(otto.get(two, f"/workspaces/{ws}").status_code == 200, "otto stays a member (viewer) on the second instance")
    finally:
        for binding in created:
            admin.delete(one, f"/workspaces/{ws}/access/role-bindings/{binding}")
    return report.finish()
