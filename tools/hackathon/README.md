# Hackathon qualification tools

The [baseline](../../docs/23-hackathon-requirements-baseline.md) defines this profile. The tools do not certify Orchesstra or customer-source equivalence.

Prepare an artifact from a committed, reviewed definition, using the Python dependencies already listed in `tools/contract-check/requirements.txt`:

```powershell
python tools/hackathon/prepare_release.py --source examples/hackathon/tier1-approval.json --commit <full-reviewed-commit> --review-reference <review-id> --output evidence/hackathon/prepared.json
python -m unittest discover -s tools/hackathon -p "test_*.py"
```

The source file must exist in that commit and match the working definition. Provenance is injected into a separate artifact after review; it records the source commit, avoiding a self-referential Git hash. The sidecar records the source blob and prepared artifact SHA-256 values. Runtime `sourceHash` is separately computed using the canonical JSON algorithm. Import/save the prepared definition as a draft, run its golden scenarios and have an independent publisher release it. The server stores and checks the provenance shape and publisher separation; it has no Git access and does not independently verify a supplied commit's existence. Use this preparation step in the trusted pipeline to establish that link.

The sample has no fabricated Git provenance. Commit/review it in the normal project process before using the preparation command. The test fixtures use explicitly synthetic provenance only in isolated tests.

For the local capture service:

```powershell
cd deploy/compose
docker compose -f docker-compose.yml -f docker-compose.hackathon.yml --profile app up -d --build
```

Open `http://localhost:1080/messages` for synthetic captured email. No email leaves this SMTP sink. With the overlay removed, SMTP remains disabled unless configured. The capture service retains only the last 1,000 messages in memory and loses them on restart. It is a development fixture, not a production mail server.

Build independently deployable components:

```powershell
cd frontend
npm run build:authoring
npm run build:tasks
```

The application Docker build runs `npm run build:embedded`, packaging the two artifacts and the sample host with the normal application. See [embedding contract](../../docs/hackathon-embedding.md).

## Real local acceptance and load

Use the seeded development realm and an isolated Compose project. The realm permits the normal application port 8080 and the qualification port 18080; existing Keycloak volumes retain their imported client settings and must be updated explicitly when changing ports. Do not weaken production redirect rules.

```powershell
$env:ORQIX_BASE_URL = 'http://localhost:18080'
$env:MAIL_CAPTURE_URL = 'http://localhost:11080'
python tools/hackathon/demo.py --restart-container orqix-hackathon-verified-orqix-1
python tools/hackathon/load.py --users 2 --cases 40 --warmup-minutes 0 --minutes 0.5 --out evidence/hackathon/load-smoke.json
python tools/hackathon/load.py
```

`demo.py` checks independent publication, exact/changed idempotency replay, a real one-minute SLA, durable restart recovery, cancellation, SMTP capture and permission-scoped evidence. It temporarily grants `audit.read` through the public API and removes the grant in a `finally` block. Restart is restricted to explicitly named containers belonging to a dedicated `orqix-hackathon-*` project. Run browser checks separately from this restart exercise.

`load.py` defaults to 50 virtual users, 10,000 actual stored cases, five minutes of warm-up and fifteen minutes of measured traffic. Preparation is additional time. Each virtual user owns its cases and authenticated sessions; the measured HTTP mix is 40% reads, 20% starts, 30% decisions and 10% reassignment/closure. Run without competing builds or test suites before interpreting latency. A smaller smoke run verifies the harness only. The tool writes observed p95 latency, errors and workload counts and exits nonzero if the local targets fail. Fixtures remain in the isolated development database for inspection.

Both scripts accept only localhost targets, use explicitly synthetic source provenance, and never qualify genuine source equivalence, enterprise SMTP, compliance or production capacity. Evidence is written under the ignored `evidence/hackathon/` directory. See the [acceptance record](../../docs/hackathon-acceptance.md) for the dated outcomes and remaining owner gates.

Interrupted preparation can be resumed using the fixture manifest written before cases are created:

```powershell
python tools/hackathon/load.py --reuse-fixture evidence/hackathon/load.fixture.json
```

Keep the same target URL and virtual-user count and choose `--cases` at least as large as the existing fixture. The tool validates the synthetic workflow namespace, takes a fixed census, assigns every existing case to exactly one virtual user and creates only missing cases. A reused task whose assignee no longer matches the current dev personas is reassigned through the audited operator API during preparation. All users authenticate again after preparation, before either timing clock starts. Preparation, identity refresh and repair requests are excluded from measured traffic. Four seeded role personas supply the independent virtual-user session sets; this does not test fifty distinct enterprise directory identities.

The development realm pins explicit user IDs so recreating its container preserves OIDC subject mappings. An older database paired with a recreated, unpinned realm may contain retired subject IDs; the local directory must mark those identities disabled before automatic dispatch is used. Preserve their audit history. This is a fixture lifecycle concern, not a substitute for enterprise identity lifecycle integration.
