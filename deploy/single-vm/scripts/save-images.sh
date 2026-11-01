#!/usr/bin/env bash
# On a CONNECTED staging machine: pull (unless --no-pull) and save every image the kit uses into one archive plus a
# .sha256 file, to carry to the air-gapped VM (scripts/load-images.sh there).
#
#   scripts/save-images.sh /media/usb/orqix-images-0.1.0.tar
source "$(dirname "$0")/common.sh"
require_env_file
OUT="${1:?usage: $0 <archive.tar> [--no-pull]}"; PULL=true; [[ "${2-}" == "--no-pull" ]] && PULL=false
imgs=("$(env_get ORQIX_IMAGE)" "$(env_get POSTGRES_IMAGE postgres:18-alpine)" "$(env_get PROXY_IMAGE nginx:1.27-alpine)" \
      "$(env_get KEYCLOAK_IMAGE quay.io/keycloak/keycloak:26.8.0)")
if $PULL; then for i in "${imgs[@]}"; do docker pull "$i"; done; fi
docker save -o "$OUT" "${imgs[@]}"
sha256sum "$OUT" | awk -v f="$(basename "$OUT")" '{print $1 "  " f}' > "$OUT.sha256"
log "saved ${#imgs[@]} images to $OUT ($(wc -c < "$OUT" | tr -d ' ') bytes), checksum in $OUT.sha256"
printf '  %s\n' "${imgs[@]}"
