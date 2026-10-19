"""Durability/uncertainty boundary tests. No billable provider calls or third-party messages."""
from dataclasses import asdict
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parent))
from orqix_worker import ApiError, CallbackTicket, Job
from orqix_protocols import Profile, Progress, ProtocolError
from orqix_agent_gateway import Gateway, GatewayWorker


class FakeWorker:
    _base = 'https://orqix.example/api/v1'
    _ws = '00000000-0000-4000-8000-000000000001'
    worker_id = 'native-gateway'

    def __init__(self):
        self.deliveries, self.failures, self.jobs = [], [], []
        self.registration_calls = 0
        self.lose_reply = False
        self.delivery_error = None

    def await_callback(self, job, timeout):
        self.registration_calls += 1
        return CallbackTicket(job.id, 'callback-1', '2099-01-01T00:00:00Z')

    def complete_callback(self, ticket, result):
        if self.delivery_error:
            raise self.delivery_error
        if (ticket, result) not in self.deliveries:
            self.deliveries.append((ticket, result))
        if self.lose_reply:
            self.lose_reply = False
            raise OSError('private response detail')

    def fail_callback(self, ticket, message):
        self.failures.append((ticket, message))

    def fetch_and_lock(self, topics):
        jobs, self.jobs = self.jobs, []
        return jobs


class Client:
    def __init__(self):
        self.submissions, self.polls, self.preparations = 0, 0, 0
        self.submit_error = None
        self.poll_error = None
        self.pending = True
        self.cancel_calls = 0
        self.cancelled = False

    def prepare(self):
        self.preparations += 1
        return {'session': 'private-session'}

    def submit(self, inputs, dispatch_key, prepared):
        self.submissions += 1
        if self.submit_error:
            raise self.submit_error
        return Progress('pending', {'id': 'remote-task', **prepared}, poll_seconds=1)

    def cancel(self, remote):
        self.cancel_calls += 1
        self.cancelled = True

    def poll(self, remote):
        self.polls += 1
        if self.cancelled:
            return Progress('failed', remote, cancelled=True)
        if self.poll_error:
            raise self.poll_error
        return Progress('pending', remote, poll_seconds=1) if self.pending else Progress('completed', remote, {'score': 2})


class GatewayTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.database = str(Path(self.temp.name, 'outbox.db'))
        self.worker, self.client = FakeWorker(), Client()
        self.now = 1000
        self.profiles = {'native-agent': Profile('mcp-2025-11-25', 'https://provider.example/mcp', {'score': '/score'}, tool='lookup')}
        self.gateway = self.open()
        self.job = Job('job-1', 'native-agent', {'question': 'Private question'}, 3, '2099-01-01T00:00:00Z', 1, 3, 'ORQ-1', 'check')

    def open(self):
        return Gateway(self.worker, self.database, self.profiles, client_factory=lambda _: self.client, clock=lambda: self.now)

    def tearDown(self):
        self.gateway.close(); self.temp.cleanup()

    def restart(self):
        self.gateway.close(); self.gateway = self.open()

    def test_restart_resumes_known_remote_task_without_resubmitting(self):
        key = self.gateway.accept(self.job)
        self.gateway.advance(key)
        self.assertEqual(self.gateway.row(key)['state'], 'remote')
        self.restart(); self.now += 2; self.client.pending = False
        self.gateway.advance(key)
        row = self.gateway.row(key)
        self.assertEqual(row['state'], 'done')
        self.assertEqual(self.client.submissions, 1)
        self.assertEqual(self.worker.deliveries[0][1], {'score': 2})
        self.assertIsNone(row['job']); self.assertIsNone(row['delivery']); self.assertIsNone(row['remote'])
        self.assertNotIn('private-session', row['reference'])
        self.assertEqual(json.loads(row['reference'])['id'], 'remote-task')

    def test_lost_submit_reply_causes_attention_and_is_never_retried(self):
        self.client.submit_error = OSError('Credentials and private remote body')
        key = self.gateway.accept(self.job); self.gateway.advance(key)
        self.restart(); self.gateway.advance(key)
        self.assertEqual(self.client.submissions, 1)
        self.assertEqual(self.gateway.row(key)['state'], 'attention')
        self.assertIn('REMOTE_SUBMISSION_UNCERTAIN', self.worker.failures[0][1])
        self.assertNotIn('Credentials', self.worker.failures[0][1])

    def test_crash_after_intent_does_not_resubmit(self):
        key = self.gateway.accept(self.job)
        ticket = self.worker.await_callback(self.job, 3600)
        self.gateway.change(key, state='dispatching', ticket=json.dumps(asdict(ticket)))
        self.restart(); self.gateway.advance(key)
        self.assertEqual(self.client.submissions, 0)
        self.assertEqual(self.gateway.row(key)['error_code'], 'REMOTE_SUBMISSION_UNCERTAIN')

    def test_lost_callback_reply_is_replayed_after_restart(self):
        key = self.gateway.accept(self.job); self.gateway.advance(key)
        self.now += 2; self.client.pending = False; self.worker.lose_reply = True
        self.gateway.advance(key)
        self.assertEqual(self.gateway.row(key)['state'], 'delivering')
        self.restart(); self.now += 10; self.gateway.advance(key)
        self.assertEqual(self.gateway.row(key)['state'], 'done')
        self.assertEqual(len(self.worker.deliveries), 1)
        self.assertEqual(self.client.submissions, 1)
        self.assertEqual(self.client.polls, 1)

    def test_suspension_holds_delivery_until_resume(self):
        key = self.gateway.accept(self.job); self.gateway.advance(key)
        self.now += 2; self.client.pending = False
        self.worker.delivery_error = ApiError(409, 'RUN_SUSPENDED', 'private')
        self.gateway.advance(key)
        self.assertEqual(self.gateway.row(key)['state'], 'delivering')
        self.worker.delivery_error = None; self.now += 10; self.gateway.advance(key)
        self.assertEqual(self.gateway.row(key)['state'], 'done')

    def test_revocation_stops_and_late_fence_is_abandoned(self):
        key = self.gateway.accept(self.job); self.gateway.advance(key)
        self.now += 2; self.client.pending = False
        self.worker.delivery_error = ApiError(403, 'FORBIDDEN', 'private')
        with self.assertRaises(ApiError):
            self.gateway.advance(key)
        self.assertEqual(self.gateway.row(key)['state'], 'delivering')
        self.worker.delivery_error = ApiError(409, 'JOB_LEASE_LOST', 'private')
        self.gateway.advance(key)
        self.assertEqual(self.gateway.row(key)['state'], 'orphaned')
        self.assertEqual(self.client.submissions, 1)

    def test_poll_transient_error_retries_only_the_poll(self):
        key = self.gateway.accept(self.job); self.gateway.advance(key)
        self.now += 2; self.client.poll_error = ProtocolError('TRANSPORT_UNAVAILABLE', True)
        self.gateway.advance(key)
        self.assertEqual(self.gateway.row(key)['state'], 'remote')
        self.client.poll_error = None; self.client.pending = False; self.now += 10
        self.gateway.advance(key)
        self.assertEqual(self.gateway.row(key)['state'], 'done')
        self.assertEqual(self.client.submissions, 1)

    def test_expiry_does_not_send_new_work(self):
        key = self.gateway.accept(self.job)
        ticket = CallbackTicket(self.job.id, 'callback', '1970-01-01T00:00:01Z')
        self.gateway.change(key, state='prepared', ticket=json.dumps(asdict(ticket)))
        self.gateway.advance(key)
        self.assertEqual(self.client.submissions, 0)
        self.assertEqual(self.gateway.row(key)['error_code'], 'REMOTE_DEADLINE_EXCEEDED')

    def test_configuration_change_and_cross_workspace_database_reuse_refused(self):
        key = self.gateway.accept(self.job)
        self.gateway.close()
        self.profiles['native-agent'] = Profile('mcp-2025-11-25', 'https://another.example/mcp', {'score': '/score'}, tool='lookup')
        self.gateway = self.open()
        with self.assertRaisesRegex(ProtocolError, 'PENDING_PROFILE_CHANGED'):
            self.gateway.advance(key)
        self.assertEqual(self.client.submissions, 0)
        self.gateway.close(); self.worker._ws = 'another-workspace'
        with self.assertRaisesRegex(ProtocolError, 'OUTBOX_WORKER_IDENTITY_CHANGED'):
            self.open()
        self.worker._ws = FakeWorker._ws

    def test_poll_hint_is_clamped_to_fixed_callback_deadline(self):
        key = self.gateway.accept(self.job); self.gateway.advance(key)
        ticket = CallbackTicket(self.job.id, 'callback', '1970-01-01T00:16:45Z')
        self.gateway.change(key, ticket=json.dumps(asdict(ticket)))
        self.assertEqual(self.gateway.scheduled(key, 3600), 1005)
        self.now = 1005; self.gateway.advance(key)
        self.assertEqual(self.client.polls, 0)
        self.assertEqual(self.gateway.row(key)['error_code'], 'REMOTE_DEADLINE_EXCEEDED')

    def test_rejected_typed_result_reports_failure_without_resubmission(self):
        key = self.gateway.accept(self.job); self.gateway.advance(key)
        self.now += 2; self.client.pending = False
        self.worker.delivery_error = ApiError(422, 'INVALID_RESULT', 'private')
        self.gateway.advance(key)
        self.assertEqual(self.gateway.row(key)['error_code'], 'RESULT_REJECTED')
        self.gateway.advance(key)
        self.assertEqual(self.gateway.row(key)['state'], 'attention')
        self.assertIn('RESULT_REJECTED', self.worker.failures[0][1])
        self.assertEqual(self.client.submissions, 1)

    def test_slow_protocol_negotiation_cannot_dispatch_after_deadline(self):
        key = self.gateway.accept(self.job)
        ticket = CallbackTicket(self.job.id, 'callback', '1970-01-01T00:16:45Z')
        self.gateway.change(key, state='prepared', ticket=json.dumps(asdict(ticket)))
        def slow_prepare():
            self.now = 1006
            return {}
        self.client.prepare = slow_prepare
        self.gateway.advance(key)
        self.assertEqual(self.client.submissions, 0)
        self.assertEqual(self.gateway.row(key)['state'], 'attention')
        self.assertEqual(self.gateway.row(key)['error_code'], 'REMOTE_DEADLINE_EXCEEDED')

    def test_operator_cancel_checks_remote_state_and_never_reports_success(self):
        key = self.gateway.accept(self.job); self.gateway.advance(key)
        self.gateway.cancel(key)
        self.assertEqual(self.client.cancel_calls, 1)
        self.assertEqual(self.gateway.row(key)['state'], 'attention')
        self.assertEqual(self.gateway.row(key)['error_code'], 'REMOTE_CANCELLED')
        self.assertEqual(self.worker.deliveries, [])

    def test_crashed_cancel_intent_only_polls_and_does_not_send_cancel_again(self):
        key = self.gateway.accept(self.job); self.gateway.advance(key)
        self.gateway.change(key, state='cancelling', next_poll=self.now)
        self.restart(); self.gateway.advance(key)
        self.assertEqual(self.client.cancel_calls, 0)
        self.assertEqual(self.gateway.row(key)['state'], 'cancel_check')
        self.client.pending = False; self.now += 2; self.gateway.advance(key)
        self.assertEqual(self.gateway.row(key)['error_code'], 'CANCEL_RACED_COMPLETION')
        self.assertEqual(self.worker.deliveries, [])

    def test_one_database_has_only_one_active_owner(self):
        with self.assertRaises((OSError, ProtocolError)):
            self.open()


if __name__ == '__main__':
    unittest.main()
