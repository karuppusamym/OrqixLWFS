-- V1 granted DML on the tables that existed at that point; the Spring Session tables arrive in V2.
-- Sessions are mutable by design, so the runtime role gets full DML on them.
GRANT SELECT, INSERT, UPDATE, DELETE ON SPRING_SESSION, SPRING_SESSION_ATTRIBUTES TO ${app_role};
