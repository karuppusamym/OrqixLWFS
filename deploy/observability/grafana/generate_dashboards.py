#!/usr/bin/env python3
"""Generates the Orqix Grafana dashboard pack (CG-P06, docs/build/S150) into dashboards/*.json.

The JSON files are committed; edit this script and run it again rather than editing the JSON:

    python deploy/observability/grafana/generate_dashboards.py

Every panel queries the Prometheus data source chosen in the dashboard's "datasource" variable. Gauges that read the
database are reported identically by every pod, so panels take max() over pods; counters and timers are summed.
"""
from __future__ import annotations

import json
from pathlib import Path

OUT = Path(__file__).resolve().parent / "dashboards"
DS = {"type": "prometheus", "uid": "${datasource}"}


class Board:
    def __init__(self, uid: str, title: str, description: str) -> None:
        self.uid, self.title, self.description = uid, title, description
        self.panels: list[dict] = []
        self.y = 0
        self.x = 0
        self.row_height = 0

    def row(self, title: str) -> None:
        self._newline()
        self.panels.append({"type": "row", "title": title, "collapsed": False, "id": len(self.panels) + 1,
                            "gridPos": {"h": 1, "w": 24, "x": 0, "y": self.y}, "panels": []})
        self.y += 1

    def _newline(self) -> None:
        if self.x:
            self.y += self.row_height
            self.x = 0
            self.row_height = 0

    def _place(self, w: int, h: int) -> dict:
        if self.x + w > 24:
            self._newline()
        pos = {"h": h, "w": w, "x": self.x, "y": self.y}
        self.x += w
        self.row_height = max(self.row_height, h)
        return pos

    def stat(self, title: str, expr: str, unit: str = "short", w: int = 4, thresholds: list | None = None,
             description: str = "") -> None:
        steps = [{"color": "green", "value": None}] + [{"color": c, "value": v} for v, c in (thresholds or [])]
        self.panels.append({
            "type": "stat", "title": title, "description": description, "id": len(self.panels) + 1,
            "datasource": DS, "gridPos": self._place(w, 4),
            "targets": [{"refId": "A", "expr": expr, "datasource": DS}],
            "fieldConfig": {"defaults": {"unit": unit, "thresholds": {"mode": "absolute", "steps": steps}},
                            "overrides": []},
            "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                        "colorMode": "value", "graphMode": "area", "textMode": "auto"},
        })

    def graph(self, title: str, targets: list[tuple[str, str]], unit: str = "short", w: int = 12,
              description: str = "") -> None:
        self.panels.append({
            "type": "timeseries", "title": title, "description": description, "id": len(self.panels) + 1,
            "datasource": DS, "gridPos": self._place(w, 8),
            "targets": [{"refId": chr(65 + i), "expr": expr, "legendFormat": legend, "datasource": DS}
                        for i, (expr, legend) in enumerate(targets)],
            "fieldConfig": {"defaults": {"unit": unit, "custom": {"lineWidth": 1, "fillOpacity": 10}}, "overrides": []},
            "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}},
        })

    def json(self) -> dict:
        return {
            "uid": self.uid, "title": self.title, "description": self.description,
            "tags": ["orqix"], "timezone": "browser", "schemaVersion": 39, "version": 1, "editable": True,
            "refresh": "30s", "time": {"from": "now-6h", "to": "now"}, "graphTooltip": 1,
            "templating": {"list": [
                {"name": "datasource", "label": "Prometheus", "type": "datasource", "query": "prometheus",
                 "current": {}, "hide": 0, "refresh": 1},
                {"name": "job", "label": "Job", "type": "query", "datasource": DS,
                 "query": {"query": "label_values(orqix_runs_started_total, job)", "refId": "job"},
                 "definition": "label_values(orqix_runs_started_total, job)", "includeAll": True, "multi": True,
                 "current": {}, "hide": 0, "refresh": 2, "allValue": ".*"},
            ]},
            "annotations": {"list": []},
            "links": [{"title": "Orqix dashboards", "type": "dashboards", "tags": ["orqix"], "asDropdown": True}],
            "panels": self.panels,
        }


J = 'job=~"$job"'


