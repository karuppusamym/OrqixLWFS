#!/usr/bin/env bash
# Create (or update) a user in the bundled Keycloak realm and put them in groups. Groups are created when missing.
#
#   scripts/add-user.sh ada.admin --email ada@example.internal --groups orqix-platform-admins,finance-approvers
#   ORQIX_USER_PASSWORD=... scripts/add-user.sh rex.requester --email rex@example.internal --groups requesters    # otherwise prompts
#
# Uses the temporary master-realm admin from .env (KEYCLOAK_ADMIN / KEYCLOAK_ADMIN_PASSWORD) through kcadm.sh inside the
# keycloak container; no password appears on the host's command lines. External IdP: manage users there instead.
source "$(dirname "$0")/common.sh"
require_env_file
keycloak_enabled || die "the bundled Keycloak is not enabled (COMPOSE_PROFILES); manage users in your IdP"

USERNAME="${1-}"; [[ -n "$USERNAME" && "$USERNAME" != --* ]] || die "usage: $0 <username> --email E [--first F] [--last L] [--groups g1,g2]"
shift
EMAIL=""; FIRST=""; LAST=""; GROUP_LIST=""
while (( $# )); do
  case "$1" in
    --email) EMAIL="$2"; shift ;;
    --first) FIRST="$2"; shift ;;
    --last) LAST="$2"; shift ;;
    --groups) GROUP_LIST="$2"; shift ;;
    *) die "unknown option $1" ;;
  esac
  shift
done
# Keycloak's user profile requires email, first and last name; without them the first sign-in stops at "update profile".
[[ -n "$EMAIL" ]] || die "--email is required"
PW="${ORQIX_USER_PASSWORD-}"
if [[ -z "$PW" ]]; then
  read -r -s -p "Password for $USERNAME (12+ characters): " PW; echo
fi
(( ${#PW} >= 12 )) || die "password shorter than 12 characters (realm password policy)"
[[ -n "$FIRST" ]] || FIRST="$(echo "${USERNAME%%.*}" | sed 's/./\U&/')"
[[ -n "$LAST" ]] || LAST="$(echo "${USERNAME#*.}" | sed 's/./\U&/')"

dc exec -T \
  -e KC_ADMIN="$(env_get KEYCLOAK_ADMIN admin)" -e KC_ADMIN_PW="$(env_get KEYCLOAK_ADMIN_PASSWORD)" \
  -e REALM="$(env_get ORQIX_REALM orqix)" -e U="$USERNAME" -e EMAIL="$EMAIL" -e FIRST="$FIRST" -e LAST="$LAST" \
  -e OQ_GROUPS="$GROUP_LIST" -e PW="$PW" keycloak bash -s <<'IN_CONTAINER'
set -euo pipefail
kc=/opt/keycloak/bin/kcadm.sh
export HOME=/tmp
$kc config credentials --server http://localhost:8080/auth --realm master --user "$KC_ADMIN" --password "$KC_ADMIN_PW" >/dev/null
id="$($kc get users -r "$REALM" -q username="$U" -q exact=true --fields id --format csv --noquotes | head -n 1)"
if [[ -z "$id" ]]; then
  id="$($kc create users -r "$REALM" -s username="$U" -s enabled=true -s firstName="$FIRST" -s lastName="$LAST"         -s email="$EMAIL" -s emailVerified=true -i)"
  echo "created user $U"
else
  $kc update "users/$id" -r "$REALM" -s firstName="$FIRST" -s lastName="$LAST" -s email="$EMAIL" -s emailVerified=true
  echo "user $U exists; updated profile, password and groups"
fi
$kc set-password -r "$REALM" --userid "$id" --new-password "$PW"
IFS=',' read -r -a gs <<< "$OQ_GROUPS"
for g in "${gs[@]}"; do
  [[ -n "$g" ]] || continue
  gid=""
  # The Keycloak image has no awk/sed: parse the CSV with bash.
  while IFS=, read -r i n; do [[ "$n" == "$g" ]] && { gid="$i"; break; }; done     < <($kc get groups -r "$REALM" -q search="$g" -q exact=true --fields id,name --format csv --noquotes)
  if [[ -z "$gid" ]]; then gid="$($kc create groups -r "$REALM" -s name="$g" -i)"; echo "created group $g"; fi
  $kc update "users/$id/groups/$gid" -r "$REALM" -s realm="$REALM" -s userId="$id" -s groupId="$gid" -n
  echo "  member of $g"
done
IN_CONTAINER
