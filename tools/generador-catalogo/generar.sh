#!/usr/bin/env bash
# Regenera el catalogo de steps / job entries de PDI 9.4 a partir de la instalacion.
# Uso (Git Bash):  bash generar.sh ["C:/ruta/a/data-integration"]   (o defini PDI_HOME)
# Salida: ../steps.json, ../steps/<id>.xml, ../job-entries.json, ../job-entries/<id>.xml
#         (INDICE.md se genera aparte con indice.py)
set -euo pipefail

PDI="${1:-${PDI_HOME:?pasa la ruta de data-integration o defini PDI_HOME}}"
GEN="$(cd "$(dirname "$0")" && pwd -W 2>/dev/null || pwd)"   # ruta Windows si estamos en Git Bash
CAT="$(cd "$GEN/../../skills/pentaho-etl/catalogo" && pwd -W 2>/dev/null || pwd)"
KHOME="${KETTLE_HOME_PRUEBAS:-${TMP:-/tmp}/kettle-home-pruebas}"   # nunca ~/.kettle
mkdir -p "$KHOME/.kettle"

OPENS="--add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/sun.net.www.protocol.jar=ALL-UNNAMED"
# libswt solo para que el escaneo de los jars "-ui" de plugins/ no loguee ClassNotFound de los *Dialog
CP="$GEN/build;$PDI/lib/*;$PDI/libswt/win64/*;$PDI/classes"
PROPS=(-DKETTLE_HOME="$KHOME" -DKETTLE_PLUGIN_BASE_FOLDERS="$PDI/plugins" -Dfile.encoding=UTF-8
       -DKETTLE_DISABLE_CONSOLE_LOGGING=N)

echo "== compilando"
mkdir -p "$GEN/build"
javac --release 8 -proc:none -nowarn -encoding UTF-8 -cp "$PDI/lib/*" -d "$GEN/build" "$GEN/GenerarCatalogo.java"

rm -rf "$CAT/steps" "$CAT/job-entries"

cd "$PDI"   # cwd = data-integration, igual que Spoon (rutas relativas como ./plugins, ./simple-jndi)
echo "== pasada 1: nombres en es_ES (lo que muestra Spoon en este equipo)"
java $OPENS "${PROPS[@]}" -cp "$CP" GenerarCatalogo "$CAT" --locale es_ES --solo-nombres > "$GEN/build/log_es.txt" 2>&1
echo "== pasada 2: catalogo en en_US + XML por defecto"
java $OPENS "${PROPS[@]}" -cp "$CP" GenerarCatalogo "$CAT" --locale en_US > "$GEN/build/log_en.txt" 2>&1 || { tail -40 "$GEN/build/log_en.txt"; exit 1; }
grep -E "^(Steps|Job entries):" "$GEN/build/log_en.txt"

echo "== INDICE.md"
python "$GEN/indice.py" "$CAT"
