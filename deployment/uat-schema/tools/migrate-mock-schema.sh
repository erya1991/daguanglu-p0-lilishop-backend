#!/bin/sh
# R3 MOCK/TEST database migration. A human explicitly selects two isolated schemas.
set -eu
umask 077
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
MYSQL=${P0_MYSQL_COMMAND:-mysql}
PLAN="$ROOT/tools/ddl-plan-v2.tsv"
PLAN_SHA=92b74af6ee6d88f3ac450fb4a0ef8862129960255df5f497839a237878a22b78
SOURCE_UUID=dc2fc878-dbf0-11f1-9c13-5ac1b87338ef
CONFIG= UUID= BUSINESS= MOCK= CONFIRM= CHECK_ONLY=0 PHASE=before-bootstrap
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
while [ "$#" -gt 0 ]; do
  case "$1" in
    --defaults-file) [ "$#" -ge 2 ] || fail "missing defaults file"; CONFIG=$2; shift 2 ;;
    --expected-server-uuid) [ "$#" -ge 2 ] || fail "missing UUID"; UUID=$2; shift 2 ;;
    --business-db) [ "$#" -ge 2 ] || fail "missing business database"; BUSINESS=$2; shift 2 ;;
    --mock-db) [ "$#" -ge 2 ] || fail "missing mock database"; MOCK=$2; shift 2 ;;
    --confirm-test-mock) CONFIRM=YES; shift ;;
    --check-only) CHECK_ONLY=1; shift ;;
    --phase) [ "$#" -ge 2 ] || fail "missing phase"; PHASE=$2; shift 2 ;;
    *) fail "unknown argument: $1" ;;
  esac
done
[ "$CONFIRM" = YES ] || fail "explicit --confirm-test-mock is required"
case "$PHASE" in before-bootstrap|complete) ;; *) fail "phase must be before-bootstrap or complete" ;; esac
[ -f "$CONFIG" ] && [ -r "$CONFIG" ] || fail "readable mysql defaults file required"
case "$CONFIG" in /*) ;; *) fail "defaults file must be an absolute path" ;; esac
for name in "$BUSINESS" "$MOCK"; do
  case "$name" in ''|[0-9]*|*[!A-Za-z0-9_]*) fail "database name must contain only letters, numbers and underscore and not start with a number" ;; esac
  [ "$(printf '%s' "$name" | wc -c)" -le 64 ] || fail "database name is too long"
done
[ "$BUSINESS" != "$MOCK" ] || fail "business and mock databases must be distinct"
case "$UUID" in ''|*[!0-9A-Fa-f-]*) fail "invalid expected server UUID" ;; esac
[ "$(printf '%s' "$UUID" | wc -c)" -eq 36 ] || fail "invalid expected server UUID"
command -v "$MYSQL" >/dev/null 2>&1 || fail "missing MySQL command: $MYSQL"
for cmd in sha256sum awk grep mktemp sed tr wc basename dirname cat; do command -v "$cmd" >/dev/null 2>&1 || fail "missing command: $cmd"; done
[ -f "$PLAN" ] || fail "migration plan missing"
[ "$(sha256sum "$PLAN" | awk '{print $1}')" = "$PLAN_SHA" ] || fail "migration plan digest mismatch"
TAB=$(printf '\t')
TOTAL=0
while IFS="$TAB" read -r file target checksum; do
  [ -n "$file" ] || fail "empty migration entry"
  case "$file" in database/*/*.sql) ;; *) fail "invalid migration path" ;; esac
  case "$file" in *..*|*[!A-Za-z0-9_./-]*) fail "unsafe migration path" ;; esac
  case "$target" in business|mock) ;; *) fail "unknown migration target" ;; esac
  case "$checksum" in *[!0-9a-f]*|'') fail "invalid migration checksum" ;; esac
  [ "$(printf '%s' "$checksum" | wc -c)" -eq 64 ] || fail "invalid migration checksum length"
  [ -f "$ROOT/$file" ] || fail "missing migration: $file"
  [ "$(sha256sum "$ROOT/$file" | awk '{print $1}')" = "$checksum" ] || fail "checksum mismatch: $file"
  TOTAL=$((TOTAL+1))
