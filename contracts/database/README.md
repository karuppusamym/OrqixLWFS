# Database contract

The reference DDL moved to the backend at ticket B01.4, as planned in S2:
[`backend/app/src/main/resources/db/migration/`](../../backend/app/src/main/resources/db/migration/).

- `V1__orqix_core.sql` — application schema (contract reviewed in docs/build/S2-data-model.md)
- `V2__spring_session.sql` — Spring Session JDBC schema, copied verbatim from the pinned `spring-session-jdbc` jar

Changes are new versioned migrations only (expand/contract), never edits to applied files.
