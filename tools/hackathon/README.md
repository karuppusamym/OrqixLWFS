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
