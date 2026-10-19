"""Native wire shapes and transport boundaries, using scripted peers and loopback HTTP only."""
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, str(Path(__file__).parent))
from orqix_protocols import NativeClient, Profile, ProtocolError, Transport, map_result, object_json


def profile(protocol='mcp-2025-11-25', **kwargs):
    base = dict(tool='lookup') if protocol.startswith('mcp') else {}
    if protocol == 'responses-v1':
        base = dict(model='configured-model', token_file='mounted-token')
    base.update(kwargs)
    return Profile(protocol, 'https://peer.example/rpc', {'score': '/score'}, **base)


class Script:
    def __init__(self, answers):
        self.answers, self.calls = list(answers), []

    def exchange(self, method, body=None, headers=None, suffix=''):
        self.calls.append((method, body, headers, suffix))
        result = self.answers.pop(0)
        if isinstance(result, Exception):
            raise result
        value, returned_headers = result if isinstance(result, tuple) else (result, {})
        if body and 'method' in body and 'id' in body:
            return {'jsonrpc': '2.0', 'id': body['id'], 'result': value}, returned_headers
        return value, returned_headers


class NativeTest(unittest.TestCase):
    def mcp_prepared(self, **changes):
        task_support = changes.get('support', 'required')
        transport = Script([
            ({'protocolVersion': changes.get('version', '2025-11-25'), 'capabilities': {'tasks': {'requests': {'tools': {'call': {}}}}}}, {'mcp-session-id': 'session-1'}),
            {}, {'tools': [{'name': 'lookup', 'annotations': {'readOnlyHint': changes.get('read_only', True)}, 'execution': {'taskSupport': task_support}}]},
        ])
        return NativeClient(profile(), transport), transport

    def test_mcp_negotiates_then_polls_and_retrieves_structured_result(self):
        client, wire = self.mcp_prepared()
        prepared = client.prepare()
        self.assertEqual(prepared, {'cancel_supported': False, 'session': 'session-1'})
        wire.answers.extend([{'task': {'taskId': 'task-1', 'status': 'working', 'pollInterval': 12000}},
                             {'taskId': 'task-1', 'status': 'working', 'pollInterval': 20000},
                             {'taskId': 'task-1', 'status': 'completed'},
                             {'structuredContent': {'score': 0.75}, '_meta': {'io.modelcontextprotocol/related-task': {'taskId': 'task-1'}}}])
        pending = client.submit({'address': 'Private input'}, 'dispatch-key', prepared)
        self.assertEqual(pending.poll_seconds, 12)
        self.assertEqual(client.poll(pending.remote).poll_seconds, 20)
        done = client.poll(pending.remote)
        self.assertEqual(map_result(profile(), done.output), {'score': 0.75})
        submit = next(call for call in wire.calls if call[1].get('method') == 'tools/call')
        self.assertEqual(submit[1]['params']['task'], {'ttl': 3600000})
        self.assertEqual(submit[2]['Mcp-Session-Id'], 'session-1')
        self.assertEqual(submit[2]['MCP-Protocol-Version'], '2025-11-25')

    def test_mcp_refuses_missing_task_support_and_write_tools(self):
        for changes in ({'support': 'forbidden'}, {'support': None}, {'read_only': False}, {'version': '2025-06-18'}):
            with self.subTest(changes=changes):
                client, _ = self.mcp_prepared(**changes)
                with self.assertRaises(ProtocolError):
                    client.prepare()

    def test_mcp_input_required_failure_and_remote_identity_are_not_success(self):
        for task, expected in (({'taskId': 't', 'status': 'input_required'}, 'input_required'),
                               ({'taskId': 't', 'status': 'failed'}, 'failed'),
                               ({'taskId': 't', 'status': 'cancelled'}, 'failed')):
            client = NativeClient(profile(), Script([task]))
            self.assertEqual(client.poll({'id': 't'}).state, expected)
        for task in ({'taskId': 'other', 'status': 'completed'}, {'taskId': 't', 'status': 'invented'}):
            with self.assertRaises(ProtocolError):
                NativeClient(profile(), Script([task])).poll({'id': 't'})

    def test_mcp_text_result_and_cancellation(self):
        wire = Script([{'taskId': 't', 'status': 'completed'}, {'content': [{'type': 'text', 'text': '{"score":1}'}]}, {'taskId': 't', 'status': 'cancelled'}])
        client = NativeClient(profile(), wire)
        self.assertEqual(client.poll({'id': 't'}).output, {'score': 1})
        client.cancel({'id': 't', 'cancel_supported': True})
        self.assertEqual(wire.calls[-1][1]['method'], 'tasks/cancel')

    def test_a2a_v1_submission_polling_context_and_inline_artifact(self):
        working = {'id': 'a', 'contextId': 'context', 'status': {'state': 'TASK_STATE_WORKING'}}
        complete = {**working, 'status': {'state': 'TASK_STATE_COMPLETED'}, 'artifacts': [{'parts': [{'data': {'score': 5}}]}]}
        wire = Script([{'task': working}, complete])
        client = NativeClient(profile('a2a-1.0'), wire)
        pending = client.submit({'question': 'private'}, 'stable-message-id', {})
        self.assertEqual(client.poll(pending.remote).output, {'score': 5})
        request = wire.calls[0]
        self.assertEqual(request[2]['A2A-Version'], '1.0')
        self.assertEqual(request[1]['method'], 'SendMessage')
        self.assertTrue(request[1]['params']['configuration']['returnImmediately'])
        self.assertEqual(request[1]['params']['message']['role'], 'ROLE_USER')
        self.assertEqual(request[1]['params']['message']['messageId'], 'stable-message-id')
        self.assertEqual(wire.calls[1][1]['method'], 'GetTask')
        self.assertEqual(wire.calls[1][1]['params']['historyLength'], 0)
        changed = {**working, 'contextId': 'other'}
        with self.assertRaisesRegex(ProtocolError, 'CONTEXT_ID_CHANGED'):
            NativeClient(profile('a2a-1.0'), Script([changed])).poll(pending.remote)

    def test_a2a_direct_response_auth_required_and_file_refusal(self):
        client = NativeClient(profile('a2a-1.0'), Script([{'message': {'parts': [{'text': '{"score":2}'}]}}]))
        self.assertEqual(client.submit({}, 'msg', {}).output, {'score': 2})
        for state in ('TASK_STATE_INPUT_REQUIRED', 'TASK_STATE_AUTH_REQUIRED'):
            task = {'id': 'a', 'contextId': 'c', 'status': {'state': state}}
            self.assertEqual(NativeClient(profile('a2a-1.0')).a2a_task(task).state, 'input_required')
        with self.assertRaises(ProtocolError):
            NativeClient.parts([{'url': 'https://untrusted.invalid/file'}])

    def test_a2a_cancel_and_mcp_cancel_capability_guard(self):
        wire = Script([{'id': 'a', 'contextId': 'c', 'status': {'state': 'TASK_STATE_CANCELED'}}])
        NativeClient(profile('a2a-1.0'), wire).cancel({'id': 'a', 'context': 'c'})
        self.assertEqual(wire.calls[0][1]['method'], 'CancelTask')
        self.assertEqual(wire.calls[0][1]['params'], {'id': 'a'})
        self.assertEqual(wire.calls[0][2]['A2A-Version'], '1.0')
        blocked = Script([])
        with self.assertRaisesRegex(ProtocolError, 'MCP_CANCEL_NOT_SUPPORTED'):
            NativeClient(profile(), blocked).cancel({'id': 't'})
        self.assertEqual(blocked.calls, [])

    def test_invalid_deployment_references_are_refused(self):
        for options in ({'endpoint': 42}, {'token_file': 42}, {'result_mapping': {1: '/score'}}):
            args = dict(protocol='a2a-1.0', endpoint='https://peer.example/rpc', result_mapping={'score': '/score'})
            args.update(options)
            with self.assertRaises(ProtocolError):
                Profile(**args)

    def test_responses_background_submit_poll_json_and_cancel(self):
        wire = Script([{'id': 'resp_1', 'status': 'queued'}, {'id': 'resp_1', 'status': 'in_progress'},
                       {'id': 'resp_1', 'status': 'completed', 'output': [{'type': 'message', 'content': [{'type': 'output_text', 'text': '{"score":3}'}]}]},
                       {'id': 'resp_1', 'status': 'cancelled'}])
        client = NativeClient(profile('responses-v1'), wire)
        pending = client.submit({'question': 'private'}, 'dispatch', {})
        self.assertEqual(client.poll(pending.remote).state, 'pending')
        self.assertEqual(client.poll(pending.remote).output, {'score': 3})
        self.assertTrue(wire.calls[0][1]['background'])
        self.assertEqual(wire.calls[0][1]['tools'], [])
        self.assertIn('JSON', wire.calls[0][1]['input'])
        client.cancel(pending.remote)
        self.assertEqual(wire.calls[-1][3], '/resp_1/cancel')

    def test_invalid_responses_and_precision_free_scalar_mapping(self):
        for status in ('failed', 'incomplete', 'cancelled'):
            self.assertEqual(NativeClient(profile('responses-v1')).response({'id': 'resp_1', 'status': status}).state, 'failed')
        with self.assertRaises(ProtocolError):
            NativeClient(profile('responses-v1')).response({'id': '../admin', 'status': 'queued'})
        self.assertEqual(map_result(profile(), {'score': False}), {'score': False})
        for output in ({}, {'score': {}}, {'score': None}, {'score': float('nan')}):
            with self.assertRaises(ProtocolError):
                map_result(profile(), output)
        with self.assertRaises(ProtocolError):
            object_json('{"score":1,"score":2}')

    def test_malformed_peer_shapes_fail_as_protocol_errors(self):
        for value in (None, [], 'invalid', 1):
            with self.subTest(value=value):
                with self.assertRaises(ProtocolError):
                    NativeClient(profile(), Script([{'protocolVersion': '2025-11-25', 'capabilities': value}])).prepare()
                with self.assertRaises(ProtocolError):
                    NativeClient(profile('a2a-1.0')).a2a_task({'id': 'a', 'contextId': 'c', 'status': value})
                with self.assertRaises(ProtocolError):
                    NativeClient(profile('responses-v1')).response({'id': 'resp_1', 'status': 'completed', 'output': value})



