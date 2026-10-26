# Demo tools

- `demo-stack.sh` starts an isolated demo stack (see docs/demo-guide.md).
- `seed_demo.py` adds nine Purchase approval requests in different states.
- `seed_samples.py` adds four sample workflows (Pega XPDL routing and review, Pega Blueprint synthetic PDF, Camunda 7 purchase) with finished and open requests, through the public API. Idempotent; reads `ORQIX_BASE_URL` and `ORQIX_STUB_URL`; prints a summary table and never prints passwords. Details and which user acts on each open item: "Sample data" in docs/demo-guide.md.

```
ORQIX_BASE_URL=http://localhost:8080 ORQIX_STUB_URL=http://localhost:8095 python tools/demo/seed_samples.py
```
