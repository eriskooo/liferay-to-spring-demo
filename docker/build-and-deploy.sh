#!/usr/bin/env bash
# Build legacy appky a SSO mocku, štart Docker stacku a hot deploy WAR-ov do Liferay.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NEXT="bash docker/smoke.sh && bash docker/smoke-migration.sh"

mvn -q -B -f "$ROOT/legacy/pom.xml" verify
mvn -q -B -f "$ROOT/sso-mock/pom.xml" verify
mvn -q -B -f "$ROOT/crm-web/pom.xml" verify
cp "$ROOT/legacy/sso-portal-api/target/sso-portal-api.jar" "$ROOT/docker/liferay/sso-portal-api.jar"
mkdir -p "$ROOT/docker/deploy"

cd "$ROOT/docker"
docker compose up -d --build

# Čakanie na deploy sledujeme len v logoch od tohto okamihu – staršie behy kontajnera by dali falošné "hotovo".
DEPLOY_START="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
# Theme pred portletmi: portlety ho majú v required-deployment-contexts. Liferay WAR po spracovaní z deploy/ zmaže.
cp "$ROOT/legacy/customercare-theme/target/customercare-theme.war" deploy/
cp "$ROOT/legacy/customercare-portlets/target/customercare-portlet.war" deploy/

# RedisInsight 3.x nepreberá pripojenie z premenných prostredia – databázu zdieľanej session zaregistrujeme cez jeho API.
for _ in $(seq 1 30); do curl -s -o /dev/null http://localhost:5540/api/databases && break; sleep 2; done
curl -s http://localhost:5540/api/databases | grep -q crm-shared-session     || curl -s -o /dev/null -X POST http://localhost:5540/api/databases -H 'Content-Type: application/json'         -d '{"name":"crm-shared-session","host":"redis","port":6379}'
# Skript skončí až keď sú portlety nasadené, aby smoke testy nešli proti polovičnému stacku.
# Presný text logu: webapp sa volá customercare-portlet (bez "s").
READY_LOG="portlets for customercare-portlet are available for use"
echo "Čekám na start Liferay a deploy portletů (obvykle 1-3 min, max 10 min)..."
for i in $(seq 1 120); do
    if docker compose logs --since "$DEPLOY_START" liferay 2>/dev/null | grep -qF "$READY_LOG"; then
        echo "Hotovo za ~$((i * 5)) s. Dále: $NEXT"
        exit 0
    fi
    sleep 5
done
echo "CHYBA: Liferay nenasadil portlety do 10 min. Poslední řádky logu:" >&2
docker compose logs --tail 40 liferay >&2
exit 1