def overview() -> Board:
    b = Board("orqix-overview", "Orqix - Overview and SLOs",
              "Service level objectives of docs/build/S150 section 5: API availability and latency, engine job "
              "freshness, requests started and completed.")
    b.row("Service level objectives (30-day targets)")
    b.stat("API availability (5m)", f'1 - (sum(rate(http_server_requests_seconds_count{{{J},uri=~"/api/.*",status=~"5.."}}[5m]))'
           f' / clamp_min(sum(rate(http_server_requests_seconds_count{{{J},uri=~"/api/.*"}}[5m])), 1e-9))',
           "percentunit", thresholds=[(0, "red"), (0.995, "green")], description="SLO: 99.5 % of API requests do not fail with 5xx")
    b.stat("Start p95 (5m)", f'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{{{J},method="POST",'
           f'uri="/api/v1/workspaces/{{workspaceId}}/runs"}}[5m])))', "s", thresholds=[(1, "orange"), (2, "red")],
           description="SLO: 95 % of starts answer within 1 s")
    b.stat("Read p95 (5m)", f'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{{{J},method="GET",'
           f'uri=~"/api/.*"}}[5m])))', "s", thresholds=[(0.5, "orange"), (1, "red")],
           description="SLO: 95 % of reads answer within 500 ms")
    b.stat("Oldest executable job", f"max(orqix_oldest_executable_job_age_seconds{{{J}}})", "s",
           thresholds=[(60, "orange"), (300, "red")], description="SLO: below 60 s 99 % of the time")
    b.stat("Analytics lag", f"max(orqix_analytics_lag_seconds{{{J}}})", "s", thresholds=[(300, "orange"), (900, "red")],
           description="SLO: below 5 min 99 % of the time")
    b.stat("Needs attention", f"max(orqix_runs_needs_attention{{{J}}})", thresholds=[(1, "orange")])
    b.row("Traffic")
    b.graph("Requests started / completed per minute", [
        (f"sum(rate(orqix_runs_started_total{{{J}}}[5m])) * 60", "started"),
        (f"sum by (outcomeKind) (rate(orqix_runs_completed_total{{{J}}}[5m])) * 60", "completed {{outcomeKind}}"),
    ], "short")
    b.graph("Task decisions per minute", [
        (f"sum by (action) (rate(orqix_tasks_decided_total{{{J}}}[5m])) * 60", "{{action}}"),
    ])
    b.graph("API requests per second by status", [
        (f'sum by (status) (rate(http_server_requests_seconds_count{{{J},uri=~"/api/.*"}}[5m]))', "{{status}}"),
    ], "reqps")
    b.graph("API latency p50 / p95 / p99", [
        (f'histogram_quantile({q}, sum by (le) (rate(http_server_requests_seconds_bucket{{{J},uri=~"/api/.*"}}[5m])))', f"p{int(q * 100)}")
        for q in (0.5, 0.95, 0.99)
    ], "s")
    b.graph("Admission refusals per minute (CG-P05)", [
        (f"sum by (kind) (rate(orqix_admission_rejected_total{{{J}}}[5m])) * 60", "{{kind}}"),
    ], description="Starts and inbound messages refused with 429 by the workspace limits")
    b.graph("Authorization denials per minute", [
        (f"sum by (permission) (rate(orqix_authz_denied_total{{{J}}}[5m])) * 60", "{{permission}}"),
    ])
    b.row("Pods")
    b.graph("Database connections (Hikari)", [
        (f'sum by (instance) (hikaricp_connections_active{{{J}}})', "active {{instance}}"),
        (f'sum by (instance) (hikaricp_connections_pending{{{J}}})', "pending {{instance}}"),
    ])
    b.graph("CPU per pod", [(f"process_cpu_usage{{{J}}}", "{{instance}}")], "percentunit")
    b.graph("Heap used per pod", [(f'sum by (instance) (jvm_memory_used_bytes{{{J},area="heap"}})', "{{instance}}")], "bytes")
    b.graph("Engine jobs", [
        (f"max(orqix_oldest_executable_job_age_seconds{{{J}}})", "oldest executable job (s)"),
    ], "s")
    return b