class TransportTest(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.kind = 'ok'
        outer = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def do_POST(self):
                body = object_json(self.rfile.read(int(self.headers['Content-Length'])))
                outer.calls.append((self.path, self.headers.get('Authorization')))
                if outer.kind == 'redirect':
                    self.send_response(302); self.send_header('Location', '/stolen'); self.end_headers(); return
                if outer.kind == 'large':
                    raw, media = b' ' * (1024 * 1024 + 1), 'application/json'
                elif outer.kind == 'sse':
                    raw = ('event: message\ndata: ' + json.dumps({'jsonrpc': '2.0', 'id': body['id'], 'result': {'ok': True}}) + '\n\n').encode()
                    media = 'text/event-stream'
                else:
                    raw, media = b'{"ok":true}', 'application/json'
                self.send_response(200); self.send_header('Content-Type', media); self.send_header('Content-Length', str(len(raw))); self.end_headers()
                self.wfile.write(raw)
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()
        self.temp = tempfile.TemporaryDirectory()
        self.secret = Path(self.temp.name, 'token'); self.secret.write_text('private-token')
        self.endpoint = f'http://127.0.0.1:{self.server.server_port}/rpc'

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join(); self.temp.cleanup()

    def transport(self):
        return Transport(Profile('a2a-1.0', self.endpoint, {'score': '/score'}, token_file=str(self.secret), allow_private=True, allow_http=True))

    def test_real_http_auth_rotation_and_redirect_refusal(self):
        self.transport().exchange('POST', {})
        self.secret.write_text('rotated-token')
        self.transport().exchange('POST', {})
        self.assertEqual([call[1] for call in self.calls], ['Bearer private-token', 'Bearer rotated-token'])
        self.kind = 'redirect'
        with self.assertRaisesRegex(ProtocolError, 'REMOTE_HTTP_302'):
            self.transport().exchange('POST', {})
        self.assertEqual(len(self.calls), 3)  # no request to /stolen

    def test_response_cap_and_sse(self):
        self.kind = 'large'
        with self.assertRaisesRegex(ProtocolError, 'RESPONSE_TOO_LARGE'):
            self.transport().exchange('POST', {})
        self.kind = 'sse'
        result, _ = self.transport().exchange('POST', {'id': 'request'})
        self.assertTrue(result['result']['ok'])

    def test_private_dns_and_unapproved_http_are_refused(self):
        with self.assertRaises(ProtocolError):
            Profile('a2a-1.0', self.endpoint, {'score': '/score'})
        blocked = Profile('a2a-1.0', self.endpoint.replace('http:', 'https:'), {'score': '/score'})
        with self.assertRaisesRegex(ProtocolError, 'EGRESS_REFUSED'):
            Transport(blocked).exchange('POST', {})
        self.assertEqual(self.calls, [])


if __name__ == '__main__':
    unittest.main()
