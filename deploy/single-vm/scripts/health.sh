#!/usr/bin/env bash
# Health of the running kit: container states, Orqix readiness through the management port, the UI and sign-in through
# HTTPS. Exit 0 = healthy.
source "$(dirname "$0")/common.sh"
require_env_file

rc=0
for s in postgres orqix proxy $(keycloak_enabled && echo keycloak); do
  st="$(health_of "$s")"
  printf '  %-10s %s\n' "$s" "$st"
  [[ "$st" == "healthy" ]] || rc=1
done

mb="$(env_get MANAGEMENT_BIND 127.0.0.1)"; [[ "$mb" == "0.0.0.0" ]] && mb=127.0.0.1
mgmt="http://$mb:$(env_get MANAGEMENT_PORT 8081)"
ready="$(curl -fsS --max-time 10 "$mgmt/actuator/health/readiness" 2>/dev/null || true)"
printf '  readiness  %s\n' "${ready:-unreachable}"
[[ "$ready" == *'"status":"UP"'* ]] || rc=1

# The public URL may not resolve from the VM itself: connect to the local HTTPS port with the public host name.
url="$(env_get ORQIX_PUBLIC_URL)"; hostport="${url#https://}"; host="${hostport%%:*}"; host="${host%%/*}"
hp="$(env_get HTTPS_PORT 443)"; hb="$(env_get HTTPS_BIND 0.0.0.0)"; [[ "$hb" == "0.0.0.0" ]] && hb=127.0.0.1
pubport="${hostport#*:}"; [[ "$pubport" == "$hostport" ]] && pubport=443
resolve=(--resolve "$host:$pubport:$hb")
[[ "$pubport" != "$hp" ]] && resolve=(--connect-to "$host:$pubport:$hb:$hp")
cacert=(); [[ -n "${ORQIX_CA_FILE:-}" ]] && cacert=(--cacert "$ORQIX_CA_FILE") || cacert=(-k)
ui="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "${cacert[@]}" "${resolve[@]}" "$url/" 2>/dev/null || true)"
printf '  https UI   %s\n' "${ui:-unreachable}"
[[ "$ui" == "200" || "$ui" == "302" ]] || rc=1
if keycloak_enabled; then
  realm="$(env_get ORQIX_REALM orqix)"
  oidc="$(curl -sS --max-time 10 "${cacert[@]}" "${resolve[@]}" "$url/auth/realms/$realm/.well-known/openid-configuration" 2>/dev/null || true)"
  if [[ "$oidc" == *"\"issuer\":\"$url/auth/realms/$realm\""* ]]; then printf '  oidc       issuer %s/auth/realms/%s\n' "$url" "$realm"
  else printf '  oidc       unexpected discovery document\n'; rc=1; fi
fi
(( rc == 0 )) && log "healthy" || warn "not healthy"
exit $rc
