Mounted read-only at /var/run/secrets/orqix in the app container (orqix.secrets.dir).
One file per connection `secretRef`; the file content is the secret value. Empty in development because the procurement stub needs no credential.
Put only throw-away local values here; never commit real secrets (the dev stack must not hold production credentials).
