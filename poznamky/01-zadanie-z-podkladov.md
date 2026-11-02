# Zadání podle podkladů (prezentace + Q&A dokument)

Zdroj: dvě prezentace v `input/` (`*-AI.pptx` a její verze s komentáři klienta – 14 slajdů,
4 komentáře) a Q&A dokument (obsah je v `BLOCKS` v generátoru `input/*-odpovedi-komentare.py`).
Zkratky viz [00-konvencie.md](00-konvencie.md).

## Co podklady říkají o cíli
- Cíl = **odstranit Liferay** (nepodporované/rizikové knihovny) při **zachování funkcionality 1:1**
  a **zachování technické filozofie 1:1**: žádné SPA, žádný přesun logiky FE↔BE, stavovost
  (session) zůstává (slide 2).
- Cílový stack v podkladech: **Spring MVC + JSP na plain Tomcat** (slide 5), ne Spring Boot,
  ne REST+SPA. JSP se kopírují/sdílejí, portlet tagy se reimplementují bez Liferay (slide 13).
- Server services a backend integrace „necháváme“ (slide 4).
- Theme → statické CSS/JS ve WAR. Liferay CMS se nepoužívá; překlady jdou z externího CMS
  a do JSP se mají injektovat překladové klíče.
- Session: „duální session“ Liferay → Spring Session (Redis), jediný zápisový bod = session manager
  (fasáda), feature flag pro zdroj čtení (slide 12). Prezentace uvádí 375+ controllerů
  a 30+ objektů v session (neověřená čísla).
- Deeplinker/Backlinker (generování interních odkazů a „zpět“) → vlastní URL builder;
  podle prezentace „je třeba ještě rozpracovat“.
- Klient se ptal, zda to poběží na JBoss (komentář). Rozhodnuto 2026-10-21: JBoss není podmínkou, nová aplikace
  běží jako samostatný proces (Spring Boot 4 na JBoss ani nejde, viz [07-pasce.md](07-pasce.md) bod 30).

## PoC ukázky v prezentaci
- Ukázka 1: seznam tarifů/produktů → klik → formulář „Zrušení služby“ (datum podání,
  typ: převod na jiného zákazníka / zrušení / převod na předplacenou kartu, „ke dni“).
- Ukázka 1 obsahuje i obrazovku „Plánované odstávky služeb“ (filtr podle ID služby + tabulka
  událostí s detailem) – na mock datech.
- Ukázka 2: původní Liferay verze vs. zmigrovaná PoC verze téhož formuláře „Zrušení služby“.
  Pozorování ze screenshotů: Liferay verze je vnořená v layoutu portálu (horní menu, zákazník
  v hlavičce – to dodává theme + jiné portlety), PoC verze má vlastní jednoduchou hlavičku.
  PoC verze navíc zobrazuje pole „Ke dni“ (datum účinnosti), Liferay screenshot ne – pravděpodobně
  se zobrazuje podmíněně podle zvoleného typu (neověřeno).
- Slide 13: tabulka tagů, které bylo třeba přepsat (URL tagy → `PortletResponse.createRenderURL()`
  / `createResourceURL()`, i18n tag → `LanguageUtil` + `ThemeDisplay`, ostatní nepřímo nebo vůbec).
- Druhá prezentace: Non-Customer Blacklist (vyhledávání + vytvoření), Documents,
  Customer Request History – na mock datech.

## AS-IS architektura (slide 14, obrázek)
Prohlížeč (HTML/JS, AJAX) → JBoss + Liferay: JSP view, controller, services (Java 8, Spring 4.3.6,
Hibernate), DAO/JMX + JPA → Oracle 19; navenek message broker, integrační vrstva (REST/SOAP),
SSO portál (session).

## Co v podkladech NENÍ (ověřeno fulltextem prezentací a Q&A)
- **JSF** – v projektu ani v podkladech (0 `.xhtml`, 0 `faces-config.xml`, 0 `javax.faces`).
  Portlety jsou Spring Portlet MVC + JSP.
- **Návrh security** – žádný (Spring Security ani jiné). Pouze: identita uživatele je zapouzdřena
  v session fasádě (nastavení externího/interního uživatele a zákazníka); kontrakt fasády se má
  zachovat a implementace vyměnit (pryč od `PortletSession`/Liferay). Autorizace = existující
  vlastní ACL. ⇒ Při migraci security **neměnit**, dokud nepadne architektonické rozhodnutí.

## Rozhodnutí pro demo (uživatel, 2026-10-08)
- Držet se podkladů: **Spring MVC controllery + JSP** (server-side), AJAX/resource volání → JSON
  endpointy (to je „REST“ část). Žádné SPA, žádné JSF.
- Rozsah: blacklist nezákazníků + zjednodušené zrušení služby. Java 21.
- Security: neměnit – session fasáda + fake login + vlastní ACL jako v legacy.
