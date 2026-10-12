"""Scripted browser-equivalent client for the local Orqix stack (dev realm test users only).

Logs in through the real OIDC authorization-code flow (Keycloak login form), keeps the session cookie,
and sends the CSRF header like the SPA does. Passwords come from the dev realm file and are never printed.
"""
import json
import os
from pathlib import Path
import re
import uuid
from html import unescape

import requests

BASE = os.environ.get("ORQIX_BASE_URL", "http://localhost:8080").rstrip("/")
REALM_FILE = str(Path(__file__).resolve().parents[2] / "deploy" / "compose" / "keycloak" / "orqix-dev-realm.json")


def _password(username):
    realm = json.load(open(REALM_FILE, encoding="utf-8"))
    user = next(u for u in realm["users"] if u["username"] == username)
    return next(c["value"] for c in user["credentials"] if c["type"] == "password")


class Client:
    def __init__(self, username):
        self.username = username
        self.s = requests.Session()
        r = self._follow(self.s.get(f"{BASE}/oauth2/authorization/orqix", allow_redirects=False, timeout=30))
        m = re.search(r'action="([^"]+login-actions/authenticate[^"]*)"', r.text)
        if not m:
            raise RuntimeError(f"login form not found (status {r.status_code}, url {r.url})")
        self._browser_cookies()
        r = self._follow(self.s.post(unescape(m.group(1)), data={"username": username,
                                                                  "password": _password(username)},
                                     allow_redirects=False, timeout=30))
        me = self.s.get(f"{BASE}/api/v1/me", timeout=30)  # also primes the XSRF-TOKEN cookie
        if me.status_code != 200:
            raise RuntimeError(f"login failed for {username}: {r.status_code} {r.url} -> /me {me.status_code}")

    def _browser_cookies(self):
        # Browsers treat http://localhost as a secure context and send Secure cookies to it; requests does not.
        for cookie in self.s.cookies:
            cookie.secure = False

    def _follow(self, r):
        for _ in range(10):
            self._browser_cookies()
            if not r.is_redirect:
                return r
            loc = r.headers["Location"]
            r = self.s.get(loc if loc.startswith("http") else BASE + loc, allow_redirects=False, timeout=30)
        raise RuntimeError("too many redirects")

    def _headers(self, idem=None, if_match=None):
        h = {"Accept": "application/json"}
        token = self.s.cookies.get("XSRF-TOKEN")
        if token:
            h["X-XSRF-TOKEN"] = token
        if idem:
            h["Idempotency-Key"] = idem
        if if_match:
            h["If-Match"] = if_match
        return h

    def get(self, path, **params):
        return self.s.get(f"{BASE}/api/v1{path}", params=params, headers=self._headers(), timeout=30)

    def post(self, path, body=None, idem=False, key=None):
        k = key or (str(uuid.uuid4()) if idem else None)
        return self.s.post(f"{BASE}/api/v1{path}", json=body or {}, headers=self._headers(k), timeout=60)

    def put(self, path, body, if_match=None):
        return self.s.put(f"{BASE}/api/v1{path}", json=body, headers=self._headers(if_match=if_match), timeout=30)


def show(label, r, keys=None):
    try:
        body = r.json()
    except ValueError:
        body = r.text[:200]
    if keys and isinstance(body, dict):
        body = {k: body.get(k) for k in keys}
    print(f"{label}: {r.status_code} {json.dumps(body)[:300]}")
    return r
