-- Test database bootstrap; mirrors deploy/compose/postgres/sql/orqix-init.sql (roles, schemas, default privileges).
CREATE ROLE orqix_migrator LOGIN PASSWORD 'migrator-test';
CREATE ROLE orqix_app      LOGIN PASSWORD 'app-test';
ALTER DATABASE orqix OWNER TO orqix_migrator;
REVOKE ALL ON DATABASE orqix FROM PUBLIC;
GRANT CONNECT ON DATABASE orqix TO orqix_migrator, orqix_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA orqix    AUTHORIZATION orqix_migrator;
CREATE SCHEMA flowable AUTHORIZATION orqix_migrator;
GRANT USAGE ON SCHEMA orqix, flowable TO orqix_app;
ALTER DEFAULT PRIVILEGES FOR ROLE orqix_migrator IN SCHEMA flowable
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO orqix_app;
ALTER DEFAULT PRIVILEGES FOR ROLE orqix_migrator IN SCHEMA flowable
    GRANT USAGE, SELECT ON SEQUENCES TO orqix_app;
