# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

A **learning demo** of migrating a legacy customer-care CRM frontend (Liferay 6.2 portlets) to Spring Boot.
The repository is meant for GitHub; the original client materials live in `input/`, which is
**git-ignored and read-only** — never modify, move or commit anything there.

Output layout (outside `input/`):
- `docs/` — `ANALYZA.md` (analysis of the legacy project), `MIGRACIA.md` (before → after, mapping table),
  `docs/superpowers/` (specs and plans).
- `poznamky/` — a portable **knowledge base**: the user will import it into the real project to orient
  themselves and carry out the migration. Keep it practical (where to look, concepts → replacements, playbook, pitfalls)
  and add to it as you learn. Index: `poznamky/README.md`; abbreviations and anonymization: `poznamky/00-konvencie.md`.
  `poznamky/LOCAL-*` (alias → real-name mapping, anonymization check script) is git-ignored and copied manually.
- `legacy/`, `sso-mock/`, `docker/` – the runnable "before" app on Liferay 6.2 (sub-project 1, done; see `legacy/README.md`).
- `crm-web/` – the migrated Spring Boot "after" app (sub-project 2, done; see `crm-web/README.md`, `docs/MIGRACIA.md`).
- `kb-graph/` – knowledge base in Neo4j: structure extracted from code + lessons, saved Cypher queries (bonus outside the migration project; see `kb-graph/README.md`).

Docs and notes (`README.md` files, `docs/*.md`, `poznamky/`) are written in **Czech**, like the source materials.
Exception: `docs/superpowers/` (specs and plans) stay in Slovak as historical records.

## Status (2026-10-16)

- Phase 1 (analysis) done: `docs/ANALYZA.md`, knowledge base in `poznamky/`.
- Phase 2 done as two sub-projects (the original 2026-10-08 spec/plan `*-liferay-migracia-demo*` is superseded by them;
  its user decisions still hold: Spring MVC + JSP with same-interface tags, Java 21, Spring Boot 4.1.1, security unchanged):
  1. Legacy "before" app – spec + plan `docs/superpowers/*/2026-10-12-legacy-liferay-app*`.
  2. Spring Boot "after" app – spec + plan `docs/superpowers/*/2026-10-12-crm-web-migration*`. Only the blacklist is migrated;
     the dashboard (incl. service cancellation) stays in Liferay.
  3. Characterization e2e tests – spec + plan `docs/superpowers/*/2026-10-16-e2e-charakterizacne-testy*`. Playwright Java
     suite `e2e/` written on legacy, green on both apps (UI + DB + audit log). Introduced shared Postgres for the
     blacklist (legacy Spring JDBC DAO per table like the original, crm-web JPA/Hibernate), audit log contract,
     file logs. Decisions and pitfalls are summarized in `poznamky/12-*` §5–6.
  4. Bonus, NOT part of the migration project: knowledge base in a graph (Neo4j learning demo, 2026-10-21) – spec + plan `docs/superpowers/*/2026-10-21-kb-graph-neo4j*`.
     `kb-graph/`: Java extractor generates the repo structure from code (POM, compose, nginx, portlet.xml, endpoints,
     DDL, `poznamky/*.md`) into `graph/structure.json`; hand-written lessons in `lessons/*.json` (every pitfall of
     `poznamky/07` + concept map `05`) anchored to structure nodes; `load` validates anchors, wipes and refills Neo4j
     (disposable DB); 11 saved queries → generated Browser guide. Adding a pitfall to `poznamky/07` requires a lesson
     in `kb-graph/lessons/pitfalls.json` (test `RealLessonsTest` fails otherwise).
- Next (user request 2026-10-16): rewrite root `README.md` as a project-lead showcase of the migration – step-by-step
  migration plan (current step: e2e safety net), strategy, estimates, technology presentation.
- Git: the user allows commits only when they explicitly ask; never push.

## Runtime architecture (big picture)

All in `docker/docker-compose.yml`: nginx (:8000, single entry) → Liferay 6.2 (:8080, legacy portlets), `crm-web` (`/crm`),
SSO mock (`/sso`), Redis + RedisInsight (:5540), Postgres 16 (host **:5433** – 5432 is
often taken by a local PostgreSQL service; inside compose `postgres:5432`).
- **Strangler:** nginx redirects `/web/crm/blacklist` → `/crm/blacklist/search`; everything else under `/web/…` goes to Liferay.
  `crm-web` pages pull CSS from the Liferay theme via nginx, so they look unstyled outside :8000.
- **Identity:** owned by the SSO mock, both apps resolve it from the `SSO_ID` cookie (legacy via the shaded
  `sso-portal-api` JAR in Liferay `lib/ext`).
- **Shared session:** Redis hash `crm:session:{SSO_ID}`, same JSON contract in both apps. `crm-web` always reads/writes Redis;
  legacy writes PortletSession + Redis and reads per flag `crm:flags:session-read-source` (`PORTLET`|`REDIS`) – it must be
  `REDIS` for legacy to see what `crm-web` wrote. `crm-web` keeps working without Redis (no flash messages / last record).
- **Shared DB:** blacklist of legacy and crm-web lives in Postgres, schema `blacklist`, user `crm_app` (DML only).
  DDL + seed in `docker/postgres/init/` (one seed file for container init, Testcontainers tests, smoke and e2e reset).
  Legacy: Spring JDBC DAO per table + commons-dbcp2 (`-Dcrm.db.*`); crm-web: JPA (entities in `blacklist/jpa`, mapper,
  `ddl-auto=validate` in profile `jpa`, default `crm.blacklist.store=memory` so `@SpringBootTest`s run without DB).
  Dashboard stays in-memory.
