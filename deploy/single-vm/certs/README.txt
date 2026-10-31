Bring your own TLS certificate (or point TLS_CERT_DIR in .env at another folder):
  tls.crt  PEM, full chain, server certificate first; subjectAltName must contain the host of ORQIX_PUBLIC_URL
  tls.key  PEM private key, unencrypted, readable by the proxy container (chmod 640, owner root)
scripts/preflight.sh checks expiry, key match and name. scripts/dev-cert.sh makes a self-signed one for TESTS ONLY.
Nothing in this folder is committed (.gitignore).
