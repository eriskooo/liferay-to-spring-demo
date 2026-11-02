# Legacy modul `customercare-portlets` – co a jak používá z Liferay

Cesty relativně k `$FE/customercare-portlets/src/main` (= `CP`). Věci ověřené vlastními příkazy
(grep/find) jsou označeny ✔, zbytek pochází z průzkumu agenta a není ručně ověřen.

## Rozsah
- ~1418 Java souborů (~227 k LOC), **1241 JSP** ✔, 219 `.tag`, 41 testů.
- **13 portletů** ve `webapp/WEB-INF/portlet.xml` ✔ (JSR-286 / Portlet 2.0).
  Oblasti: kontext zákazníka + hlavička + fake login, objednávky (order), dashboard, billing,
  požadavky zákazníka, scoring, blacklist nezákazníků, vyhledávání dokumentů, kontaktní osoby,
  2 vyřazené (decommissioned) portlety.
- Typ: **Spring Portlet MVC** (anotace) ✔ – žádný `MVCPortlet`, `GenericPortlet` ani JSF.
  `@Controller` 246, `@RenderMapping` 174, `@ActionMapping` 347, `@ResourceMapping` 598 ✔,
  `@SessionAttributes` 101, `@ModelAttribute` 745.
- Portlet třídy = vlastní podtřídy Spring `DispatcherPortlet`; každý portlet má vlastní Spring
  kontext `WEB-INF/<portlet>-portlet.xml` s `InternalResourceViewResolver` → `/WEB-INF/jsp/<portlet>/`.

## Klíčová lekce: portletové fáze
Portlet má **render / action / resource** fázi (`@RenderMapping`, `@ActionMapping`, `@ResourceMapping`).
Action zpracuje POST a *nemá* výstup; render pak vykreslí HTML; resource = AJAX/JSON/soubory.
Ve servletovém světě je to jeden request → v migraci: action+render = POST-Redirect-GET,
resource = samostatný GET/POST endpoint (REST).

## Liferay API (kolik ho doopravdy je)
- **Service Builder: nepoužívá se** (žádný `service.xml`) ✔. Perzistence je ve vlastním backendu.
- 119 Java souborů importuje `com.liferay.*` ✔. Hlavně `PortalUtil` (175 volání, zejména
  `getHttpServletRequest` – „dostaň se k servlet requestu“), `ThemeDisplay` (6 souborů),
  `LanguageUtil` (4 soubory, jen jazyk/země).
- **Permissions: Liferay se nepoužívá na business autorizaci** – vlastní ACL
  (ACL služba + JSP tagy typu `aclFilter` / `aclPageFilterTag privilegeType=...`).
  Liferay `PermissionChecker` jen ve startovacím hooku.
- `*LocalServiceUtil` (60×) jen ve **startup hooku**, který při startu portálu vytváří site, stránky,
  umísťuje portlety, Expando sloupce a Journal články (CMS obsah prázdného košíku).
- `liferay-hook.xml` ✔: jen `portal.properties` (startup event) + `custom-jsp-dir` (přepis
  `top_meta.jspf` a chybových stránek 403/404/500).
- **Friendly URL**: 5 route souborů v `resources/friendly/` ✔, mapper pro 5 portletů
  (2 vlastní mappery), např. dashboard `/{cuRefNo}/{caRefNo}/{tsRefNo}/{assetRefNo}/{page}/{action}`.
- Liferay taglibs v JSP téměř nejsou (`liferay-ui`/`aui` 0 použití, `liferay-theme` 29 souborů
  hlavně kvůli cestě k obrázkům theme). Dominují **vlastní taglibs** `portal:` (~7200 použití)
  a `common:` (~9500 použití). URL tagy uvnitř volají `MimeResponse.createActionURL()` a přetypují
  na `LiferayPortletURL` → **tady je skutečná závislost na Liferay, skrytá v tazích**.
- Theme: samostatný WAR, portlet WAR ho vyžaduje (`required-deployment-contexts`).
- JBoss: `jboss-deployment-structure.xml` závisí na modulu Liferay portálu a modulech SSO portálu.

## Session
- Fasáda session manager (interface ~780 LOC, impl ~1750 LOC) ve `frontend-base-customization`,
  dědí ze třídy z externí knihovny (zdroj není k dispozici).
- Sdílená session se **SSO portálem** přes service locator (API je `provided` závislost).
- `getSessionManager()` voláno 414× ve 114 souborech. `PortletSession` 51× (hlavně `PORTLET_SCOPE`).

## Backend
- **Backend běží ve stejném JVM a stejném Spring kontextu** – `applicationContext.xml` importuje
  backend kontexty (DAO, služby, transakce, WS/REST integrační klienti). Žádný remoting/EJB.
  ⇒ „backend“ = Java interfacy v jaru, který v archivu chybí/je mimo podklady.

## Dispatch a interceptory
- Dashboard a order používají **vlastní HandlerMappingy**: controller se vybírá podle parametru
  `page` (registr stránek), resp. podle scénáře/kroku (page-flow pro objednávky).
- 18 interceptorů (kontext účtu, deeplinky, kopírování render parametrů po action fázi,
  analytika, platby, logování…).
- Společný base controller ~1900 LOC (dědí z externí knihovny).

## Kandidáti na demo
| Feature | Kde | Velikost | Liferay závislost | Samostatnost |
|---|---|---|---|---|
| Blacklist nezákazníků | vlastní portlet, 4 controllery, 4 JSP | ~4 200 LOC | nepřímá (URL tagy, base controller, dispatcher) | **nejvyšší** – standardní annotation mapping, search→detail→create, validátory, ACL, 1 backend služba |
| Plánované odstávky | stránka v dashboard portletu | ~570 LOC | žádná přímá; závisí na dashboard dispatchi | střední – volá integrační klienty |
| Zrušení služby (deaktivace tarifu) | stránka v dashboard portletu + manager | ~3 800 LOC | `PortalUtil`, session manager, dashboard dispatch | nízká – 7+ backend služeb, velký manager |
