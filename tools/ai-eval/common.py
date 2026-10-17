"""Shared helpers for the Orqix AI evaluation tools (docs/build/S10-ai-assistance.md section 7).

- OpenRouter client with usage accounting and a hard spend cap (never prints the key).
- Bridge to the backend's own prompt construction and draft rules (com.orqix.ai.eval.AiEvalCli in the app test
  sources), so the evaluation measures exactly what the product accepts.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
TOOL = Path(__file__).resolve().parent
OPENROUTER = "https://openrouter.ai/api/v1"


def load_key() -> str:
    """OPENROUTER_API_KEY from the environment or the repository's gitignored .env. Never printed or logged."""
    key = os.environ.get("OPENROUTER_API_KEY", "").strip()
    env = REPO / ".env"
    if not key and env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            if line.startswith("OPENROUTER_API_KEY="):
                key = line.split("=", 1)[1].strip().strip('"').strip("'")
    if not key:
        sys.exit("OPENROUTER_API_KEY is not set (environment or .env). Nothing was spent.")
    return key


class SpendCapReached(RuntimeError):
    pass


class OpenRouter:
    """Minimal Chat Completions client. Every call asks for usage accounting; total cost is tracked and capped."""

    def __init__(self, key: str, max_spend_usd: float, max_price=(1.0, 3.0)):
        self._key = key
        self.max_spend = max_spend_usd
        self.max_price = max_price
        self.spent = 0.0
        self.calls = 0
        self._lock = threading.Lock()

    def models(self) -> dict:
        with urllib.request.urlopen(OPENROUTER + "/models", timeout=30) as response:
            return {m["id"]: m for m in json.load(response)["data"]}

    def chat(self, model: str, messages: list, *, max_tokens: int, schema: dict | None = None,
             schema_name: str = "reply", provider_sort: str | None = None, temperature: float | None = 0.2,
             timeout: float = 180.0, price_cap: bool = True) -> dict:
        with self._lock:
            if self.spent >= self.max_spend:
                raise SpendCapReached(f"spend cap USD {self.max_spend:.2f} reached")
            self.calls += 1
        body = {"model": model, "messages": messages, "max_tokens": max_tokens, "usage": {"include": True}}
        if temperature is not None:
            body["temperature"] = temperature
        if schema is not None:
            body["response_format"] = {"type": "json_schema",
                                       "json_schema": {"name": schema_name, "strict": False, "schema": schema}}
        provider = {}
        if price_cap:
            provider["max_price"] = {"prompt": self.max_price[0], "completion": self.max_price[1]}
        if provider_sort:
            provider["sort"] = provider_sort
        if provider:
            body["provider"] = provider
        request = urllib.request.Request(OPENROUTER + "/chat/completions", data=json.dumps(body).encode("utf-8"),
                                         headers={"Authorization": "Bearer " + self._key,
                                                  "Content-Type": "application/json", "X-Title": "Orqix ai-eval"})
        started = time.monotonic()
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                data = json.load(response)
        except urllib.error.HTTPError as e:
            return {"error": f"HTTP {e.code}", "latency_s": round(time.monotonic() - started, 2)}
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            return {"error": type(e).__name__, "latency_s": round(time.monotonic() - started, 2)}
        latency = round(time.monotonic() - started, 2)
        usage = data.get("usage") or {}
        cost = usage.get("cost")
        with self._lock:
            self.spent += float(cost or 0.0)
        if data.get("error"):
            return {"error": f"provider error {data['error'].get('code')}", "latency_s": latency, "cost": cost}
        choice = (data.get("choices") or [{}])[0]
        return {
            "content": (choice.get("message") or {}).get("content") or "",
            "finish_reason": choice.get("finish_reason"),
            "model": data.get("model"),
            "provider": data.get("provider"),
            "input_tokens": usage.get("prompt_tokens"),
            "output_tokens": usage.get("completion_tokens"),
            "reasoning_tokens": (usage.get("completion_tokens_details") or {}).get("reasoning_tokens"),
            "cost": cost,
            "latency_s": latency,
        }


class Bridge:
    """JSON-lines bridge to com.orqix.ai.eval.AiEvalCli (backend prompt + draft rules, no network)."""

    def __init__(self, backend: Path, build: bool = True):
        java = Path(os.environ.get("JAVA_HOME", "")) / "bin" / ("java.exe" if os.name == "nt" else "java")
        java_cmd = str(java) if java.exists() else "java"
        mvnw = str(backend / ("mvnw.cmd" if os.name == "nt" else "mvnw"))
        if build:
            subprocess.run([mvnw, "-B", "-ntp", "-q", "-pl", "app", "-am", "test-compile"], cwd=backend, check=True)
        cp_file = Path(tempfile.gettempdir()) / "orqix-ai-eval-classpath.txt"
        subprocess.run([mvnw, "-B", "-ntp", "-q", "-pl", "app", "dependency:build-classpath",
                        f"-Dmdep.outputFile={cp_file}", "-Dmdep.includeScope=test"], cwd=backend, check=True)
        sep = ";" if os.name == "nt" else ":"
        classpath = sep.join([str(backend / "app/target/test-classes"), str(backend / "app/target/classes"),
                              str(backend / "compiler/target/classes"), cp_file.read_text(encoding="utf-8").strip()])
        self._process = subprocess.Popen([java_cmd, "-cp", classpath, "com.orqix.ai.eval.AiEvalCli"],
                                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding="utf-8")
        self._lock = threading.Lock()
        ready = json.loads(self._process.stdout.readline())
        if not ready.get("ready"):
            raise RuntimeError("AiEvalCli did not start")

    def request(self, **payload) -> dict:
        with self._lock:
            self._process.stdin.write(json.dumps(payload) + "\n")
            self._process.stdin.flush()
            answer = json.loads(self._process.stdout.readline())
        if "error" in answer:
            raise RuntimeError(answer["error"])
        return answer

    def close(self):
        self._process.stdin.close()
        self._process.wait(timeout=30)


def percentile(values: list[float], p: float) -> float | None:
    data = sorted(v for v in values if v is not None)
    if not data:
        return None
    k = (len(data) - 1) * p
    lower, upper = int(k), min(int(k) + 1, len(data) - 1)
    return round(data[lower] + (data[upper] - data[lower]) * (k - lower), 2)


def stamp() -> str:
    return time.strftime("%Y%m%d-%H%M%S")
