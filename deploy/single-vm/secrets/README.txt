Mounted read-only at /var/run/secrets/orqix in the Orqix container (ORQIX_SECRETS_DIR).
One file per connection secretRef; the file content is the secret value. Empty unless you bind a connector.
Read on every call: replacing a file rotates the secret without a restart. Included in backups (config.tar.gz).
Nothing in this folder is committed (.gitignore).
