"""Sign-in: OAuth 2.0 device authorization grant (RFC 8628) with the identity provider, or a token file.

The access token is the person's own (audience orqix-cli); Orqix checks their permissions exactly as for a browser
sign-in. The token never goes into a URL or a log line.
"""
from __future__ import annotations

import json
import os
import time
from pathlib import Path
from typing import Any, Callable, Dict, Optional
from urllib import error, parse, request

from orqix_cli import config

DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"


class AuthError(Exception):
    """Sign-in failed or is needed; the message is safe to show."""


def _post_form(url: str, fields: Dict[str, str], timeout: float = 30) -> Dict[str, Any]:
    body = parse.urlencode(fields).encode("ascii")
    req = request.Request(url, data=body, method="POST", headers={
        "Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"})
    try:
        with request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8") or "{}")
    except error.HTTPError as e:
        try:
            return json.loads(e.read().decode("utf-8") or "{}") | {"_status": e.code}
        except ValueError:
            return {"error": "http_" + str(e.code), "_status": e.code}
    except error.URLError as e:
        raise AuthError(f"The identity provider at {url} cannot be reached: {e.reason}") from e


def discover(issuer: str, timeout: float = 30) -> Dict[str, str]:
    """The device and token endpoints from the issuer's OpenID configuration."""
    url = issuer.rstrip("/") + "/.well-known/openid-configuration"
    try:
        with request.urlopen(request.Request(url, headers={"Accept": "application/json"}), timeout=timeout) as resp:
            document = json.loads(resp.read().decode("utf-8"))
    except (error.URLError, ValueError) as e:
        raise AuthError(f"The identity provider configuration at {url} cannot be read.") from e
    device = document.get("device_authorization_endpoint")
    token = document.get("token_endpoint")
    if not device or not token:
        raise AuthError("The identity provider does not offer the device sign-in (device_authorization_endpoint).")
    return {"device": device, "token": token}


def device_login(issuer: str, client_id: str, show: Callable[[str], None],
                 sleep: Callable[[float], None] = time.sleep, now: Callable[[], float] = time.time) -> Dict[str, Any]:
    """Runs the device flow; returns the token record to store ({access_token, refresh_token, expires_at, ...})."""
    endpoints = discover(issuer)
    started = _post_form(endpoints["device"], {"client_id": client_id, "scope": "openid"})
    if "device_code" not in started:
        raise AuthError("The identity provider refused the device sign-in: " + str(started.get("error_description")
                                                                                 or started.get("error") or "no reason"))
    link = started.get("verification_uri_complete") or started.get("verification_uri")
    show(f"To sign in, open {link}")
    show(f"and confirm the code {started.get('user_code')}.")
    interval = float(started.get("interval") or 5)
    deadline = now() + float(started.get("expires_in") or 600)
    while now() < deadline:
        sleep(interval)
        answer = _post_form(endpoints["token"], {"grant_type": DEVICE_GRANT, "device_code": started["device_code"],
                                                 "client_id": client_id})
        if "access_token" in answer:
            return _record(answer, issuer, client_id, endpoints["token"], now)
        code = answer.get("error")
        if code == "authorization_pending":
            continue
        if code == "slow_down":
            interval += 5
            continue
        if code == "access_denied":
            raise AuthError("The sign-in was declined.")
        if code == "expired_token":
            break
        raise AuthError("The sign-in failed: " + str(answer.get("error_description") or code))
    raise AuthError("The sign-in code expired. Run orqix login again.")


def _record(answer: Dict[str, Any], issuer: str, client_id: str, token_endpoint: str,
            now: Callable[[], float]) -> Dict[str, Any]:
    return {"access_token": answer["access_token"], "refresh_token": answer.get("refresh_token"),
            "expires_at": int(now() + float(answer.get("expires_in") or 300)), "issuer": issuer,
            "client_id": client_id, "token_endpoint": token_endpoint}


def refresh(record: Dict[str, Any], now: Callable[[], float] = time.time) -> Optional[Dict[str, Any]]:
    """A new token record from the refresh token, or None when that is not possible."""
    if not record.get("refresh_token") or not record.get("token_endpoint"):
        return None
    answer = _post_form(record["token_endpoint"], {"grant_type": "refresh_token", "refresh_token": record["refresh_token"],
                                                   "client_id": record.get("client_id") or config.DEFAULT_CLIENT_ID})
    if "access_token" not in answer:
        return None
    fresh = _record(answer, record.get("issuer", ""), record.get("client_id", ""), record["token_endpoint"], now)
    if not fresh.get("refresh_token"):
        fresh["refresh_token"] = record.get("refresh_token")
    return fresh


def read_token_file(path: str) -> str:
    try:
        text = Path(path).read_text(encoding="utf-8").strip()
    except OSError as e:
        raise AuthError(f"The token file {path} cannot be read.") from e
    if not text or any(c.isspace() for c in text):
        raise AuthError(f"The token file {path} must hold one access token.")
    return text


def access_token(settings: Dict[str, Any], persist: Callable[[Dict[str, Any]], None],
                 now: Callable[[], float] = time.time) -> str:
    """The token to send: ORQIX_TOKEN, ORQIX_TOKEN_FILE, the configured token file, or the stored sign-in (refreshed)."""
    if os.environ.get("ORQIX_TOKEN"):
        return os.environ["ORQIX_TOKEN"].strip()
    token_file = os.environ.get("ORQIX_TOKEN_FILE") or settings.get("tokenFile")
    if token_file:
        return read_token_file(token_file)
    record = settings.get("token")
    if not isinstance(record, dict) or not record.get("access_token"):
        raise AuthError("Not signed in. Run orqix login (or set ORQIX_TOKEN_FILE).")
    if float(record.get("expires_at") or 0) - 30 <= now():
        fresh = refresh(record, now)
        if fresh is None:
            raise AuthError("The sign-in expired. Run orqix login again.")
        settings["token"] = fresh
        persist(settings)
        record = fresh
    return record["access_token"]
