#!/usr/bin/env bash
# TEST ONLY: self-signed certificate for ORQIX_PUBLIC_URL's host name in certs/ (tls.crt, tls.key, valid 30 days).
# Production: put your CA-issued certificate there instead (full chain in tls.crt, server certificate first).
source "$(dirname "$0")/common.sh"
require_env_file
url="$(env_get ORQIX_PUBLIC_URL)"; host="${url#https://}"; host="${host%%/*}"; host="${host%%:*}"
dir="$(abs_path "$(env_get TLS_CERT_DIR ./certs)")"; mkdir -p "$dir"
san="DNS:$host"; [[ "$host" =~ ^[0-9.]+$ ]] && san="IP:$host"
# Relative file names: also works with a native Windows openssl under Git Bash.
( cd "$dir" && openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 30 -subj "/CN=$host" \
    -addext "subjectAltName=$san" -addext "extendedKeyUsage=serverAuth" \
    -keyout tls.key -out tls.crt 2>/dev/null ) || die "openssl could not create the certificate"
chmod 644 "$dir/tls.crt"; chmod 644 "$dir/tls.key" 2>/dev/null || true
log "self-signed certificate for $host in $dir (browsers will warn; test only)"