done < "$PLAN"
[ "$TOTAL" -eq 50 ] || fail "expected 50 migrations"
mysqlq() {
  "$MYSQL" --defaults-extra-file="$CONFIG" --skip-force --batch --raw --skip-column-names --connect-timeout=10 "$@"
}
actual=$(mysqlq --execute='SELECT @@server_uuid;') || fail "cannot connect to the selected server"
[ "$(printf '%s' "$actual" | tr 'A-F' 'a-f')" = "$(printf '%s' "$UUID" | tr 'A-F' 'a-f')" ] || fail "server UUID mismatch"
[ "$(printf '%s' "$actual" | tr 'A-F' 'a-f')" != "$SOURCE_UUID" ] || fail "the original UAT server is not a valid target"
tmp=$(mktemp -d '/tmp/p0-migrate.XXXXXXXX') || fail "cannot create temporary directory"
cleanup() {
  rm -f -- "$tmp/business.tablecount" "$tmp/business.history" "$tmp/mock.tablecount" "$tmp/mock.history" "$tmp/migrate.sql" "$tmp/mysql.out" "$tmp/mysql.err"
  rmdir -- "$tmp" 2>/dev/null || true
}
trap cleanup EXIT HUP INT TERM
BT=$(printf '\140')
for pair in business mock; do
  if [ "$pair" = business ]; then db=$BUSINESS; else db=$MOCK; fi
  count=$(mysqlq --database="$db" --execute="SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE();") || fail "database unavailable: $db"
  has_history=$(mysqlq --database="$db" --execute="SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='li_p0_schema_history';") || fail "cannot inspect target: $db"
  case "$count:$has_history" in *[!0-9:]*|*::*|'') fail "unexpected table metadata: $db" ;; esac
  printf '%s' "$count" > "$tmp/$pair.tablecount"
  if [ "$has_history" -eq 0 ]; then
    [ "$count" -eq 0 ] || fail "target is nonempty and has no migration history: $db"
    : > "$tmp/$pair.history"
  elif [ "$has_history" -eq 1 ]; then
    mysqlq --database="$db" --execute='SELECT migration,checksum,status FROM li_p0_schema_history ORDER BY migration;' > "$tmp/$pair.history" || fail "cannot read migration history: $db"
    if [ ! -s "$tmp/$pair.history" ] && [ "$count" -ne 1 ]; then fail "empty migration history with pre-existing tables: $db"; fi
    while IFS="$TAB" read -r name sum status; do
      [ "$status" = DONE ] || fail "incomplete migration requires investigation: $name"
      valid=$(awk -F '\t' -v n="$name" -v s="$sum" -v t="$pair" '{c=split($1,a,"/");if(a[c]==n&&$2==t&&$3==s)f=1} END{print f+0}' "$PLAN")
      [ "$valid" -eq 1 ] || fail "unknown or mismatched history entry: $name"
    done < "$tmp/$pair.history"
  else fail "unexpected history state: $db"; fi
done
if [ "$PHASE" = complete ]; then
  roots=$(mysqlq --database="$BUSINESS" --execute="SELECT COUNT(*) FROM li_menu WHERE name='uat-root' AND delete_flag=0;") || fail "bootstrap root is not available"
  [ "$roots" = 1 ] || fail "complete phase requires exactly one initialized uat-root menu"
fi
pending_seen=0
PENDING=0
SKIPPED=0
while IFS="$TAB" read -r file target checksum; do
  name=$(basename -- "$file")
  if [ "$PHASE" = before-bootstrap ] && [ "$name" = UAT_008_resident_menu.sql ]; then continue; fi
  found=$(awk -F '\t' -v n="$name" -v s="$checksum" '$1==n&&$2==s&&$3=="DONE"{print 1}' "$tmp/$target.history")
  if [ "$found" = 1 ]; then
    [ "$pending_seen" -eq 0 ] || fail "migration history is not a complete prefix: $name"
    SKIPPED=$((SKIPPED+1))
  else pending_seen=1; PENDING=$((PENDING+1)); fi
done < "$PLAN"
if [ "$CHECK_ONLY" -eq 1 ]; then
  printf 'CHECK_OK migrations=%s pending=%s skipped=%s; no schema changed\n' "$TOTAL" "$PENDING" "$SKIPPED"
  exit 0
