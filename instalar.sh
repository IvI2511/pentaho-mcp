#!/usr/bin/env bash
# Instala Pentaho MCP en macOS / Linux. Mismos pasos y codigos de salida que instalar.ps1:
#   0 listo · 2 varias instalaciones de PDI (repetir con --pdi) · 3 no hay PDI (--pdi o --instalar-pdi)
#   4 Spoon abierto traba el plugin · 1 otro error
#
# Uso:  bash instalar.sh [--pdi <carpeta data-integration>] [--instalar-pdi [--destino DIR]]
#                        [--sin-claude] [--sin-codex] [--puerto N]
set -euo pipefail

REPO="IvI2511/pentaho-mcp"
BASE="https://github.com/$REPO/releases/latest/download"
PDI="${PDI_HOME:-}"; INSTALAR_PDI=0; DESTINO="$HOME/pentaho"; SIN_CLAUDE=0; SIN_CODEX=0; PUERTO=8765
while [ $# -gt 0 ]; do
  case "$1" in
    --pdi) PDI="$2"; shift 2 ;;
    --instalar-pdi) INSTALAR_PDI=1; shift ;;
    --destino) DESTINO="$2"; shift 2 ;;
    --sin-claude) SIN_CLAUDE=1; shift ;;
    --sin-codex) SIN_CODEX=1; shift ;;
    --puerto) PUERTO="$2"; shift 2 ;;
    *) echo "opcion desconocida: $1"; exit 1 ;;
  esac
done
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
paso() { echo; echo "== Paso $1 - $2"; }
bajar() { echo "   bajando $1"; curl -fsSL "$1" -o "$2"; }
command -v unzip >/dev/null || { echo "Falta 'unzip' (apt install unzip / brew install unzip)"; exit 1; }

paso 1 "Pentaho Data Integration"
if [ "$INSTALAR_PDI" = 1 ]; then
  echo "   PDI 9.4 CE con el plugin ya instalado (~420 MB), a $DESTINO"
  bajar "$BASE/pdi-ce-9.4-con-pentaho-mcp.zip" "$TMP/pdi.zip"
  mkdir -p "$DESTINO" && unzip -q -o "$TMP/pdi.zip" -d "$DESTINO"
  chmod +x "$DESTINO"/data-integration/*.sh
  PDI="$DESTINO/data-integration"
elif [ -z "$PDI" ]; then
  echo "   buscando data-integration/spoon.sh..."
  HALLADOS=()
  while IFS= read -r d; do HALLADOS+=("$d"); done < <(
    find "$HOME" /opt /Applications 2>/dev/null -maxdepth 10 -type d -name data-integration \
      -not -path "*/Library/*" -not -path "*/.cache/*" -exec test -f "{}/spoon.sh" \; -print 2>/dev/null | sort -u)
  if [ ${#HALLADOS[@]} -eq 0 ]; then
    echo "No encontre Pentaho Data Integration. Repeti con --pdi <carpeta data-integration>, o con --instalar-pdi."; exit 3
  fi
  if [ ${#HALLADOS[@]} -gt 1 ]; then
    echo "Encontre varias instalaciones de PDI; repeti con --pdi eligiendo una:"; printf '   %s\n' "${HALLADOS[@]}"; exit 2
  fi
  PDI="${HALLADOS[0]}"
fi
[ -f "$PDI/spoon.sh" ] || { echo "$PDI no parece una carpeta data-integration (no tiene spoon.sh)."; exit 1; }
echo "   PDI: $PDI"

paso 2 "plugin de Spoon (plugins/pentaho-mcp)"
if [ "$INSTALAR_PDI" = 1 ]; then
  echo "   ya viene adentro del PDI que se bajo"
else
  bajar "$BASE/pentaho-mcp.zip" "$TMP/pentaho-mcp.zip"
  unzip -q -o "$TMP/pentaho-mcp.zip" -d "$TMP"
  rm -rf "$PDI/plugins/pentaho-mcp" && cp -R "$TMP/pentaho-mcp" "$PDI/plugins/pentaho-mcp" \
    || { echo "No pude reemplazar el plugin: cerra Spoon y volve a correr."; exit 4; }
  echo "   instalado en $PDI/plugins/pentaho-mcp"
fi

paso 3 "Java"
if command -v java >/dev/null; then java -version 2>&1 | head -1 | sed 's/^/   /'
else echo "   NO hay Java: Spoon no va a abrir. Instala Java 11+ (p.ej. Temurin 21: https://adoptium.net)"; fi

paso 4 "agentes (Claude Code / Codex)"
URL="http://127.0.0.1:$PUERTO/mcp"
[ "$PUERTO" != 8765 ] && echo "   Puerto $PUERTO: agrega 'export PENTAHO_MCP_PORT=$PUERTO' a tu ~/.bashrc o ~/.zshrc (lo leen Spoon y Claude Code)"
if [ "$SIN_CLAUDE" = 0 ] && command -v claude >/dev/null; then
  if claude plugin list 2>&1 | grep -q "pentaho@pentaho-tools"; then
    echo "   Claude Code: el plugin ya estaba (actualizar: claude plugin update pentaho@pentaho-tools)"
  else
    claude plugin marketplace add "$REPO" 2>&1 | sed 's/^/   /' || true
    claude plugin install pentaho@pentaho-tools --scope user 2>&1 | sed 's/^/   /' || true
  fi
elif [ "$SIN_CLAUDE" = 0 ]; then echo "   Claude Code no esta instalado: se saltea"; fi

if [ "$SIN_CODEX" = 0 ] && command -v codex >/dev/null; then
  if codex mcp list 2>&1 | grep -q pentaho; then echo "   Codex: el MCP 'pentaho' ya estaba"
  else codex mcp add pentaho --url "$URL" 2>&1 | sed 's/^/   /' || true; fi
  CODEX_DIR="${CODEX_HOME:-$HOME/.codex}"
  bajar "https://github.com/$REPO/archive/refs/heads/main.zip" "$TMP/repo.zip"
  unzip -q -o "$TMP/repo.zip" -d "$TMP"
  mkdir -p "$CODEX_DIR/skills" && rm -rf "$CODEX_DIR/skills/pentaho-etl"
  cp -R "$TMP/pentaho-mcp-main/skills/pentaho-etl" "$CODEX_DIR/skills/pentaho-etl"
  echo "   Codex: skill en $CODEX_DIR/skills/pentaho-etl (si no aparece, activar skills y reiniciar Codex)"
elif [ "$SIN_CODEX" = 0 ]; then echo "   Codex no esta instalado: se saltea"; fi

echo
echo "Listo. Falta:"
echo "  1. Abrir (o reiniciar) Spoon: $PDI/spoon.sh"
echo "  2. Reiniciar el agente (Claude Code / Codex) para que tome el MCP."
echo "  3. Probar: curl http://127.0.0.1:$PUERTO/ping  ->  {\"ok\":true,...}"
