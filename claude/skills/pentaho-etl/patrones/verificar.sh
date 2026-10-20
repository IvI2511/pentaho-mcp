#!/bin/sh
# verificar.sh - runs every pattern of this folder with Kitchen (PDI 9.4, macOS / Linux) and checks the
# result in its SQLite database with verificar.py. Prints OK / FALLA per pattern.
#
# Usage, from this folder:
#   PDI_HOME=/opt/pdi/data-integration sh verificar.sh
#   PDI_HOME=... sh verificar.sh 03 04          # only some patterns
#
# - Every pattern starts from scratch: its salida/ folder is deleted first.
# - KETTLE_HOME points to a folder of its own (default $TMPDIR/kettle-home-patrones), so ~/.kettle is
#   left alone.
# - One JVM at a time. On Linux it first waits (up to MAX_WAIT_MINUTES) until MIN_FREE_MB are available.
# - kitchen.sh is run through /bin/sh, so it works even if the zip lost its execute bit.
# - The log of every run stays in <pattern>/salida/logs/. Needs python3 (standard library only).

set -u
AQUI=$(cd "$(dirname "$0")" && pwd)
: "${PDI_HOME:?set PDI_HOME to the data-integration folder}"
KITCHEN="$PDI_HOME/kitchen.sh"
[ -f "$KITCHEN" ] || { echo "kitchen.sh not found in $PDI_HOME" >&2; exit 2; }
PYTHON=${PYTHON:-python3}
MIN_FREE_MB=${MIN_FREE_MB:-2048}
MAX_WAIT_MINUTES=${MAX_WAIT_MINUTES:-10}
KETTLE_HOME=${KETTLE_HOME:-${TMPDIR:-/tmp}/kettle-home-patrones}
export KETTLE_HOME
mkdir -p "$KETTLE_HOME/.kettle"
# Small data: a smaller heap than the default 1-2 GB.
PENTAHO_DI_JAVA_OPTIONS=${PENTAHO_DI_JAVA_OPTIONS:--Xms256m -Xmx1024m}
export PENTAHO_DI_JAVA_OPTIONS

wait_free_memory() {
  [ -r /proc/meminfo ] || return 0          # macOS: no check
  waited=0
  while :; do
    free_mb=$(awk '/^MemAvailable:/ { print int($2 / 1024) }' /proc/meminfo)
    [ -z "$free_mb" ] && return 0
    [ "$free_mb" -ge "$MIN_FREE_MB" ] && return 0
    if [ "$waited" -ge $((MAX_WAIT_MINUTES * 60)) ]; then
      echo "    (only $free_mb MB free after $MAX_WAIT_MINUTES min: running anyway)"
      return 0
    fi
    echo "    waiting for memory: $free_mb MB free, $MIN_FREE_MB needed..."
    sleep 15
    waited=$((waited + 15))
  done
}

# run_job <pattern folder> <job.kjb> <run name> [NAME=value ...]  -> sets LAST_LOG, returns the exit code
run_job() {
  folder=$1; job=$2; name=$3; shift 3
  wait_free_memory
  logs="$AQUI/$folder/salida/logs"
  mkdir -p "$logs"
  LAST_LOG="$logs/$name.log"
  set -- "-file=$AQUI/$folder/$job" "-level=Basic" "-logfile=$LAST_LOG" "$@"
  # The remaining arguments are NAME=value: prefix them with -param:
  n=$#; i=0
  while [ $i -lt $n ]; do
    arg=$1; shift
    case $arg in
      -*) set -- "$@" "$arg" ;;
      *) set -- "$@" "-param:$arg" ;;
    esac
    i=$((i + 1))
  done
  /bin/sh "$KITCHEN" "$@" > "$logs/$name.console.txt" 2>&1
  code=$?
  printf '    %-28s exit %s\n' "$name" "$code"
  return $code
}

check() {
  "$PYTHON" -I "$AQUI/verificar.py" "$1" "$AQUI/$2" "$LAST_LOG"
}

SUMMARY=""
FAILED=0
record() {
  if [ "$2" -eq 0 ]; then SUMMARY="$SUMMARY
  OK     $1"; else SUMMARY="$SUMMARY
  FALLA  $1"; FAILED=1; fi
}

wanted() {
  [ -z "$ONLY" ] && return 0
  case " $ONLY " in *" $1 "*) return 0 ;; esac
  return 1
}
ONLY="$*"

start_pattern() {
  echo ""
  echo "== $1"
  rm -rf "$AQUI/$1/salida"
}

# Each pattern: runs in order; a Kitchen error stops the pattern, a failed check marks it FALLA.
if wanted 01; then
  f=01_csv_a_staging; start_pattern $f; r=0
  run_job $f 01_csv_a_staging.kjb run1 &&
  run_job $f 01_csv_a_staging.kjb run2 && check 01 $f || r=1
  record $f $r
fi

if wanted 02; then
  f=02_deduplicacion; start_pattern $f; r=0
  run_job $f 02_deduplicacion.kjb run1 && check 02 $f || r=1
  record $f $r
fi

if wanted 03; then
  f=03_carga_incremental; start_pattern $f; r=0
  run_job $f 03_preparar_origen.kjb source_batch1 LOTE=1 &&
  run_job $f 03_carga_incremental.kjb run1 && { check 03-1 $f || r=1; } &&
  run_job $f 03_preparar_origen.kjb source_batch2 LOTE=2 &&
  run_job $f 03_carga_incremental.kjb run2 && { check 03-2 $f || r=1; } &&
  run_job $f 03_carga_incremental.kjb run3_no_new_data && { check 03-3 $f || r=1; } || r=1
  record $f $r
fi

if wanted 04; then
  f=04_scd_tipo2; start_pattern $f; r=0
  run_job $f 04_scd_tipo2.kjb run1_day1 && { check 04-1 $f || r=1; } &&
  run_job $f 04_scd_tipo2.kjb run2_day2 ARCHIVO_CLIENTES=clientes_dia2.csv FECHA_CARGA=2026-10-15 &&
  { check 04-2 $f || r=1; } &&
  run_job $f 04_scd_tipo2.kjb run3_day2_again ARCHIVO_CLIENTES=clientes_dia2.csv FECHA_CARGA=2026-10-15 &&
  { check 04-3 $f || r=1; } || r=1
  record $f $r
fi

if wanted 05; then
  f=05_hecho_con_claves; start_pattern $f; r=0
  run_job $f 05_hecho_con_claves.kjb run1_batch1 LOTE=1 && { check 05-1 $f || r=1; } &&
  run_job $f 05_hecho_con_claves.kjb run2_batch2 LOTE=2 && { check 05-2 $f || r=1; } || r=1
  record $f $r
fi

echo ""
echo "== Summary$SUMMARY"
exit $FAILED
