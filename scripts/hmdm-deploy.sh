#!/usr/bin/env bash
# Publica um APK como versão nova do UHF Logger no Headwind MDM e a liga a UMA
# configuração. Os aparelhos dessa configuração recebem o push "configUpdated"
# e instalam sozinhos (respeitando a janela de atualização da config).
#
#   HMDM_URL=... HMDM_LOGIN=... HMDM_PASSWORD=... \
#   HMDM_APP_ID=100 HMDM_CONFIG_ID=4 scripts/hmdm-deploy.sh app.apk
#
# Idempotente: rodar de novo com o mesmo APK reaproveita a versão já criada.
#
# NUNCA trocar para PUT /rest/private/configurations: ele substitui a lista
# inteira de apps da configuração (já apagou 85 apps uma vez).
set -euo pipefail

APK="${1:?uso: hmdm-deploy.sh <apk>}"
: "${HMDM_URL:?}" "${HMDM_LOGIN:?}" "${HMDM_PASSWORD:?}" "${HMDM_APP_ID:?}" "${HMDM_CONFIG_ID:?}"

json() { python3 -c "import sys,json; d=json.load(sys.stdin); $1"; }

# O Headwind responde 200 mesmo em erro; o que vale é "status".
api() {
  local method=$1 path=$2; shift 2
  local out
  out=$(curl -sS --fail-with-body -X "$method" "$HMDM_URL$path" \
    -H "Authorization: Bearer $TOKEN" "$@")
  if [ "$(echo "$out" | json 'print(d.get("status"))')" != "OK" ]; then
    echo "::error::Headwind $method $path falhou: $out" >&2
    return 1
  fi
  echo "$out"
}

# Senha vai como MD5 em MAIÚSCULAS — é o que o painel web faz.
PASS_MD5=$(printf %s "$HMDM_PASSWORD" | md5sum | cut -d' ' -f1 | tr a-f A-F)
TOKEN=$(curl -sS --fail-with-body -X POST "$HMDM_URL/rest/public/jwt/login" \
  -H 'Content-Type: application/json' \
  -d "{\"login\":\"$HMDM_LOGIN\",\"password\":\"$PASS_MD5\"}" | json 'print(d["id_token"])')

UPLOAD=$(api POST /rest/private/web-ui-files -F "file=@$APK")
SERVER_PATH=$(echo "$UPLOAD" | json 'print(d["data"]["serverPath"])')
PKG=$(echo "$UPLOAD" | json 'print(d["data"]["fileDetails"]["pkg"])')
VERSION=$(echo "$UPLOAD" | json 'print(d["data"]["fileDetails"]["version"])')
VERSION_CODE=$(echo "$UPLOAD" | json 'print(d["data"]["fileDetails"]["versionCode"])')
echo "APK: $PKG $VERSION ($VERSION_CODE)"

if [ "$PKG" != "com.uhflogger" ]; then
  echo "::error::APK é do pacote '$PKG', esperado com.uhflogger" >&2
  exit 1
fi

# O Headwind decide atualizar pelo versionName: versão repetida não chega
# aos aparelhos. Por isso o CI gera 1.4.0-b<N>.
find_version() {
  api GET "/rest/private/applications/$HMDM_APP_ID/versions" \
    | json "print(next((str(v['id']) for v in d['data'] if v['version']=='$VERSION'), ''))"
}

VERSION_ID=$(find_version)
if [ -z "$VERSION_ID" ]; then
  api PUT /rest/private/applications/versions -H 'Content-Type: application/json' \
    -d "{\"applicationId\":$HMDM_APP_ID,\"version\":\"$VERSION\",\"versionCode\":$VERSION_CODE,\"filePath\":\"$SERVER_PATH\"}" >/dev/null
  VERSION_ID=$(find_version)
  [ -n "$VERSION_ID" ] || { echo "::error::versão $VERSION não apareceu após criar" >&2; exit 1; }
  echo "Versão criada: id $VERSION_ID"
else
  echo "Versão já existia: id $VERSION_ID"
  # Religar uma versão existente desliga a atual da config (o Headwind deixa
  # uma só com action=1). Num "Re-run" de build antigo isso seria downgrade
  # nos aparelhos — então só segue se esta ainda for a mais nova do app.
  NEWEST=$(api GET "/rest/private/applications/$HMDM_APP_ID/versions" \
    | json 'print(max(v["versionCode"] for v in d["data"]))')
  if [ "$VERSION_CODE" -lt "$NEWEST" ]; then
    echo "::warning::$VERSION ($VERSION_CODE) é mais antiga que a versão $NEWEST já publicada — não religo para não fazer downgrade."
    exit 0
  fi
fi

# Liga a versão SÓ à configuração alvo. notify=true dispara o push para os
# aparelhos; action=1 = instalar.
CUSTOMER_ID=$(api GET "/rest/private/applications/version/$VERSION_ID/configurations" \
  | json "print(next((c['customerId'] for c in d['data'] if c['configurationId']==$HMDM_CONFIG_ID), 1))")
api POST /rest/private/applications/version/configurations -H 'Content-Type: application/json' \
  -d "{\"applicationVersionId\":$VERSION_ID,\"configurations\":[{\"id\":null,\"customerId\":$CUSTOMER_ID,\"configurationId\":$HMDM_CONFIG_ID,\"applicationId\":$HMDM_APP_ID,\"applicationVersionId\":$VERSION_ID,\"action\":1,\"showIcon\":true,\"remove\":false,\"notify\":true}]}" >/dev/null

# Confere que a config agora aponta para a versão nova.
LINKED=$(api GET "/rest/private/applications/version/$VERSION_ID/configurations" \
  | json "print(next((c['action'] for c in d['data'] if c['configurationId']==$HMDM_CONFIG_ID), ''))")
if [ "$LINKED" != "1" ]; then
  echo "::error::config $HMDM_CONFIG_ID não ficou com a versão $VERSION (action='$LINKED')" >&2
  exit 1
fi
echo "OK: config $HMDM_CONFIG_ID → $VERSION (versão $VERSION_ID), aparelhos notificados"
