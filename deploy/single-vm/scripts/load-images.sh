#!/usr/bin/env bash
# On the AIR-GAPPED VM: verify and load image archives made by scripts/save-images.sh (or any `docker save` tarball).
#
#   scripts/load-images.sh /media/usb/orqix-images-0.1.0.tar [more.tar ...]
source "$(dirname "$0")/common.sh"
(( $# )) || die "usage: $0 <archive.tar> [...]"
for f in "$@"; do
  [[ -f "$f" ]] || die "$f not found"
  if [[ -f "$f.sha256" ]]; then
    [[ "$(sha256_file "$f")" == "$(awk '{print $1}' "$f.sha256")" ]] || die "checksum mismatch: $f"
    log "checksum OK: $f"
  else
    warn "no $f.sha256: integrity not verified"
  fi
  docker load -i "$f"
done
