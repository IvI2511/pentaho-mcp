#!/usr/bin/env bash
# Compila el plugin de Spoon desde el código del repo en macOS o Linux: lo mismo que spoon-plugin/build.ps1
# (que es de Windows: jar.exe, libswt\win64 por ruta, «;» en el classpath), sin el zip del release. Lo usa
# .github/workflows/plataformas.yml con «plugin: rama». Si build.ps1 cambia lo que va en el jar o en la
# carpeta del plugin, cambiar esto también: el paso del workflow lista las dos carpetas para compararlas.
#
# Uso:  bash tools/ci/compilar-plugin.sh <carpeta data-integration> <carpeta de salida>
# Deja <salida>/pentaho-mcp/ (pentaho-mcp-core-X.Y.Z.jar, version.xml y, desde la 0.4.2, ui/), lista para
# copiar a <data-integration>/plugins/. Necesita un JDK 11+ en JAVA_HOME (javac --release 8 y jar).
set -euo pipefail

[ $# -eq 2 ] || { sed -n '2,9p' "$0"; exit 1; }
PDI="$1"; SALIDA="$2"
RAIZ="$(cd "$(dirname "$0")/../.." && pwd)"
PLUGIN="$RAIZ/spoon-plugin"
BUILD="$SALIDA/build"; CLASES="$BUILD/classes"; DIST="$SALIDA/pentaho-mcp"

# --- versión: VERSION manda; plugin.json (la ficha del plugin de Claude) tiene que decir lo mismo
VERSION="$(tr -d ' \r\n' < "$RAIZ/VERSION")"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "VERSION no tiene la forma X.Y.Z: '$VERSION'"; exit 1; }
VERSION_CLAUDE="$(python3 -I -c 'import json, sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["version"])' \
  "$RAIZ/claude/.claude-plugin/plugin.json")"
[ "$VERSION_CLAUDE" = "$VERSION" ] || {
  echo "claude/.claude-plugin/plugin.json dice versión $VERSION_CLAUDE y VERSION dice $VERSION"; exit 1; }

JAVAC="${JAVA_HOME:?definí JAVA_HOME (un JDK)}/bin/javac"; JAR="$JAVA_HOME/bin/jar"
[ -x "$JAVAC" ] && [ -x "$JAR" ] || { echo "$JAVA_HOME no es un JDK (falta javac o jar)"; exit 1; }
echo "Versión: $VERSION"
echo "JDK:     $JAVA_HOME ($("$JAVAC" -version 2>&1))"
echo "PDI:     $PDI"

# como build.ps1: se compila contra el SWT de Windows (es la misma API y así sale el mismo bytecode que el release)
for p in lib/kettle-core-9.4.0.0-343.jar lib/kettle-engine-9.4.0.0-343.jar lib/kettle-ui-swt-9.4.0.0-343.jar \
         libswt/win64/swt.jar; do
  [ -f "$PDI/$p" ] || { echo "Falta $p en $PDI"; exit 1; }
done
CP="$PDI/lib/*:$PDI/libswt/win64/*"

rm -rf "$BUILD" "$DIST"
mkdir -p "$CLASES" "$DIST/lib"

# --- compilar (bytecode Java 8: PDI 9.4 es Java 8)
find "$PLUGIN/src/main/java" -name '*.java' | sort | sed 's/.*/"&"/' > "$BUILD/sources.txt"
"$JAVAC" --release 8 -encoding UTF-8 -Xlint:-options -nowarn -cp "$CP" -d "$CLASES" "@$BUILD/sources.txt"
if [ -d "$PLUGIN/src/main/resources" ]; then cp -R "$PLUGIN/src/main/resources/." "$CLASES/"; fi

# --- la versión, como recurso junto a las clases: McpLifecycleListener.VERSION la lee de ahí
printf '%s' "$VERSION" > "$CLASES/io/github/ivi2511/pentahomcp/pentaho-mcp-version.txt"

# --- catálogo y guías de la skill pentaho-etl dentro del jar, para los recursos MCP pentaho://...
SKILL="$RAIZ/claude/skills/pentaho-etl"
for f in catalogo/INDICE.md catalogo/steps-comunes.md manual/01-formato-ktr.md manual/02-formato-kjb.md \
         manual/03-errores-comunes.md manual/04-informes.md manual/06-calidad.md manual/07-corrector.md \
         manual/08-modo-clase.md manual/09-robustez.md; do
  [ -f "$SKILL/$f" ] || { echo "Falta $SKILL/$f (catálogo de la skill pentaho-etl)"; exit 1; }
  mkdir -p "$CLASES/pentaho-etl/$(dirname "$f")" && cp "$SKILL/$f" "$CLASES/pentaho-etl/$f"
done
# glosario del modo clase (pentaho://glossary), como en build.ps1
[ -f "$SKILL/catalogo/glosario-clase.json" ] || { echo "Falta $SKILL/catalogo/glosario-clase.json (glosario del modo clase)"; exit 1; }
cp "$SKILL/catalogo/glosario-clase.json" "$CLASES/pentaho-etl/catalogo/"
for d in catalogo/steps catalogo/job-entries; do
  n=$(find "$SKILL/$d" -maxdepth 1 -type f -name '*.xml' | wc -l | tr -d ' ')
  [ "$n" -gt 0 ] || { echo "No hay .xml en $SKILL/$d"; exit 1; }
  mkdir -p "$CLASES/pentaho-etl/$d" && cp "$SKILL/$d"/*.xml "$CLASES/pentaho-etl/$d/"
  echo "Recursos: $n .xml de $d"
done

# --- empaquetar
printf 'Implementation-Title: pentaho-mcp\nImplementation-Version: %s\n\n' "$VERSION" > "$BUILD/MANIFEST.MF"
"$JAR" --create --file "$DIST/pentaho-mcp-core-$VERSION.jar" --manifest "$BUILD/MANIFEST.MF" -C "$CLASES" .
printf '<?xml version="1.0" encoding="UTF-8"?>\n<version>%s</version>\n' "$VERSION" > "$DIST/version.xml"

# --- desde la 0.4.2: el splash oculto de «pentaho-live --launcher» va en plugins/pentaho-mcp/ui/
if [ -d "$PLUGIN/ui" ]; then mkdir -p "$DIST/ui" && cp -R "$PLUGIN/ui/." "$DIST/ui/"; fi

echo
echo "OK -> $DIST"
(cd "$DIST" && find . -type f | sort | sed 's/^/   /')
