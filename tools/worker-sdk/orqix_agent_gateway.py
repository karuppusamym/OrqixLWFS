"""Restart-safe native async agents on Orqix's authenticated external-worker callback boundary.

Run --help. The SQLite database contains protected work and must reside on a private, durable local volume.
One gateway process owns one database; dispatch intents commit before any remote submission.
"""
from __future__ import annotations

import argparse
from dataclasses import asdict
from datetime import datetime
import json
import os
from pathlib import Path
import re
import sqlite3
import time
import uuid

from orqix_worker import ApiError, CallbackTicket, Job, Worker
from orqix_protocols import NativeClient, Profile, Progress, ProtocolError, Transport, map_result, object_json


class GatewayWorker(Worker):
    """The worker SDK protocol with the gateway's bounded, nonredirecting, address-pinned transport."""
    def __init__(self, base_url, workspace_id, token_file, worker_id, allow_private=False, allow_http=False):
        uuid.UUID(workspace_id)
        if not isinstance(token_file, str) or not token_file.strip():
            raise ProtocolError('WORKER_TOKEN_FILE_REQUIRED')
        if not re.fullmatch(r'[A-Za-z0-9_.-]{1,64}', worker_id):
            raise ProtocolError('INVALID_WORKER_ID')
        super().__init__(base_url, workspace_id, token=lambda: '', worker_id=worker_id, max_jobs=1)
        self.transport = Transport(Profile('a2a-1.0', base_url, {'unused': '/unused'}, token_file=token_file,
                                           allow_private=allow_private, allow_http=allow_http), error_codes=True)

    def _call(self, method, path, body=None, idempotency_key=None):
        headers = {'Idempotency-Key': idempotency_key} if idempotency_key else {}
        try:
            return self.transport.exchange(method, body, headers, suffix=f'/workspaces/{self._ws}{path}')[0]
        except ProtocolError as failure:
            if failure.status:
                raise ApiError(failure.status, failure.problem_code or failure.code, 'Gateway request refused') from None
            raise


