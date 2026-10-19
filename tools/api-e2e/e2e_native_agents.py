"""Local real-API acceptance: native MCP tasks, A2A 1.0 and Responses background jobs.

Uses loopback provider fixtures, real Keycloak machine authentication, PostgreSQL/Flowable and the production
gateway. No live model calls. Temporarily grants job.work to the dev machine; revokes that exact grant in finally.
Creates three uniquely named sandbox workflows/runs and preserves them as acceptance evidence.
"""
from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

import requests
from orqix_client import BASE, Client, REALM_FILE

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'worker-sdk'))
from orqix_agent_gateway import Gateway, GatewayWorker
from orqix_worker import ApiError
from orqix_protocols import Profile


class AcceptanceFailure(RuntimeError):
    pass


def require(response, code=200):
    if response.status_code != code:
        # No response bodies: those may contain submitted data or authentication details.
        raise AcceptanceFailure('Unexpected local API status ' + str(response.status_code) + ' at ' + urlsplit(response.url).path)
    return response.json() if response.content else None


class Peer:
    def __init__(self):
        self.submits = {'mcp': 0, 'a2a': 0, 'responses': 0}
        self.polls = dict.fromkeys(self.submits, 0)
        outer = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def reply(self, body, status=200, session=False):
                raw = json.dumps(body).encode() if body is not None else b''
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(raw)))
                if session:
                    self.send_header('Mcp-Session-Id', 'local-native-session')
                self.end_headers(); self.wfile.write(raw)
            def do_GET(self):
                if self.headers.get('Authorization') != 'Bearer local-provider-token':
                    return self.reply({}, 401)
                outer.polls['responses'] += 1
                state = 'in_progress' if outer.polls['responses'] == 1 else 'completed'
                return self.reply({'id': 'resp_native', 'status': state, 'output': [{'type': 'message', 'content': [{'type': 'output_text', 'text': '{"score":7.5}'}]}]})
            def do_POST(self):
                if self.headers.get('Authorization') != 'Bearer local-provider-token':
                    return self.reply({}, 401)
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                if self.path == '/responses':
                    assert body['background'] is True and body['tools'] == []
                    outer.submits['responses'] += 1
                    return self.reply({'id': 'resp_native', 'status': 'queued'})
                method = body['method']
                if method == 'notifications/initialized':
                    return self.reply(None, 202)
                if method == 'initialize':
                    result = {'protocolVersion': '2025-11-25', 'capabilities': {'tasks': {'requests': {'tools': {'call': {}}}}}, 'serverInfo': {'name': 'local-native-peer', 'version': '1'}}
                elif method == 'tools/list':
                    result = {'tools': [{'name': 'lookup', 'inputSchema': {'type': 'object'}, 'annotations': {'readOnlyHint': True}, 'execution': {'taskSupport': 'required'}}]}
                elif method == 'tools/call':
                    assert body['params']['task']['ttl'] == 60000
                    outer.submits['mcp'] += 1
                    result = {'task': {'taskId': 'mcp-task', 'status': 'working', 'pollInterval': 1000}}
                elif method == 'tasks/get':
                    outer.polls['mcp'] += 1
                    result = {'taskId': 'mcp-task', 'status': 'working' if outer.polls['mcp'] == 1 else 'completed', 'pollInterval': 1000}
                elif method == 'tasks/result':
                    result = {'structuredContent': {'score': 7.5}, 'content': [], 'isError': False}
                elif method in ('SendMessage', 'GetTask'):
                    assert self.headers.get('A2A-Version') == '1.0'
                    if method == 'SendMessage':
                        assert body['params']['configuration']['returnImmediately'] is True
                        outer.submits['a2a'] += 1
                        state = 'TASK_STATE_WORKING'
                    else:
                        outer.polls['a2a'] += 1
                        state = 'TASK_STATE_WORKING' if outer.polls['a2a'] == 1 else 'TASK_STATE_COMPLETED'
                    result = {'id': 'a2a-task', 'contextId': 'a2a-context', 'status': {'state': state}, 'artifacts': [{'parts': [{'data': {'score': 7.5}}]}]}
                    if method == 'SendMessage':
                        result = {'task': result}
                else:
                    return self.reply({}, 400)
                self.reply({'jsonrpc': '2.0', 'id': body['id'], 'result': result}, session=method == 'initialize')
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()

    def close(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()


def main():
    identity_url = os.environ.get('ORQIX_KEYCLOAK_URL', 'http://localhost:8180')
    for url in (BASE, identity_url):
        if urlsplit(url).hostname not in ('localhost', '127.0.0.1'):
            raise RuntimeError('This acceptance script is for the local dev realm only')
    admin, designer, requester = (Client(user) for user in ('wade.admin', 'dana.designer', 'rhea.requester'))
    ws = next(item['id'] for item in require(designer.get('/me'))['workspaces'] if item['key'] == 'purchasing-sandbox')
    realm = json.loads(Path(REALM_FILE).read_text(encoding='utf-8'))
    machine = next(item for item in realm['clients'] if item['clientId'] == 'orqix-machine-demo')
    token = require(requests.post(identity_url + '/realms/orqix-dev/protocol/openid-connect/token',
                    data={'grant_type': 'client_credentials', 'client_id': machine['clientId'], 'client_secret': machine['secret']}, timeout=15))['access_token']
    claims = json.loads(base64.urlsafe_b64decode(token.split('.')[1] + '==='))
    external_id = str(uuid.UUID(claims['sub']))
    with tempfile.TemporaryDirectory() as temp:
        token_path, provider_path = Path(temp, 'orqix-token'), Path(temp, 'provider-token')
        for path, value in ((token_path, token), (provider_path, 'local-provider-token')):
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, 'w') as stream:
                stream.write(value)
        worker = GatewayWorker(BASE + '/api/v1', ws, str(token_path), 'native-acceptance', allow_private=True, allow_http=True)
        try:
            worker.fetch_and_lock(['native-probe'])  # materializes the authenticated subject even when authorization denies
        except ApiError as probe:
            if probe.status not in (403, 404):
                raise
        container = os.environ.get('ORQIX_TEST_POSTGRES_CONTAINER', 'orqix-postgres-1')
        subject_ids = subprocess.check_output(['docker', 'exec', container, 'psql', '-U', 'postgres', '-d', 'orqix', '-Atc',
                   "SELECT id FROM orqix.subject WHERE external_subject='" + external_id + "'"], text=True).strip().splitlines()
        if len(subject_ids) != 1:
            raise RuntimeError('Cannot identify the local machine subject')
        subject = str(uuid.UUID(subject_ids[0]))
        grant = require(admin.post(f'/workspaces/{ws}/access/grants', {'permission': 'job.work', 'principal': {'type': 'subject', 'value': subject}}), 201)
        peer = Peer()
        try:
            for protocol, name, endpoint, extra in (
                    ('mcp-2025-11-25', 'mcp', '/mcp', {'tool': 'lookup'}),
                    ('a2a-1.0', 'a2a', '/a2a', {}),
                    ('responses-v1', 'responses', '/responses', {'model': 'local-model'})):
                key = 'native-' + name + '-' + uuid.uuid4().hex[:8]
                created = require(designer.post(f'/workspaces/{ws}/workflows', {'key': key, 'name': key, 'templateId': 'purchase-approval'}), 201)
                wf = created['id']
                draft = require(designer.get(f'/workspaces/{ws}/workflows/{wf}/draft'))
                definition = draft['definition']
                start = next(node for node in definition['nodes'] if node['type'] == 'start')
                end = next(node for node in definition['nodes'] if node['type'] == 'end' and node['config']['outcomeKind'] == 'success')
                definition['nodes'] = [start, {'id': 'native', 'type': 'externalTask', 'label': 'Native async agent', 'config': {
                    'topic': key, 'inputMapping': {'question': '/input/purpose'}, 'result': [{'name': 'score', 'type': 'number', 'required': True}]}}, end]
                definition['transitions'] = [{'id': 'toNative', 'from': start['id'], 'to': 'native', 'on': 'next'}, {'id': 'toEnd', 'from': 'native', 'to': end['id'], 'on': 'next'}]
                definition['outputSchema'] = {'type': 'object', 'additionalProperties': False, 'required': ['score'], 'properties': {'score': {'type': 'number', 'title': 'Native score'}}}
                definition['outputMapping'] = {'score': {'fieldRef': '/steps/native/score', 'required': True}}
                definition['uiLayout'] = {'positions': {start['id']: {'x': 0, 'y': 0}, 'native': {'x': 250, 'y': 0}, end['id']: {'x': 500, 'y': 0}}}
                changed = require(designer.put(f'/workspaces/{ws}/workflows/{wf}/draft', {'definition': definition}, if_match='"r' + str(draft['revision']) + '"'))
                revision = changed.get('revision', draft['revision'] + 1)
                validation = require(designer.post(f'/workspaces/{ws}/workflows/{wf}/validate', {'revision': revision}))
                if any(str(issue.get('severity')).lower() in ('error', 'blocker') for issue in validation.get('issues', [])):
                    raise RuntimeError('Local native workflow did not validate')
                require(designer.post(f'/workspaces/{ws}/workflows/{wf}/publish', {'draftRevision': revision, 'acknowledgeWarnings': True}, idem=True), 201)
                run = require(requester.post(f'/workspaces/{ws}/runs', {'workflowId': wf, 'input': {'title': key, 'purpose': 'Check this local fixture', 'costCenter': 'OPS', 'amount': 100}}, idem=True), 201)
                profile = Profile(protocol, f'http://127.0.0.1:{peer.server.server_port}' + endpoint, {'score': '/score'},
                                  token_file=str(provider_path), allow_private=True, allow_http=True, timeout_seconds=60, poll_seconds=1, **extra)
                database = str(Path(temp, name + '.db'))
                deadline = time.monotonic() + 40
                while time.monotonic() < deadline:
                    with Gateway(worker, database, {key: profile}) as gateway:
                        gateway.tick()
                        rows = gateway.db.execute('SELECT state FROM work').fetchall()
                        if rows:
                            break
                    time.sleep(0.5)
                else:
                    raise RuntimeError('No native job was offered')
                # A new gateway instance reads the same durable database after remote submission.
                with Gateway(worker, database, {key: profile}) as gateway:
                    while time.monotonic() < deadline:
                        gateway.tick()
                        if gateway.db.execute("SELECT count(*) FROM work WHERE state='done'").fetchone()[0]:
                            break
                        time.sleep(0.5)
                    else:
                        print('Gateway diagnostic: ' + json.dumps([dict(row) for row in gateway.db.execute('SELECT state,error_code FROM work')]))
                        print('Peer counts: ' + json.dumps({'submits': peer.submits, 'polls': peer.polls}))
                        raise RuntimeError('Native gateway did not deliver the result')
                while time.monotonic() < deadline:
                    current = require(requester.get(f'/workspaces/{ws}/runs/{run["id"]}'))
                    if current.get('lifecycle') == 'completed':
                        break
                    time.sleep(0.5)
                else:
                    raise RuntimeError('Flowable run did not complete')
                if current.get('outcomeKind') != 'success' or current.get('output') != {'score': 7.5}:
                    raise RuntimeError('Native workflow result did not match its typed output')
                if peer.submits[name] != 1:
                    raise RuntimeError('Native job was dispatched more than once')
                print('PASS: ' + protocol + ' authenticated handoff, gateway restart, native polling and real workflow completion')
        finally:
            peer.close()
            response = admin.s.delete(BASE + f'/api/v1/workspaces/{ws}/access/grants/{grant["id"]}', headers=admin._headers(), timeout=15)
            if response.status_code != 204:
                raise RuntimeError('Temporary worker grant cleanup failed')
    print('PASS: temporary machine worker grant revoked')


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        import traceback
        frame = traceback.extract_tb(failure.__traceback__)[-1]
        print('Native acceptance failed: ' + (str(failure) if isinstance(failure, AcceptanceFailure) else type(failure).__name__) + ' at ' + Path(frame.filename).name + ':' + str(frame.lineno))
        raise SystemExit(1) from None
