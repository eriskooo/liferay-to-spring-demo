#!/usr/bin/env bash
# End-to-end overenie bežiaceho stacku cez nginx (http://localhost:8000): SSO login, blacklist, dashboard, AJAX,
# zdieľaná session v Redise, prepnutie flagu, výpadok Redisu, chybová stránka portálu, ACL čitateľa.
set -u
cd "$(dirname "$0")"
B="http://localhost:8000"
L="http://localhost:8080"   # Liferay priamo (starý blacklist mimo nginx – na :8000 ho presmeruje strangler)
WORK="$(mktemp -d)"
JAR="$WORK/cookies.txt"
FAILS=0

ok()   { echo "OK    $1"; }
fail() { echo "FAIL  $1"; FAILS=$((FAILS + 1)); }
expect()     { if grep -qF -- "$2" "$3"; then ok "$1"; else fail "$1 (chybí: $2)"; fi; }
expect_not() { if grep -qF -- "$2" "$3"; then fail "$1 (nemá tam být: $2)"; else ok "$1"; fi; }
abs()   { case "$1" in http*) echo "$1" ;; *) echo "$B$1" ;; esac; }
fetch() { curl -s -L -b "$JAR" -c "$JAR" -o "$2" "$(abs "$1")"; }
post()  { local url out; url="$(abs "$1")"; out="$2"; shift 2; curl -s -L -b "$JAR" -c "$JAR" -o "$out" "$@" "$url"; }
# Atribút elementu s daným id (Liferay môže vrátiť HTML na jednom riadku).
attr_of() {
    grep -oE "<[a-z]+ id=\"$1\"[^>]*>" "$2" | head -1 | grep -oE " $3=\"[^\"]*\"" | head -1 \
        | sed -E "s/^ $3=\"//; s/\"$//; s/&amp;/\&/g"
}
rc()    { docker compose exec -T redis redis-cli "$@" | tr -d '\r'; }
# Obe appky zdieľajú DB blacklistu – výsledok nesmie závisieť od toho, čo zmenil predchádzajúci beh.
reset_db() { docker compose exec -T postgres psql -q -U postgres -d crm < postgres/init/02-blacklist-seed.sql > /dev/null; }
login() {
    rm -f "$JAR"
    curl -s -c "$JAR" -b "$JAR" -o /dev/null -X POST "$B/sso/login" \
        -d "operator=$1" -d "customer=C-1001" -d "returnUrl=/web/crm/blacklist"
}

echo "Čekám na Liferay (první start 1-3 min)..."
code=000
for _ in $(seq 1 90); do
    # 200 vráti Liferay aj počas nasadzovania WAR-ov – čaká sa, kým portlet hlavičky naozaj vykreslí obsah.
    code="$(curl -s -o /dev/null -w '%{http_code}' "$L/web/crm/blacklist")"
    [ "$code" = "200" ] && curl -s "$L/web/crm/blacklist" | grep -q 'id="sessionDebug"' && break
    sleep 5
done
[ "$code" = "200" ] || { echo "Liferay neodpovídá (poslední HTTP $code)"; exit 1; }

reset_db

echo "== 1. Bez přihlášení"
rm -f "$JAR"
fetch "$L/web/crm/blacklist" "$WORK/anon.html"
expect "blacklist bez SSO ukáže výzvu k přihlášení" 'id="loginLink"' "$WORK/anon.html"
expect "hlavička je vložená z theme (runtime portlet)" 'id="sessionDebug"' "$WORK/anon.html"

echo "== 2. Přihlášení v SSO mocku (správce, zákazník C-1001)"
login spravca
SSO_ID="$(grep SSO_ID "$JAR" | awk '{print $7}')"
if [ -n "$SSO_ID" ]; then ok "cookie SSO_ID=$SSO_ID"; else fail "cookie SSO_ID chybí"; fi
fetch "$L/web/crm/blacklist" "$WORK/search.html"
expect "hlavička ukazuje operátora" 'id="headerOperator"' "$WORK/search.html"
expect "operátor Správce" 'Správce' "$WORK/search.html"
expect "formulář vyhledávání" 'id="searchForm"' "$WORK/search.html"

echo "== 3. Diakritika v action fázi"
post "$(attr_of searchForm "$WORK/search.html" action)" "$WORK/result1.html" \
    -d "firstName=V%C4%9Bra" -d lastName= -d identifierValue= -d state=
# Bajty UTF-8 explicitne: curl na Windows by z argumentu „Věra“ poslal %E1 (ANSI kódová stránka), prehliadač pošle UTF-8.
expect "Věra nalezena" 'id="detail-2"' "$WORK/result1.html"

echo "== 4. Vyhledávání a detail"
fetch "$L/web/crm/blacklist" "$WORK/search2.html"
post "$(attr_of searchForm "$WORK/search2.html" action)" "$WORK/result2.html" \
    -d firstName= -d lastName=Vzork -d identifierValue= -d state=
