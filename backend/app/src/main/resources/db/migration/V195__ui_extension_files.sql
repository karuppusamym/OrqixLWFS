-- UI02 / S116: multi-file extension bundles. A version may carry JavaScript modules, stylesheets, fonts and images
-- (flat names, no SVG), each with its SHA-256 recorded at submission; the version's hash covers the source and every
-- file hash, so an approval approves exactly these bytes. Files are immutable. `entry` names the module the frame loads;
-- `file_key` is a per-version random key that signs the short-lived file URLs a frame receives.
ALTER TABLE ui_extension_version DROP CONSTRAINT ui_extension_version_source_check;
ALTER TABLE ui_extension_version ADD COLUMN entry text CHECK (entry IS NULL OR entry ~ '^[a-z0-9][a-z0-9_-]{0,60}\.(js|mjs)$');
ALTER TABLE ui_extension_version ADD COLUMN file_key bytea CHECK (file_key IS NULL OR octet_length(file_key) = 32);
ALTER TABLE ui_extension_version ADD CONSTRAINT ui_extension_version_source_check
    CHECK (octet_length(source) <= 65536 AND (octet_length(source) >= 1 OR entry IS NOT NULL));

CREATE TABLE ui_extension_file (
    workspace_id uuid NOT NULL,
    extension_id uuid NOT NULL,
    version integer NOT NULL,
    path text NOT NULL CHECK (path ~ '^[a-z0-9][a-z0-9_-]{0,60}\.(js|mjs|css|woff2|woff|png|jpg|jpeg|webp|gif)$'),
    content_type text NOT NULL,
    content bytea NOT NULL CHECK (octet_length(content) BETWEEN 1 AND 262144),
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (extension_id, version, path),
    FOREIGN KEY (extension_id, version) REFERENCES ui_extension_version (extension_id, version)
);
GRANT SELECT, INSERT ON ui_extension_file TO ${app_role};