fi
lock1=$(printf '%s' "$BUSINESS" | sha256sum | awk '{print substr($1,1,32)}')
lock2=$(printf '%s' "$MOCK" | sha256sum | awk '{print substr($1,1,32)}')
sql="$tmp/migrate.sql"
{
  printf 'USE %s%s%s;\n' "$BT" "$BUSINESS" "$BT"
  printf 'CREATE TEMPORARY TABLE p0_migration_guard(ok TINYINT NOT NULL CHECK(ok=1));\n'
  printf "INSERT INTO p0_migration_guard VALUES(GET_LOCK('p0_mig_%s',0));\n" "$lock1"
  printf "INSERT INTO p0_migration_guard VALUES(GET_LOCK('p0_mig_%s',0));\n" "$lock2"
  for pair in business mock; do
    if [ "$pair" = business ]; then db=$BUSINESS; else db=$MOCK; fi
    count=$(cat "$tmp/$pair.tablecount")
    printf "INSERT INTO p0_migration_guard SELECT IF(COUNT(*)=%s,1,0) FROM information_schema.TABLES WHERE TABLE_SCHEMA='%s';\n" "$count" "$db"
    printf 'CREATE TABLE IF NOT EXISTS %s%s%s.li_p0_schema_history(migration VARCHAR(100) PRIMARY KEY,checksum CHAR(64) NOT NULL,status VARCHAR(16) NOT NULL,started_at DATETIME(6) NOT NULL,completed_at DATETIME(6) NULL) ENGINE=InnoDB;\n' "$BT" "$db" "$BT"
    history_count=$(awk 'END{print NR+0}' "$tmp/$pair.history")
    printf 'INSERT INTO p0_migration_guard SELECT IF(COUNT(*)=%s,1,0) FROM %s%s%s.li_p0_schema_history;\n' "$history_count" "$BT" "$db" "$BT"
  done
  while IFS="$TAB" read -r file target checksum; do
    name=$(basename -- "$file")
    if [ "$target" = business ]; then db=$BUSINESS; else db=$MOCK; fi
    found=$(awk -F '\t' -v n="$name" -v s="$checksum" '$1==n&&$2==s&&$3=="DONE"{print 1}' "$tmp/$target.history")
    if [ "$found" = 1 ]; then
      printf "INSERT INTO p0_migration_guard SELECT IF(COUNT(*)=1,1,0) FROM %s%s%s.li_p0_schema_history WHERE migration='%s' AND checksum='%s' AND status='DONE';\n" "$BT" "$db" "$BT" "$name" "$checksum"
    else
      printf "INSERT INTO p0_migration_guard SELECT IF(COUNT(*)=0,1,0) FROM %s%s%s.li_p0_schema_history WHERE migration='%s';\n" "$BT" "$db" "$BT" "$name"
    fi
  done < "$PLAN"
  while IFS="$TAB" read -r file target checksum; do
    name=$(basename -- "$file")
    if [ "$PHASE" = before-bootstrap ] && [ "$name" = UAT_008_resident_menu.sql ]; then continue; fi
    found=$(awk -F '\t' -v n="$name" -v s="$checksum" '$1==n&&$2==s&&$3=="DONE"{print 1}' "$tmp/$target.history")
    [ "$found" = 1 ] && continue
    if [ "$target" = business ]; then db=$BUSINESS; else db=$MOCK; fi
    printf 'USE %s%s%s;\n' "$BT" "$db" "$BT"
    printf "INSERT INTO li_p0_schema_history(migration,checksum,status,started_at) VALUES('%s','%s','STARTED',UTC_TIMESTAMP(6));\n" "$name" "$checksum"
    cat "$ROOT/$file"
    printf "\nUPDATE li_p0_schema_history SET status='DONE',completed_at=UTC_TIMESTAMP(6) WHERE migration='%s' AND checksum='%s' AND status='STARTED';\n" "$name" "$checksum"
  done < "$PLAN"
} > "$sql"
if ! mysqlq < "$sql" > "$tmp/mysql.out" 2> "$tmp/mysql.err"; then
  cat "$tmp/mysql.err" >&2
  fail "migration stopped; inspect target history and any partially applied DDL before recovery"
fi
printf 'MIGRATION_OK migrations=%s applied=%s skipped=%s; business=%s mock=%s\n' "$TOTAL" "$PENDING" "$SKIPPED" "$BUSINESS" "$MOCK"