class Gateway:
    def __init__(self, worker: Worker, database: str, profiles: dict[str, Profile], client_factory=NativeClient,
                 clock=time.time, max_pending=100):
        if not profiles or len(profiles) > 10 or any(not re.fullmatch(r'[a-z][a-z0-9-]{1,62}', topic) for topic in profiles):
            raise ProtocolError('INVALID_TOPICS')
        if type(max_pending) is not int or not 1 <= max_pending <= 1000:
            raise ProtocolError('INVALID_CAPACITY')
        self.worker, self.profiles, self.factory, self.clock = worker, profiles, client_factory, clock
        self.max_pending = max_pending
        self.db, self.lock = None, None
        file = Path(database).resolve()
        if not file.parent.is_dir():
            raise ProtocolError('OUTBOX_DIRECTORY_REQUIRED')
        self.lock = open(str(file) + '.lock', 'a+b')
        try:
            self.lock.seek(0); self.lock.write(b'0'); self.lock.flush(); self.lock.seek(0)
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(self.lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if not file.exists():
                descriptor = os.open(file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                os.close(descriptor)
            if os.name != 'nt' and file.stat().st_mode & 0o077:
                raise ProtocolError('OUTBOX_MUST_BE_PRIVATE')
            self.db = sqlite3.connect(file)
            self.db.row_factory = sqlite3.Row
            self.db.execute('PRAGMA synchronous=FULL')
            self.db.execute('PRAGMA journal_mode=DELETE')
            self.db.execute('CREATE TABLE IF NOT EXISTS metadata (id INTEGER PRIMARY KEY, identity TEXT NOT NULL)')
            self.db.execute('''CREATE TABLE IF NOT EXISTS work (
                key TEXT PRIMARY KEY, topic TEXT NOT NULL, profile_hash TEXT NOT NULL,
                state TEXT NOT NULL, job TEXT, ticket TEXT, remote TEXT, delivery TEXT,
                dispatch_key TEXT NOT NULL, next_poll REAL NOT NULL, error_code TEXT, reference TEXT)''')
            identity = json.dumps([worker._base, worker._ws, worker.worker_id])
            prior = self.db.execute('SELECT identity FROM metadata WHERE id=1').fetchone()
            if prior and prior[0] != identity:
                raise ProtocolError('OUTBOX_WORKER_IDENTITY_CHANGED')
            self.db.execute('INSERT OR IGNORE INTO metadata VALUES(1,?)', (identity,))
            self.db.commit()
        except BaseException:
            self.close()
            raise

    def close(self):
        if self.db is not None:
            self.db.close(); self.db = None
        if self.lock is not None:
            self.lock.close(); self.lock = None

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def change(self, key, **updates):
        allowed = {'state', 'job', 'ticket', 'remote', 'delivery', 'next_poll', 'error_code', 'reference'}
        if not updates or set(updates) - allowed:
            raise ProtocolError('INVALID_OUTBOX_UPDATE')
        with self.db:
            self.db.execute('UPDATE work SET ' + ','.join(name + '=?' for name in updates) + ' WHERE key=?',
                            [*updates.values(), key])

    def accept(self, job: Job):
        if job.topic not in self.profiles:
            raise ProtocolError('UNKNOWN_TOPIC')
        profile = self.profiles[job.topic]
        key = f'{job.id}:{job.fence}'
        payload = json.dumps(asdict(job), allow_nan=False)
        if len(payload.encode()) > 65536:
            raise ProtocolError('JOB_TOO_LARGE')
        with self.db:
            self.db.execute('INSERT OR IGNORE INTO work(key,topic,profile_hash,state,job,dispatch_key,next_poll) VALUES(?,?,?,?,?,?,?)',
                            (key, job.topic, profile.fingerprint(), 'registering', payload,
                             str(uuid.uuid5(uuid.NAMESPACE_URL, f'{self.worker._base}/{self.worker._ws}/{key}')), self.clock()))
        return key

    def row(self, key):
        value = self.db.execute('SELECT * FROM work WHERE key=?', (key,)).fetchone()
        if value is None:
            raise ProtocolError('OUTBOX_ITEM_NOT_FOUND')
        return dict(value)

    def stage_failure(self, key, code):
        # Only static codes enter the timeline. Remote error text and input-required messages are never forwarded.
        self.change(key, state='delivering', delivery=json.dumps({'failure': code}), error_code=code, next_poll=self.clock())

    def scheduled(self, key, delay):
        row = self.row(key)
        deadline = datetime.fromisoformat(json.loads(row['ticket'])['deadline'].replace('Z', '+00:00')).timestamp()
        return min(self.clock() + delay, deadline) if row['state'] != 'delivering' else self.clock() + delay

    def progress(self, key, profile, progress: Progress):
        if progress.remote.get('id'):
            self.change(key, reference=json.dumps({name: value for name, value in progress.remote.items() if name in ('id', 'context')}))
        if progress.state == 'completed':
            result = map_result(profile, progress.output)
            self.change(key, state='delivering', remote=json.dumps(progress.remote),
                        delivery=json.dumps({'result': result}, allow_nan=False), next_poll=self.clock())
        elif progress.state in ('failed', 'input_required'):
            self.stage_failure(key, 'REMOTE_FAILED' if progress.state == 'failed' else 'REMOTE_INPUT_REQUIRED')
        elif progress.state == 'pending':
            self.change(key, state='remote', remote=json.dumps(progress.remote),
                        next_poll=self.scheduled(key, progress.poll_seconds))
        else:
            raise ProtocolError('INVALID_PROGRESS')

    def advance(self, key):
        row = self.row(key)
        if row['state'] in ('done', 'attention', 'orphaned') or row['next_poll'] > self.clock():
            return
        profile = self.profiles.get(row['topic'])
        if profile is None or profile.fingerprint() != row['profile_hash']:
            # Never repoint an existing remote task to a changed endpoint, protocol, result mapping or credential reference.
            raise ProtocolError('PENDING_PROFILE_CHANGED')
        if row['state'] == 'registering':
            job = Job(**json.loads(row['job']))
            try:
                ticket = self.worker.await_callback(job, profile.timeout_seconds)
            except ApiError as failure:
                if failure.status in (401, 403):
                    raise
                if failure.status == 409:
                    self.finish(key, 'orphaned', failure.code)
                    return
                raise
            self.change(key, state='prepared', ticket=json.dumps(asdict(ticket)))
            row = self.row(key)
        ticket = CallbackTicket(**json.loads(row['ticket']))
        parsed_deadline = datetime.fromisoformat(ticket.deadline.replace('Z', '+00:00'))
        if parsed_deadline.tzinfo is None:
            raise ProtocolError('INVALID_CALLBACK_DEADLINE')
        deadline = parsed_deadline.timestamp()
        if row['state'] != 'delivering' and self.clock() >= deadline:
            self.stage_failure(key, 'REMOTE_DEADLINE_EXCEEDED')
            row = self.row(key)
        client = self.factory(profile)
        if row['state'] == 'prepared':
            try:
                prepared = client.prepare()
            except ProtocolError as failure:
                if failure.retryable:
                    self.change(key, next_poll=self.scheduled(key, profile.poll_seconds))
                    return
                self.stage_failure(key, failure.code)
            else:
                if self.clock() >= deadline:
                    self.stage_failure(key, 'REMOTE_DEADLINE_EXCEEDED')
                else:
                    # A crash here is deliberately not recoverable by resubmission.
                    self.change(key, state='dispatching', remote=json.dumps(prepared))
                    try:
                        progress = client.submit(json.loads(row['job'])['input'], row['dispatch_key'], prepared)
                        self.progress(key, profile, progress)
                    except Exception:
                        self.stage_failure(key, 'REMOTE_SUBMISSION_UNCERTAIN')
            row = self.row(key)
        elif row['state'] == 'dispatching':
            self.stage_failure(key, 'REMOTE_SUBMISSION_UNCERTAIN')
            row = self.row(key)
        elif row['state'] in ('remote', 'cancel_check', 'cancelling'):
            checking_cancel = row['state'] != 'remote'
            if row['state'] == 'cancelling':
                self.change(key, state='cancel_check')
            try:
                progress = client.poll(json.loads(row['remote']))
                if checking_cancel:
                    if progress.state == 'pending':
                        self.change(key, state='cancel_check', next_poll=self.scheduled(key, progress.poll_seconds))
                    else:
                        self.stage_failure(key, 'REMOTE_CANCELLED' if progress.cancelled else 'CANCEL_RACED_COMPLETION' if progress.state == 'completed' else 'REMOTE_TERMINAL_AFTER_CANCEL')
                else:
                    self.progress(key, profile, progress)
            except ProtocolError as failure:
                if failure.retryable:
                    self.change(key, next_poll=self.scheduled(key, profile.poll_seconds))
                    return
                self.stage_failure(key, failure.code)
            row = self.row(key)
        if row['state'] == 'delivering':
            delivery = json.loads(row['delivery'])
            try:
                if 'result' in delivery:
                    self.worker.complete_callback(ticket, delivery['result'])
                else:
                    self.worker.fail_callback(ticket, 'Native agent needs attention: ' + delivery['failure'])
            except ApiError as failure:
                if failure.status in (401, 403):
                    raise
                if failure.status == 409 and failure.code in ('JOB_NOT_OPEN', 'JOB_LEASE_LOST'):
                    self.finish(key, 'orphaned', failure.code)
                elif failure.status == 422:
                    if 'result' in delivery:
                        self.stage_failure(key, 'RESULT_REJECTED')
                    else:
                        self.finish(key, 'attention', 'FAILURE_DELIVERY_REJECTED')
                else:
                    self.change(key, next_poll=self.scheduled(key, profile.poll_seconds))
                return
            except (OSError, ProtocolError):
                self.change(key, next_poll=self.scheduled(key, profile.poll_seconds))
                return
            self.finish(key, 'attention' if 'failure' in delivery else 'done', delivery.get('failure'))

    def cancel(self, key):
        row = self.row(key)
        profile = self.profiles.get(row['topic'])
        if profile is None or profile.fingerprint() != row['profile_hash']:
            raise ProtocolError('PENDING_PROFILE_CHANGED')
        if row['state'] != 'remote':
            raise ProtocolError('REMOTE_TASK_NOT_CANCELLABLE')
        self.change(key, state='cancelling', next_poll=self.clock())
        try:
            self.factory(profile).cancel(json.loads(row['remote']))
        except Exception:
            # An uncertain cancel is checked by polling, never automatically sent again.
            pass
        self.change(key, state='cancel_check')
        self.advance(key)

    def finish(self, key, state, error_code=None):
        self.change(key, state=state, error_code=error_code, job=None, delivery=None, ticket=None, remote=None)

    def tick(self):
        rows = self.db.execute("SELECT key FROM work WHERE state NOT IN ('done','attention','orphaned') ORDER BY next_poll LIMIT 100").fetchall()
        for row in rows:
            self.advance(row['key'])
        count = self.db.execute("SELECT count(*) FROM work WHERE state NOT IN ('done','attention','orphaned')").fetchone()[0]
        if count < self.max_pending:
            for job in self.worker.fetch_and_lock(sorted(self.profiles)):
                self.advance(self.accept(job))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, help='Private deployment JSON; credentials are file references')
    parser.add_argument('--database', required=True, help='Durable private SQLite outbox (one process per database)')
    parser.add_argument('--once', action='store_true', help='Advance due work and fetch once, then exit')
    parser.add_argument('--cancel', help='Request remote cancellation once for a pending job:fence, then check its outcome')
    parser.add_argument('--inspect', help='Inspect correlation metadata for a job:fence; no inputs, outputs or session credentials')
    parser.add_argument('--status', action='store_true', help='Print counts only; never protected payloads or remote IDs')
    args = parser.parse_args()
    try:
        with Path(args.config).open('rb') as source:
            raw = source.read(65537)
        if len(raw) > 65536:
            raise ProtocolError('CONFIG_TOO_LARGE')
        config = object_json(raw)
        if set(config) != {'orqix', 'profiles'} or not isinstance(config['profiles'], dict):
            raise ProtocolError('INVALID_CONFIGURATION')
        worker = GatewayWorker(**config['orqix'])
        profiles = {topic: Profile(**value) for topic, value in config['profiles'].items()}
        with Gateway(worker, args.database, profiles) as gateway:
            if args.cancel:
                gateway.cancel(args.cancel)
                print('Remote cancellation requested; resume the daemon to continue checking pending outcomes')
                return
            if args.inspect:
                row = gateway.row(args.inspect)
                print(json.dumps({name: row[name] for name in ('key', 'topic', 'state', 'dispatch_key', 'error_code', 'reference')}))
                return
            if args.status:
                print(json.dumps(dict(gateway.db.execute('SELECT state,count(*) FROM work GROUP BY state').fetchall())))
                return
            while True:
                try:
                    gateway.tick()
                except ApiError as failure:
                    if failure.status in (401, 403):
                        raise
                except ProtocolError as failure:
                    if not failure.retryable:
                        raise
                if args.once:
                    return
                time.sleep(1)
    except KeyboardInterrupt:
        return
    except (ProtocolError, ApiError, OSError, ValueError, TypeError, sqlite3.Error) as failure:
        # Do not print exception strings from file, SQLite, urllib or provider libraries.
        print('Gateway stopped: ' + (failure.code if isinstance(failure, (ProtocolError, ApiError)) else type(failure).__name__))
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()