def integrations() -> Board:
    b = Board("orqix-integrations", "Orqix - Connections, polls and AI",
              "Connector calls, circuit breakers, keep-checking steps and AI calls (Waves A-B features).")
    b.row("Connector calls")
    b.graph("Calls per second by outcome", [
        (f"sum by (outcome) (rate(orqix_connector_call_seconds_count{{{J}}}[5m]))", "{{outcome}}"),
    ], "reqps")
    b.graph("Call latency p95 by effect class", [
        (f"histogram_quantile(0.95, sum by (le, effect_class) (rate(orqix_connector_call_seconds_bucket{{{J}}}[5m])))",
         "{{effect_class}}"),
    ], "s")
    b.graph("Effect attempts by outcome", [
        (f"sum by (outcome) (rate(orqix_effect_attempts_total{{{J}}}[5m])) * 60", "{{outcome}}"),
    ], description="Per minute")
    b.stat("Open circuits", f'max(orqix_connection_circuits{{{J},state="open"}})', thresholds=[(1, "orange"), (5, "red")])
    b.stat("Half-open circuits", f'max(orqix_connection_circuits{{{J},state="halfOpen"}})', thresholds=[(1, "orange")])
    b.row("Keep checking (poll waits)")
    b.stat("Active poll waits", f"max(orqix_poll_waits_active{{{J}}})")
    b.stat("Due, unclaimed", f"max(orqix_poll_waits_due{{{J}}})", thresholds=[(50, "orange"), (500, "red")])
    b.stat("Poll check lag", f"max(orqix_poll_check_lag_seconds{{{J}}})", "s", thresholds=[(60, "orange"), (300, "red")])
    b.graph("Poll checks per minute by outcome", [
        (f"sum by (outcome) (rate(orqix_poll_checks_total{{{J}}}[5m])) * 60", "{{outcome}}"),
    ])
    b.row("AI")
    b.stat("AI tasks available", f'max(orqix_ai_tasks{{{J},status="available"}})')
    b.stat("AI tasks running", f'max(orqix_ai_tasks{{{J},status="running"}})')
    b.stat("AI queue age", f"max(orqix_ai_task_queue_age_seconds{{{J}}})", "s", thresholds=[(300, "orange"), (900, "red")])
    b.graph("AI tasks ended per minute", [
        (f"sum by (status) (rate(orqix_ai_tasks_ended_total{{{J}}}[5m])) * 60", "{{status}}"),
    ])
    b.graph("Model provider calls p95 by host", [
        (f"histogram_quantile(0.95, sum by (le, server_address) (rate(orqix_ai_call_seconds_bucket{{{J}}}[5m])))",
         "{{server_address}}"),
    ], "s")
    b.graph("Model provider calls by outcome", [
        (f"sum by (outcome) (rate(orqix_ai_call_seconds_count{{{J}}}[5m])) * 60", "{{outcome}}"),
    ])
    return b


def operations() -> Board:
    b = Board("orqix-operations", "Orqix - Operations",
              "Bulk operations, alerts, retention and the analytics read model (Wave D features).")
    b.row("Bulk operations")
    b.stat("Running bulk operations", f"max(orqix_bulk_operations_running{{{J}}})")
    b.graph("Bulk items per minute", [
        (f"sum by (action, outcome) (rate(orqix_bulk_items_total{{{J}}}[5m])) * 60", "{{action}} {{outcome}}"),
    ])
    b.row("Alerts")
    b.stat("Open alert incidents", f"max(orqix_alert_incidents_open{{{J}}})", thresholds=[(1, "orange")])
    b.graph("Alerts fired and resolved per hour", [
        (f"sum(rate(orqix_alerts_fired_total{{{J}}}[1h])) * 3600", "fired"),
        (f"sum(rate(orqix_alerts_resolved_total{{{J}}}[1h])) * 3600", "resolved"),
    ])
    b.row("Analytics read model")
    b.stat("Analytics lag", f"max(orqix_analytics_lag_seconds{{{J}}})", "s", thresholds=[(300, "orange"), (900, "red")])
    b.stat("Outbox pending", f"max(orqix_analytics_outbox_pending{{{J}}})", thresholds=[(1000, "orange"), (10000, "red")])
    b.stat("Outbox dead", f"max(orqix_analytics_outbox_dead{{{J}}})", thresholds=[(1, "red")])
    b.graph("Analytics lag over time", [(f"max(orqix_analytics_lag_seconds{{{J}}})", "lag")], "s")
    b.row("Retention")
    b.graph("Retention sweeps per hour", [
        (f"sum by (outcome) (rate(orqix_retention_requests_total{{{J}}}[1h])) * 3600", "requests {{outcome}}"),
        (f"sum(rate(orqix_retention_history_purged_total{{{J}}}[1h])) * 3600", "engine histories purged"),
    ])
    return b


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for board in (overview(), integrations(), operations()):
        path = OUT / f"{board.uid}.json"
        path.write_text(json.dumps(board.json(), indent=2) + "\n", encoding="utf-8", newline="\n")
        print(f"wrote {path.relative_to(OUT.parent.parent.parent.parent)}")


if __name__ == "__main__":
    main()