- **Logs in files:** `docker/logs/liferay/customercare-portlet.log` (portlet's own `log4j.xml` – Liferay deploy injects
  a console-only `log4j.properties`) and `docker/logs/crm-web/crm-web.log`. Audit contract `AUDIT event=… k=v` (same in
  both apps, see `docs/MIGRACIA.md` §11).
- Legacy runtime: Liferay 6.2 CE GA6 on Java 8 – Zulu update must be ≤ 255 (ICU bug, see `poznamky/10*`).

## Anonymization (hard rule)

Everything outside `input/` is committed — including `poznamky/`, this file, and superpowers outputs. It must not contain:
client/vendor/people names, internal system names, internal URLs or hostnames, credentials, data from the logs,
or code copied from `input/`. "Before" examples are rewritten as minimal anonymized snippets.
Use the abbreviations from `poznamky/00-konvencie.md` (e.g. `$FE` for the unpacked frontend project, `<pkg>` for its base package).
Do not commit or push; the user does that.

Check before the user commits (Bash, repo root): `bash poznamky/LOCAL-anonymizacia.sh` — expected output: nothing (grep exit 1).
If the script is missing on this machine, it has to be copied manually together with `poznamky/LOCAL-aliasy.md`.

## Build & run

Prereqs: Docker Desktop (~3 GB RAM for Liferay), JDK 21, Maven 3.9, Git Bash. Every `docker/*.sh` has a `.ps1` twin.
- Tests: `mvn -q -B -f legacy/pom.xml verify`, `mvn -q -B -f sso-mock/pom.xml verify`,
  `mvn -q -B -f crm-web/pom.xml verify` (needs Docker – Redis via Testcontainers),
  legacy and crm-web `verify` need Docker (Testcontainers Postgres/Redis). E2e: `bash docker/e2e.sh legacy|migrated|all`
  (needs running stack + Google Chrome, or `-De2e.browser=chromium`) → `E2E OK`; never concurrently with smoke scripts
  (all reset the shared DB). Failure artifacts: `e2e/target/e2e-artifacts/`.
- One class: legacy `mvn -q -B -f legacy/pom.xml test -pl <module> -am -Dtest=ClassName -Dsurefire.failIfNoSpecifiedTests=false`
  (modules: `sso-portal-api`, `frontend-base-customization`, `customercare-portlets`, `customercare-theme`);
  crm-web `mvn -q -B -f crm-web/pom.xml test -Dtest=ClassName`.
- Stack: `bash docker/build-and-deploy.sh` (builds all three, compose up, hot-deploys into Liferay and **blocks until the
  portlets are deployed**, max 10 min; first start 1–3 min) – don't write own wait loops, then `bash docker/smoke.sh` → `SMOKE OK` and `bash docker/smoke-migration.sh` → `MIGRATION SMOKE OK`.
  Liferay admin :8080 `test@liferay.com`/`test`; demo operators `spravca` (all) / `citatel` (read-only); customers `C-1001`, `C-1002`.
- Shared session: `bash docker/show-session.sh`, `bash docker/session-flag.sh PORTLET|REDIS`.
- Knowledge graph (independent of the stack): `bash kb-graph/kb.sh up|load|check|guide|down` (Neo4j Browser :7474,
  `neo4j`/`kbdemo123`, guide server :7475); tests `mvn -q -B -f kb-graph/extractor/pom.xml verify` (needs Docker).
  After changing compose/nginx/POM/controllers/DDL/`poznamky` run `kb.sh extract` so `structure.json` stays current
  (`kb.sh check` → exit 1 when stale).
- Legacy modules compile with `--release 8` – Java 8 API only (no records, `var`, `List.of`, `isBlank`, `toList()`).

## "After" app architecture and conventions

- `crm-web/` Maven WAR module, context path `/crm`, package `com.example.crm`. Portlet phases map to Spring MVC:
  render = GET, action = POST + redirect (PRG), resource = AJAX endpoint (HTML fragment or JSON).
- Custom JSP tags `portal:*` / `common:*` keep the **legacy interface** but are implemented without Liferay
  (`web/tag/`, TLDs in `src/main/webapp/WEB-INF/tld/`); the session facade (`CrmSession`) and ACL keep the legacy contract;
  backend services are interfaces with in-memory implementations.
- Every JSP starts with `<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>` and has no own `page` directive.
- Identifiers in English; code comments and UI texts (`messages.properties`) in **Slovak** (this overrides the global
  "comments in Czech" rule for this repo); docs in Czech (see above). Comments explain the migration decision / legacy equivalent, not what the code does.

## The legacy project (in `input/`, for reference only)

- `$FE` = the unpacked `frontend` Maven module. Its `target/` dirs are build output, not source.
  The archive was corrupted: at least one backend JAR in `customercare-web/target/.../WEB-INF/lib` is missing, possibly more.
- `input/pom.xml` — root parent POM (Liferay 6.2.5, Spring 4.3.6, Hibernate 4.2, Java 1.8). Sibling modules and the private
  Maven repository are not available, so **the legacy code cannot be built here**.
- Modules: `customercare-portlets` (legacy Liferay portlet WAR, Spring Portlet MVC, ~1240 JSPs, custom taglibs, Liferay hooks),
  `frontend-base-customization` (shared base library), `customercare-theme` (Liferay theme), `customercare-web`
  (an earlier PoC: plain Spring MVC WAR, JSPs ported 1:1, taglib compatibility layer, backend services stubbed —
  **only `target/` exists, no `src/`**), `customercare-testing`, `one-portal-portlets` (prebuilt WAR only).
- `input/*.pptx`, `*.docx`, `*.html` + `*.py` generators — presentations and a Q&A document describing the migration approach.
- `input/*_war*.log` — runtime logs of the PoC (stubbed services, one JSP binding error).
