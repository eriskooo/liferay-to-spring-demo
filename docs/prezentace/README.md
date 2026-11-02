# Prezentace: postup migrace pro tým stávající aplikace

- `migrace-postup-pro-tym-legacy.pdf` – 12 slajdů k odsouhlasení postupu (souběh, sdílená session, sdílená DB, úpravy staré aplikace).
- `prezentace.html` – zdroj (HTML, 16:9); `img/` – screenshoty z běžícího dema (Chrome, přihlášený `spravca`, zákazník `C-1001`).

## Přegenerování PDF

```bash
"/c/Program Files/Google/Chrome/Application/chrome.exe" --headless=new --no-pdf-header-footer \
  --print-to-pdf="$PWD/docs/prezentace/migrace-postup-pro-tym-legacy.pdf" \
  "file:///$PWD/docs/prezentace/prezentace.html"
```

## Postup screenshotů

Stack běží (`bash docker/build-and-deploy.sh`), přepínač `bash docker/session-flag.sh REDIS`, DB po `smoke`/`e2e` ve výchozím stavu.

1. Dashboard v Liferay (`:8000/web/crm/dashboard`).
2. Záložka Blacklist → nginx přesměruje na `/crm/blacklist/search` (nová aplikace).
3. Hledat příjmení „Vzork“ → detail „Petr Vzorka“ → stav Neaktivní → Uložit.
4. Zpět na dashboard: hlavička ukáže poslední záznam z nové aplikace (Redis).
5. Starý portlet přímo na `:8080/web/crm/blacklist` → detail ukáže stav z DB.

Po pořízení vrátit DB do výchozího stavu: seed `docker/postgres/init/02-blacklist-seed.sql` (jako `reset_db` ve smoke skriptech).
