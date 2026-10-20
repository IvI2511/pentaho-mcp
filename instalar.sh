#!/usr/bin/env bash
# Installs Pentaho MCP on macOS / Linux. Same steps ("== Step N") and exit codes as instalar.ps1:
#   0 done · 2 several PDI installs (rerun with --pdi) · 3 no PDI (--pdi or --instalar-pdi)
#   4 Spoon is open and locks the plugin · 1 other error
#
# Usage:  bash instalar.sh [--pdi <data-integration folder>] [--instalar-pdi [--destino DIR]]
#                          [--sin-claude] [--sin-codex] [--puerto N]
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
    *) echo "unknown option: $1"; exit 1 ;;
  esac
done
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
paso() { echo; echo "== Step $1 - $2"; }
bajar() { echo "   downloading $1"; curl -fsSL "$1" -o "$2"; }
command -v unzip >/dev/null || { echo "'unzip' is missing (apt install unzip / brew install unzip)"; exit 1; }

paso 1 "Pentaho Data Integration"
if [ "$INSTALAR_PDI" = 1 ]; then
  echo "   PDI 9.4 CE with the plugin already installed (~420 MB), into $DESTINO"
  bajar "$BASE/pdi-ce-9.4-con-pentaho-mcp.zip" "$TMP/pdi.zip"
  mkdir -p "$DESTINO" && unzip -q -o "$TMP/pdi.zip" -d "$DESTINO"
  chmod +x "$DESTINO"/data-integration/*.sh
  PDI="$DESTINO/data-integration"
elif [ -z "$PDI" ]; then
  echo "   searching for data-integration/spoon.sh..."
  HALLADOS=()
  while IFS= read -r d; do HALLADOS+=("$d"); done < <(
    find "$HOME" /opt /Applications 2>/dev/null -maxdepth 10 -type d -name data-integration \
      -not -path "*/Library/*" -not -path "*/.cache/*" -exec test -f "{}/spoon.sh" \; -print 2>/dev/null | sort -u)
  if [ ${#HALLADOS[@]} -eq 0 ]; then
    echo "Pentaho Data Integration not found. Rerun with --pdi <data-integration folder>, or with --instalar-pdi."; exit 3
  fi
  if [ ${#HALLADOS[@]} -gt 1 ]; then
    echo "Found several PDI installs; rerun with --pdi and pick one:"; printf '   %s\n' "${HALLADOS[@]}"; exit 2
  fi
  PDI="${HALLADOS[0]}"
fi
[ -f "$PDI/spoon.sh" ] || { echo "$PDI does not look like a data-integration folder (it has no spoon.sh)."; exit 1; }
echo "   PDI: $PDI"

paso 2 "Spoon plugin (plugins/pentaho-mcp)"
if [ "$INSTALAR_PDI" = 1 ]; then
  echo "   already included in the downloaded PDI"
else
  bajar "$BASE/pentaho-mcp.zip" "$TMP/pentaho-mcp.zip"
  unzip -q -o "$TMP/pentaho-mcp.zip" -d "$TMP"
  rm -rf "$PDI/plugins/pentaho-mcp" && cp -R "$TMP/pentaho-mcp" "$PDI/plugins/pentaho-mcp" \
    || { echo "Could not replace the plugin: close Spoon and run the installer again."; exit 4; }
  echo "   installed in $PDI/plugins/pentaho-mcp"
fi

paso 3 "Java"
if command -v java >/dev/null; then java -version 2>&1 | head -1 | sed 's/^/   /'
else echo "   NO Java: Spoon will not open. Install Java 11+ (e.g. Temurin 21: https://adoptium.net)"; fi

paso 4 "agents (Claude Code / Codex)"
URL="http://127.0.0.1:$PUERTO/mcp"
[ "$PUERTO" != 8765 ] && echo "   Port $PUERTO: add 'export PENTAHO_MCP_PORT=$PUERTO' to your ~/.bashrc or ~/.zshrc (Spoon reads it); in Claude Code set the plugin option mcp_url to $URL (in /config)"
if [ "$SIN_CLAUDE" = 0 ] && command -v claude >/dev/null; then
  if claude plugin list 2>&1 | grep -q "pentaho-live@pentaho-tools"; then
    echo "   Claude Code: the plugin was already installed (to update it: claude plugin update pentaho-live@pentaho-tools)"
  else
    claude plugin marketplace add "$REPO" 2>&1 | sed 's/^/   /' || true
    claude plugin install pentaho-live@pentaho-tools --scope user 2>&1 | sed 's/^/   /' || true
  fi
elif [ "$SIN_CLAUDE" = 0 ]; then echo "   Claude Code is not installed: skipped"; fi

if [ "$SIN_CODEX" = 0 ] && command -v codex >/dev/null; then
  if codex mcp list 2>&1 | grep -q pentaho; then echo "   Codex: the 'pentaho' MCP was already configured"
  else codex mcp add pentaho --url "$URL" 2>&1 | sed 's/^/   /' || true; fi
  CODEX_DIR="${CODEX_HOME:-$HOME/.codex}"
  bajar "https://github.com/$REPO/archive/refs/heads/main.zip" "$TMP/repo.zip"
  unzip -q -o "$TMP/repo.zip" -d "$TMP"
  mkdir -p "$CODEX_DIR/skills" && rm -rf "$CODEX_DIR/skills/pentaho-etl"
  cp -R "$TMP/pentaho-mcp-main/claude/skills/pentaho-etl" "$CODEX_DIR/skills/pentaho-etl"
  echo "   Codex: skill in $CODEX_DIR/skills/pentaho-etl (if it does not show up, enable skills and restart Codex)"
elif [ "$SIN_CODEX" = 0 ]; then echo "   Codex is not installed: skipped"; fi

echo
echo "Done. Next:"
echo "  1. Open (or restart) Spoon: $PDI/spoon.sh"
echo "  2. Restart the agent (Claude Code / Codex) so it picks up the MCP."
echo "  3. Check: curl http://127.0.0.1:$PUERTO/ping  ->  {\"ok\":true,...}"
