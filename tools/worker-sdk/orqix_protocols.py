"""Native, bounded async clients. No automatic dispatch retries, discovery URLs or remote file downloads."""
from __future__ import annotations

import hashlib
import http.client
import ipaddress
import json
import math
import re
import socket
import ssl
import threading
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import quote, urlsplit

MAX_BYTES = 1024 * 1024


class ProtocolError(Exception):
    def __init__(self, code: str, retryable: bool = False, status=None, problem_code=None):
        super().__init__(code)  # Never provider error bodies, payloads, URLs or credentials.
        self.code, self.retryable = code, retryable
        self.status, self.problem_code = status, problem_code


def object_json(raw: str | bytes) -> dict:
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('duplicate member')
            result[key] = value
        return result
    try:
        value = json.loads(raw, object_pairs_hook=pairs, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        if not isinstance(value, dict):
            raise ValueError()
        return value
    except (ValueError, UnicodeError, RecursionError):
        raise ProtocolError('INVALID_JSON') from None


def opaque(value: Any) -> str:
    if not isinstance(value, str) or not 1 <= len(value) <= 256 or any(ord(c) < 33 or ord(c) == 127 for c in value):
        raise ProtocolError('INVALID_REMOTE_ID')
    return value


def member(value, *keys):
    for key in keys:
        if not isinstance(value, dict):
            return None
        value = value.get(key)
    return value


@dataclass(frozen=True)
class Profile:
    protocol: str
    endpoint: str
    result_mapping: dict
    token_file: str | None = field(default=None, repr=False)
    tool: str | None = None
    model: str | None = None
    max_output_tokens: int = 1024
    instructions: str = 'Analyze the supplied inputs and return the requested JSON result.'
    allow_private: bool = False
    allow_http: bool = False
    timeout_seconds: int = 3600
    poll_seconds: int = 5

    def __post_init__(self):
        if self.protocol not in ('mcp-2025-11-25', 'a2a-1.0', 'responses-v1'):
            raise ProtocolError('UNSUPPORTED_PROTOCOL')
        if not isinstance(self.endpoint, str):
            raise ProtocolError('INVALID_ENDPOINT')
        if self.token_file is not None and (not isinstance(self.token_file, str) or not self.token_file.strip()):
            raise ProtocolError('INVALID_CREDENTIAL_REFERENCE')
        try:
            url = urlsplit(self.endpoint)
            port = url.port
            if (url.scheme not in ('http', 'https') or not url.hostname or url.username is not None
                    or url.password is not None or url.query or url.fragment or '\\' in self.endpoint
                    or any(ord(c) <= 32 or ord(c) >= 127 for c in self.endpoint)
                    or len(self.endpoint) > 2048 or port == 0):
                raise ValueError()
            if url.scheme == 'http' and not (self.allow_http and self.allow_private):
                raise ValueError()
        except ValueError:
            raise ProtocolError('INVALID_ENDPOINT') from None
        if (type(self.allow_private) is not bool or type(self.allow_http) is not bool
                or type(self.timeout_seconds) is not int or not 30 <= self.timeout_seconds <= 86400
                or type(self.poll_seconds) is not int or not 1 <= self.poll_seconds <= 60
                or type(self.max_output_tokens) is not int or not 1 <= self.max_output_tokens <= 32768):
            raise ProtocolError('INVALID_LIMITS')
        if not isinstance(self.instructions, str) or not 1 <= len(self.instructions) <= 4000:
            raise ProtocolError('INVALID_INSTRUCTIONS')
        if self.protocol.startswith('mcp') and (not isinstance(self.tool, str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,128}', self.tool)):
            raise ProtocolError('TOOL_REQUIRED')
        if self.protocol == 'responses-v1' and (not isinstance(self.model, str) or not re.fullmatch(r'[A-Za-z0-9_./:-]{1,128}', self.model) or not self.token_file):
            raise ProtocolError('MODEL_AND_TOKEN_REQUIRED')
        if (not isinstance(self.result_mapping, dict) or not 1 <= len(self.result_mapping) <= 10
                or any(not isinstance(k, str) or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,63}', k) or not isinstance(v, str)
                       or not v.startswith('/') or len(v) > 256 or re.search(r'~(?![01])', v) for k, v in self.result_mapping.items())):
            raise ProtocolError('INVALID_RESULT_MAPPING')

    def fingerprint(self):
        return hashlib.sha256(json.dumps(self.__dict__, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


class Transport:
    """Exact configured endpoint, fresh DNS validation, pinned connection address, normal TLS hostname checks."""
    def __init__(self, profile: Profile, error_codes=False):
        self.profile, self.error_codes = profile, error_codes

    def exchange(self, method: str, body: dict | None = None, headers: dict | None = None,
                 suffix: str = '') -> tuple[dict, dict]:
        url = urlsplit(self.profile.endpoint)
        port = url.port or (443 if url.scheme == 'https' else 80)
        connection = None
        try:
            addresses = socket.getaddrinfo(url.hostname, port, type=socket.SOCK_STREAM)
            if not addresses or (not self.profile.allow_private and any(not ipaddress.ip_address(item[4][0]).is_global for item in addresses)):
                raise ProtocolError('EGRESS_REFUSED')
            # DNS resolution is checked once, and the actual connection uses that address, preventing rebinding.
            address = addresses[0][4][0]
            connection = http.client.HTTPConnection(url.hostname, port, timeout=10)
            sock = socket.create_connection((address, port), timeout=10)
            if url.scheme == 'https':
                try:
                    sock = ssl.create_default_context().wrap_socket(sock, server_hostname=url.hostname)
                except BaseException:
                    sock.close()
                    raise
            connection.sock = sock
            def interrupt():
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
            timer = threading.Timer(10, interrupt)
            timer.daemon = True
            timer.start()
            try:
                outgoing = {'Accept': 'application/json', 'Content-Type': 'application/json'}
                if headers:
                    outgoing.update(headers)
                if self.profile.token_file:
                    with Path(self.profile.token_file).open('rb') as secret:
                        token = secret.read(16385).decode('ascii').strip()
                    if not re.fullmatch(r'[A-Za-z0-9._~+/=-]{1,16384}', token):
                        raise ProtocolError('INVALID_CREDENTIAL')
                    outgoing['Authorization'] = 'Bearer ' + token
                payload = json.dumps(body, allow_nan=False).encode() if body is not None else None
                if payload is not None and len(payload) > MAX_BYTES:
                    raise ProtocolError('REQUEST_TOO_LARGE')
                connection.request(method, (url.path or '/') + suffix, payload, outgoing)
                response = connection.getresponse()
                if not 200 <= response.status < 300:
                    problem_code = None
                    if self.error_codes:
                        try:
                            raw_error = response.read(65537)
                            parsed_error = object_json(raw_error) if len(raw_error) <= 65536 else {}
                            candidate = parsed_error.get('code')
                            if isinstance(candidate, str) and re.fullmatch(r'[A-Z][A-Z0-9_]{0,63}', candidate):
                                problem_code = candidate
                        except ProtocolError:
                            pass
                    raise ProtocolError('REMOTE_HTTP_' + str(response.status), response.status in (408, 429) or response.status >= 500,
                                        response.status, problem_code)
                raw = response.read(MAX_BYTES + 1)
                if len(raw) > MAX_BYTES:
                    raise ProtocolError('RESPONSE_TOO_LARGE')
                result_headers = {key.lower(): value for key, value in response.getheaders()}
                content_type = result_headers.get('content-type', '').split(';')[0].strip().lower()
                if not raw and response.status in (202, 204):
                    return {}, result_headers
                if content_type == 'text/event-stream' and body and 'id' in body:
                    matches = []
                    for event in re.split(r'\r?\n\r?\n', raw.decode('utf-8')):
                        data = '\n'.join(line[5:].lstrip(' ') for line in event.splitlines() if line.startswith('data:'))
                        if not data:
                            continue
                        message = object_json(data)
                        if message.get('id') == body['id'] and ('result' in message or 'error' in message):
                            matches.append(message)
                        elif 'id' in message and 'method' in message:
                            raise ProtocolError('UNSUPPORTED_SERVER_REQUEST')
                    if len(matches) != 1:
                        raise ProtocolError('INVALID_SSE_REPLY')
                    return matches[0], result_headers
                if content_type != 'application/json' and not content_type.endswith('+json'):
                    raise ProtocolError('INVALID_CONTENT_TYPE')
                return object_json(raw), result_headers
            finally:
                timer.cancel()
        except ProtocolError:
            raise
        except (OSError, UnicodeError, ValueError, http.client.HTTPException):
            raise ProtocolError('TRANSPORT_UNAVAILABLE', True) from None
        finally:
            if connection is not None:
                connection.close()


@dataclass
class Progress:
    state: str  # pending, completed, failed, input_required
    remote: dict = field(default_factory=dict, repr=False)
    output: dict | None = field(default=None, repr=False)
    poll_seconds: float = 5
    cancelled: bool = False


class NativeClient:
    def __init__(self, profile: Profile, transport=None):
        self.profile, self.http = profile, transport or Transport(profile)

    def rpc(self, method, params, state=None, notification=False):
        headers = {}
        if self.profile.protocol.startswith('mcp'):
            headers = {'Accept': 'application/json, text/event-stream', 'MCP-Protocol-Version': '2025-11-25'}
            if state and state.get('session'):
                headers['Mcp-Session-Id'] = state['session']
        else:
            headers['A2A-Version'] = '1.0'
        body = {'jsonrpc': '2.0', 'method': method, 'params': params}
        if not notification:
            body['id'] = str(uuid.uuid4())
        reply, reply_headers = self.http.exchange('POST', body, headers)
        if state and state.get('session') and reply_headers.get('mcp-session-id', state['session']) != state['session']:
            raise ProtocolError('SESSION_ID_CHANGED')
        if notification:
            return {}, reply_headers
        if (reply.get('jsonrpc') != '2.0' or reply.get('id') != body['id'] or 'error' in reply
                or not isinstance(reply.get('result'), dict)):
            raise ProtocolError('RPC_REFUSED_OR_INVALID')
        return reply['result'], reply_headers

    def prepare(self) -> dict:
        if not self.profile.protocol.startswith('mcp'):
            return {}
        reply, headers = self.rpc('initialize', {'protocolVersion': '2025-11-25', 'capabilities': {},
                'clientInfo': {'name': 'orqix-native-gateway', 'version': '1.0'}})
        if reply.get('protocolVersion') != '2025-11-25' or not isinstance(member(reply, 'capabilities', 'tasks', 'requests', 'tools', 'call'), dict):
            raise ProtocolError('MCP_TASK_CAPABILITY_REQUIRED')
        state = {'cancel_supported': isinstance(member(reply, 'capabilities', 'tasks', 'cancel'), dict)}
        if headers.get('mcp-session-id'):
            state['session'] = opaque(headers['mcp-session-id'])
        self.rpc('notifications/initialized', {}, state, notification=True)
        cursor, seen = None, set()
        for _ in range(5):
            tools, _ = self.rpc('tools/list', {'cursor': cursor} if cursor else {}, state)
            if not isinstance(tools.get('tools'), list):
                raise ProtocolError('INVALID_TOOL_LIST')
            for tool in tools['tools']:
                if isinstance(tool, dict) and tool.get('name') == self.profile.tool:
                    if member(tool, 'execution', 'taskSupport') not in ('optional', 'required'):
                        raise ProtocolError('MCP_TOOL_TASK_SUPPORT_REQUIRED')
                    if member(tool, 'annotations', 'readOnlyHint') is not True:
                        raise ProtocolError('MCP_READ_ONLY_TOOL_REQUIRED')
                    return state
            cursor = tools.get('nextCursor')
            if not cursor:
                break
            if not isinstance(cursor, str) or len(cursor) > 1024 or cursor in seen:
                raise ProtocolError('INVALID_TOOL_CURSOR')
            seen.add(cursor)
        raise ProtocolError('MCP_TOOL_NOT_FOUND')

    def submit(self, inputs: dict, dispatch_key: str, prepared: dict) -> Progress:
        if self.profile.protocol.startswith('mcp'):
            result, _ = self.rpc('tools/call', {'name': self.profile.tool, 'arguments': inputs,
                    'task': {'ttl': self.profile.timeout_seconds * 1000}, '_meta': {'io.orqix/dispatch-id': dispatch_key}}, prepared)
            task = result.get('task')
            if not isinstance(task, dict):
                raise ProtocolError('MCP_TASK_REQUIRED')
            remote = {**prepared, 'id': opaque(task.get('taskId'))}
            # Only tasks/result is an authoritative tool result, even when submit says completed.
            return Progress('pending', remote, poll_seconds=self.interval(task))
        if self.profile.protocol == 'a2a-1.0':
            result, _ = self.rpc('SendMessage', {'message': {'messageId': dispatch_key, 'role': 'ROLE_USER',
                    'parts': [{'data': inputs}]}, 'configuration': {'returnImmediately': True, 'acceptedOutputModes': ['application/json']}})
            if ('message' in result) == ('task' in result):
                raise ProtocolError('INVALID_A2A_UNION')
            if 'message' in result:
                return Progress('completed', output=self.parts(member(result['message'], 'parts')))
            return self.a2a_task(result['task'])
        result, _ = self.http.exchange('POST', {'model': self.profile.model, 'input': 'Read this JSON input and return one JSON object: ' + json.dumps(inputs, allow_nan=False),
                'background': True, 'store': True, 'tools': [], 'max_output_tokens': self.profile.max_output_tokens,
                'instructions': self.profile.instructions + '\nReturn JSON with values at these JSON Pointers: ' + json.dumps(list(self.profile.result_mapping.values())),
                'metadata': {'orqix_dispatch': dispatch_key}, 'text': {'format': {'type': 'json_object'}}})
        return self.response(result)

    def interval(self, task):
        interval = task.get('pollInterval', self.profile.poll_seconds * 1000)
        if type(interval) not in (float, int) or not math.isfinite(interval) or not 0 <= interval <= 3600000:
            raise ProtocolError('INVALID_POLL_INTERVAL')
        return max(self.profile.poll_seconds, interval / 1000)

    def poll(self, remote: dict) -> Progress:
        remote_id = opaque(remote.get('id'))
        if self.profile.protocol.startswith('mcp'):
            task, _ = self.rpc('tasks/get', {'taskId': remote_id}, remote)
            if task.get('taskId') != remote_id:
                raise ProtocolError('TASK_ID_CHANGED')
            status = task.get('status')
            if status == 'completed':
                result, _ = self.rpc('tasks/result', {'taskId': remote_id}, remote)
                related = member(result, '_meta', 'io.modelcontextprotocol/related-task', 'taskId')
                if related is not None and related != remote_id:
                    raise ProtocolError('TASK_ID_CHANGED')
                if result.get('isError') is True:
                    return Progress('failed', remote)
                output = result.get('structuredContent')
                if not isinstance(output, dict):
                    output = self.parts(result.get('content'), mcp=True)
                return Progress('completed', remote, output)
            if status in ('failed', 'cancelled'):
                return Progress('failed', remote, cancelled=status == 'cancelled')
            if status == 'input_required':
                return Progress('input_required', remote)
            if status != 'working':
                raise ProtocolError('INVALID_TASK_STATE')
            return Progress('pending', remote, poll_seconds=self.interval(task))
        if self.profile.protocol == 'a2a-1.0':
            task, _ = self.rpc('GetTask', {'id': remote_id, 'historyLength': 0})
            if task.get('id') != remote_id:
                raise ProtocolError('TASK_ID_CHANGED')
            if remote.get('context') and task.get('contextId') != remote['context']:
                raise ProtocolError('CONTEXT_ID_CHANGED')
            return self.a2a_task(task)
        result, _ = self.http.exchange('GET', suffix='/' + quote(remote_id, safe=''))
        if result.get('id') != remote_id:
            raise ProtocolError('TASK_ID_CHANGED')
        return self.response(result)

    def cancel(self, remote: dict):
        remote_id = opaque(remote.get('id'))
        if self.profile.protocol.startswith('mcp'):
            if remote.get('cancel_supported') is not True:
                raise ProtocolError('MCP_CANCEL_NOT_SUPPORTED')
            return self.rpc('tasks/cancel', {'taskId': remote_id}, remote)[0]
        if self.profile.protocol == 'a2a-1.0':
            return self.rpc('CancelTask', {'id': remote_id})[0]
        return self.http.exchange('POST', {}, suffix='/' + quote(remote_id, safe='') + '/cancel')[0]

    def a2a_task(self, task):
        if not isinstance(task, dict):
            raise ProtocolError('INVALID_TASK')
        remote = {'id': opaque(task.get('id')), 'context': opaque(task.get('contextId'))}
        status = member(task, 'status', 'state')
        if status == 'TASK_STATE_COMPLETED':
            artifacts = task.get('artifacts')
            if not isinstance(artifacts, list) or len(artifacts) != 1:
                raise ProtocolError('ONE_INLINE_ARTIFACT_REQUIRED')
            return Progress('completed', remote, self.parts(member(artifacts[0], 'parts')))
        if status in ('TASK_STATE_FAILED', 'TASK_STATE_CANCELED', 'TASK_STATE_REJECTED'):
            return Progress('failed', remote, cancelled=status == 'TASK_STATE_CANCELED')
        if status in ('TASK_STATE_INPUT_REQUIRED', 'TASK_STATE_AUTH_REQUIRED'):
            return Progress('input_required', remote)
        if status not in ('TASK_STATE_SUBMITTED', 'TASK_STATE_WORKING'):
            raise ProtocolError('INVALID_TASK_STATE')
        return Progress('pending', remote, poll_seconds=self.profile.poll_seconds)

    def response(self, result):
        remote = {'id': opaque(result.get('id'))}
        if not re.fullmatch(r'resp_[A-Za-z0-9_-]{1,200}', remote['id']):
            raise ProtocolError('INVALID_RESPONSE_ID')
        status = result.get('status')
        if status in ('queued', 'in_progress'):
            return Progress('pending', remote, poll_seconds=self.profile.poll_seconds)
        if status in ('failed', 'cancelled', 'incomplete'):
            return Progress('failed', remote, cancelled=status == 'cancelled')
        if status != 'completed':
            raise ProtocolError('INVALID_RESPONSE_STATE')
        output = result.get('output')
        if not isinstance(output, list) or any(not isinstance(item, dict) for item in output):
            raise ProtocolError('INVALID_RESPONSE_OUTPUT')
        messages = [item for item in output if item.get('type') == 'message']
        if len(messages) != 1:
            raise ProtocolError('ONE_OUTPUT_MESSAGE_REQUIRED')
        content = messages[0].get('content', [])
        if not isinstance(content, list) or not content or any(member(part, 'type') != 'output_text' or not isinstance(member(part, 'text'), str) for part in content):
            raise ProtocolError('TEXT_OUTPUT_REQUIRED')
        return Progress('completed', remote, object_json(''.join(part['text'] for part in content)))

    @staticmethod
    def parts(parts, mcp=False):
        if not isinstance(parts, list) or len(parts) != 1 or not isinstance(parts[0], dict):
            raise ProtocolError('ONE_INLINE_PART_REQUIRED')
        part = parts[0]
        if not mcp and isinstance(part.get('data'), dict) and not any(k in part for k in ('text', 'url', 'raw')):
            return part['data']
        if isinstance(part.get('text'), str) and (not mcp or part.get('type') == 'text') and not any(k in part for k in ('data', 'url', 'raw')):
            return object_json(part['text'])
        raise ProtocolError('INLINE_JSON_REQUIRED')


def map_result(profile: Profile, output: dict) -> dict:
    mapped = {}
    for name, pointer in profile.result_mapping.items():
        value = output
        for segment in pointer[1:].split('/'):
            key = segment.replace('~1', '/').replace('~0', '~')
            if not isinstance(value, dict) or key not in value:
                raise ProtocolError('RESULT_MAPPING_MISSING')
            value = value[key]
        if type(value) not in (str, int, float, bool) or isinstance(value, float) and not math.isfinite(value):
            raise ProtocolError('SCALAR_RESULT_REQUIRED')
        mapped[name] = value
    if len(json.dumps(mapped, allow_nan=False).encode()) > 32768:
        raise ProtocolError('RESULT_TOO_LARGE')
    return mapped
