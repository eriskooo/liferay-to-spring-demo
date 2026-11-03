# Co je třeba vyjasnit před demem na reálném projektu

Postup: znalostní báze z dokumentace (s příkazem na refresh) → scan projektu → výběr úseku →
charakterizační e2e testy → smysluplné pokrytí testy → sjednocení logů s novou službou → migrace → prezentace.

Níže je to, co musí být jasné, aby ten postup dával výsledek, který obstojí před legacy týmem.
Není to seznam úkolů pro někoho – je to seznam věcí, které se musí někde rozhodnout nebo zjistit.

## 1. K čemu demo slouží

Demo má vyvolat rozhodnutí, ne jen ukázat, že to jde. Musí být jasné, jaké rozhodnutí to je
(pokračovat, zvolit architekturu, schválit postup), kdo ho dělá a podle čeho ho posoudí.
Z toho plyne, co demo ukáže a co vynechá, a také co by ho diskvalifikovalo, i kdyby fungovalo
(například zásah do legacy kódu nad dohodnutou míru nebo infrastruktura, kterou zákazník neprovozuje).

## 2. Co máme k dispozici

- **Kód:** všechny repozitáře, které úsek potřebuje (frontend, backend, sdílené knihovny), a možnost
  legacy lokálně sestavit – v přípravném demu to nešlo kvůli chybějícímu privátnímu Maven repozitáři.
- **Dokumentace:** které zdroje platí a které jsou historické. Confluence bývá zastaralá; vedle ní
  mají větší váhu provozní logy, DB schéma a Q&A s lidmi, kteří systém znají.
- **Pravidla práce:** kde smí ležet znalostní báze s interními názvy a zda a za jakých podmínek se smí
  nad kódem a dokumentací používat AI nástroje. Tohle rozhoduje o celém postupu, proto patří na začátek.

## 3. Kde a jak to poběží

Charakterizační testy potřebují běžící legacy aplikaci s databází, kterou lze vracet do známého stavu,
a testovací účty s různými rolemi. Forma výsledku (kontejner, WAR, kód v repozitáři) a prostředí se
dohodnou s legacy týmem; pro přípravu stačí vědět, že to bude existovat a kdo za to odpovídá.
Dále je třeba znát technologické mantinely (verze Javy a Spring Boot, přípustnost reverzní proxy pro
routování a Redis pro sdílenou session) – demo stojí na Java 21 + Boot 4.1 + nginx + Redis a u zákazníka
to může být jinak.

## 4. Jaký úsek vybrat

Kritéria: malý a uzavřený, zapisuje do DB (jinak e2e nad DB nemají co hlídat), reprezentativní
(session, vlastní taglib, ACL, AJAX – aby se výsledek dal extrapolovat), bez závislostí na nedostupných
backend službách, stabilní v legacy větvi (souběžné změny rozbíjejí charakterizační testy).
Preferovaný úsek legacy týmu má přednost, pokud kritéria splňuje – vlastní volba zvyšuje přijetí.

## 5. Co znamená „testy drží aplikaci pokupě“

- **Charakterizační e2e** (UI + DB + log) se píší na legacy a jsou akceptačním kritériem migrace: tytéž
  testy musí projít na nové službě. Scénáře vycházejí z toho, jak se úsek skutečně používá, ne z JSP.
- **Pokrytí 80 %** je třeba definovat: na kterém kódu (legacy úsek, nová služba, oba) a jak se měří.
  Smysluplnost = test popisuje chování z pohledu uživatele a dat, ne řádky; pokrytí se dá nafouknout bez hodnoty.
- **Nástroje** by měly navazovat na to, co u zákazníka už je, pokud něco je; jinak Playwright jako v demu.

## 6. Logy

Shoda logů 1:1 mezi legacy a novou službou vyžaduje zásah do legacy logování (audit kontrakt
`AUDIT event=… k=v`). To může kolidovat s pravidlem „legacy neměnit“ – musí to být výslovně dohodnuto.
Dále: jaký logovací stack a centrální sběr zákazník má (formát musí být kompatibilní), které události jsou
auditně povinné, a jak se shoda ověří automaticky (v demu e2e test čte log).

## 7. Napojení na běžící systém

Nová služba sdílí DB (uživatel s DML právy, schéma), vzhled (CSS z Liferay tématu přes proxy, jinak působí
jako regrese), session a identitu. Routování mezi legacy a novou službou musí mít triviální rollback –
to je hlavní argument pro legacy tým. Pokud mají být doloženy nefunkční vlastnosti (výkon, paměť),
je třeba je měřit, ne odhadovat.

## 8. Prezentace

Pořadí ukázky kopíruje argument: před → testy na legacy → sjednocené logy → po → tytéž testy na nové
službě → přepnutí routingu a rollback. Živé demo potřebuje stabilní prostředí a záložní záznam.
Legacy tým se zeptá „a co zbytek aplikace“ – připravit extrapolaci z úseku (odhad, rizika, pasti z `07-pasce.md`)
a odpovědi na očekávané námitky.

## Co je rozhodnuto (oznámit, ne vyjednávat)

- Samostatná Spring Boot služba, postupné routování přes proxy, samostatný proces; legacy měnit minimálně,
  výjimkou je sjednocení logů.
- Pořadí na úseku: charakterizační testy na legacy → logy → migrace → tytéž testy na nové službě.
- Výstup scanu: inventář, mapa konceptů a pasti ve struktuře tohoto adresáře; znalostní báze ukotvená v kódu,
  aby odhalila rozpor mezi dokumentací a skutečností, s refreshem, který nepřepíše ručně psané poznatky.

## Blokátory startu

Bez těchto čtyř věcí se nezačíná: cíl dema (§1), sestavitelný kód a pravidla pro AI nástroje (§2),
prostředí s resetovatelnou DB (§3), vybraný úsek (§4). Ostatní lze dořešit průběžně.