expect "výsledek: záznam 1" 'id="detail-1"' "$WORK/result2.html"
expect "výsledek: záznam 8" 'id="detail-8"' "$WORK/result2.html"
DETAIL="$(attr_of detail-1 "$WORK/result2.html" href)"
fetch "$DETAIL" "$WORK/detail.html"
expect "detail záznamu 1" 'AB123456' "$WORK/detail.html"

echo "== 5. Změna stavu (BLACKLIST_MODIFY)"
post "$(attr_of changeForm "$WORK/detail.html" action)" "$WORK/changed.html" -d detailId=1 -d state=INACTIVE -d validTo=
expect "hláška v hlavičce" 'Stav záznamu byl změněn.' "$WORK/changed.html"
expect "stav Neaktivní" 'id="recordState">Neaktivní' "$WORK/changed.html"

echo "== 6. Sdílená session v Redisu"
rc HGETALL "crm:session:$SSO_ID" > "$WORK/redis.txt"
expect "Redis: poslední otevřený záznam" 'Petr Vzorka' "$WORK/redis.txt"
expect "Redis: poslední kritéria" 'Vzork' "$WORK/redis.txt"
TTL="$(rc TTL "crm:session:$SSO_ID")"
if [ "${TTL:-0}" -gt 0 ] 2>/dev/null; then ok "TTL $TTL s"; else fail "TTL $TTL"; fi

echo "== 7. Dashboard: zrušení služby a JSON resource"
fetch /web/crm/dashboard "$WORK/products.html"
expect "produkty C-1001" 'id="deactivate-T-1"' "$WORK/products.html"
fetch "$(attr_of deactivate-T-1 "$WORK/products.html" href)" "$WORK/form.html"
expect "formulář pro Tarif Neomezený" 'Tarif Neomezený' "$WORK/form.html"
EARLIEST="$(abs "$(attr_of deactivationForm "$WORK/form.html" data-earliest-url)")&type=CUSTOMER_DEACTIVATION&noticeDate=2026-10-08"
curl -s -b "$JAR" -o "$WORK/earliest.json" "$EARLIEST"
expect "JSON nejdřívější datum" '"earliestDate":"2026-11-07"' "$WORK/earliest.json"
code="$(curl -s -o /dev/null -w '%{http_code}' "$EARLIEST")"
if [ "$code" = "401" ]; then ok "AJAX bez SSO cookie -> 401"; else fail "AJAX bez SSO cookie -> $code (očekáváno 401)"; fi
post "$(attr_of deactivationForm "$WORK/form.html" action)" "$WORK/done.html" \
    -d productId=T-1 -d type=CUSTOMER_POSTPRE -d "noticeDate=$(date -u +%F)" -d effectiveDate=2099-12-31
expect "potvrzení žádosti" 'id="deactivationDone"' "$WORK/done.html"
expect "hláška o přijetí" 'Žádost o zrušení byla přijata.' "$WORK/done.html"

echo "== 8. Feature flag REDIS: fasáda čte ze sdílené session"
rc SET crm:flags:session-read-source REDIS > /dev/null
fetch "$DETAIL" "$WORK/detail-redis.html"
expect "hlavička: zdroj čtení REDIS" 'zdroj čtení = REDIS' "$WORK/detail-redis.html"
expect "poslední záznam přečtený z Redisu" 'id="headerLastViewed"' "$WORK/detail-redis.html"
rc SET crm:flags:session-read-source PORTLET > /dev/null

echo "== 9. Výpadek Redisu neshodí stránku"
docker compose stop redis > /dev/null 2>&1
fetch "$L/web/crm/blacklist" "$WORK/no-redis.html"
expect "stránka běží bez Redisu" 'id="searchForm"' "$WORK/no-redis.html"
docker compose start redis > /dev/null 2>&1

echo "== 10. Přepsaná chybová stránka portálu (hook)"
fetch /web/crm/neexistuje "$WORK/404.html"
expect "hooked status.jsp" 'id="crmPortalError"' "$WORK/404.html"

echo "== 11. Čtenář: bez změn a bez dashboardu"
login citatel
fetch "$L/web/crm/blacklist" "$WORK/reader.html"
expect "čtenář vidí vyhledávání" 'id="searchForm"' "$WORK/reader.html"
expect_not "čtenář nevidí „Nový záznam“" 'id="gotoCreateForm"' "$WORK/reader.html"
fetch /web/crm/dashboard "$WORK/reader-dashboard.html"
expect "čtenář na dashboardu dostane 403" 'crm-error-403' "$WORK/reader-dashboard.html"

reset_db
rm -rf "$WORK"
echo
if [ "$FAILS" -eq 0 ]; then echo "SMOKE OK"; else echo "SMOKE: $FAILS selhání"; fi
exit "$FAILS"
