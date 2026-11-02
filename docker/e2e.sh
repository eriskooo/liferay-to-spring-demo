#!/usr/bin/env bash
# Charakterizačné e2e testy blacklistu (Playwright + Chrome) proti bežiacemu stacku: legacy | migrated | all.
# Ciele idú za sebou – zdieľajú jednu DB a každý test ju resetuje na seed. Ďalšie argumenty idú do Mavenu (-De2e.*).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
case "${1:-}" in
    legacy|migrated) TARGETS=("$1") ;;
    all) TARGETS=(legacy migrated) ;;
    *) echo "Použití: $0 legacy|migrated|all [-De2e.headed=true] [-De2e.browser=chromium]" >&2; exit 2 ;;
esac
shift
FAILS=0
for target in "${TARGETS[@]}"; do
    echo "== e2e: $target"
    mvn -q -B -f "$ROOT/e2e/pom.xml" verify -De2e.target="$target" "$@" || FAILS=$((FAILS + 1))
done
echo
if [ "$FAILS" -eq 0 ]; then echo "E2E OK"; else echo "E2E: $FAILS cíl(e) selhal(y) – artefakty v e2e/target/e2e-artifacts/"; fi
exit "$FAILS"
