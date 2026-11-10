"""A small client for the Orqix REST API (contracts/openapi.yaml) with the person's bearer token."""
from __future__ import annotations

import json
from typing import Any, Callable, Dict, Iterator, Optional, Tuple
from urllib import error, parse, request


class ApiError(Exception):
    """A problem answer (RFC 9457) or a transport failure."""

    def __init__(self, status: int, code: str, detail: str, body: Optional[Dict[str, Any]] = None):
        super().__init__(f"{status} {code}: {detail}")
        self.status = status
        self.code = code
        self.detail = detail
        self.body = body or {}

    def field_errors(self) -> list:
        errors = self.body.get("fieldErrors") or self.body.get("errors") or []
        return errors if isinstance(errors, list) else []


class Client:
    def __init__(self, server: str, token: Callable[[], str], timeout: float = 60,
                 opener: Optional[Callable[..., Any]] = None):
        self.base = server.rstrip("/") + "/api/v1"
        self._token = token
        self.timeout = timeout
        self._open = opener or request.urlopen

    def call(self, method: str, path: str, body: Any = None, query: Optional[Dict[str, Any]] = None,
             headers: Optional[Dict[str, str]] = None) -> Tuple[Any, Dict[str, str]]:
        url = self.base + path
        if query:
            clean = {k: v for k, v in query.items() if v is not None}
            if clean:
                url += "?" + parse.urlencode(clean)
        data = None if body is None else json.dumps(body).encode("utf-8")
        all_headers = {"Accept": "application/json", "Authorization": "Bearer " + self._token()}
        if data is not None:
            all_headers["Content-Type"] = "application/json"
        all_headers.update(headers or {})
        req = request.Request(url, data=data, method=method, headers=all_headers)
        try:
            with self._open(req, timeout=self.timeout) as resp:
                text = resp.read().decode("utf-8")
                return (json.loads(text) if text else None), {k.lower(): v for k, v in resp.headers.items()}
        except error.HTTPError as e:
            text = e.read().decode("utf-8", "replace")
            try:
                problem = json.loads(text) if text else {}
            except ValueError:
                problem = {}
            raise ApiError(e.code, str(problem.get("code") or "HTTP_" + str(e.code)),
                           str(problem.get("detail") or problem.get("title") or e.reason), problem) from None
        except error.URLError as e:
            raise ApiError(0, "UNREACHABLE", f"{self.base} cannot be reached: {e.reason}") from None

    def get(self, path: str, query: Optional[Dict[str, Any]] = None) -> Any:
        return self.call("GET", path, query=query)[0]

    def post(self, path: str, body: Any, headers: Optional[Dict[str, str]] = None) -> Any:
        return self.call("POST", path, body=body, headers=headers)[0]

    def pages(self, path: str, query: Optional[Dict[str, Any]] = None, limit: int = 1000) -> Iterator[Dict[str, Any]]:
        """Items of a cursor-paged list, at most {@code limit}."""
        params = dict(query or {})
        seen = 0
        for _ in range(200):
            page = self.get(path, params)
            for item in page.get("items", []):
                yield item
                seen += 1
                if seen >= limit:
                    return
            cursor = page.get("nextCursor")
            if not cursor:
                return
            params["cursor"] = cursor


def quote(value: str) -> str:
    return parse.quote(str(value), safe="")
