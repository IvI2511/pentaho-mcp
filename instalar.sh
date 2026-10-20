#!/usr/bin/env bash
# Installs Pentaho MCP on macOS / Linux. Same steps ("== Step N") and exit codes as instalar.ps1:
#   0 done · 2 several PDI installs (rerun with --pdi) · 3 no PDI (--pdi or --instalar-pdi)
#   4 Spoon is open and locks the plugin · 1 other error
#
# Mac with an Apple chip (M1-M4): PDI 9.4's graphics library (SWT) is x86_64 only, so Spoon has to run under
# Rosetta 2 with an x64 Java. Step 3 says what is missing and, only after an explicit "y" in the terminal or
# with --rosetta, installs it: Rosetta 2 (softwareupdate) and Temurin 21 x64 into <destino>/java-x64, checked
# with the SHA-256 of the Adoptium API. With no terminal and no --rosetta it installs nothing. Then it writes
# data-integration/spoon-rosetta.sh, which opens Spoon that way. Linux and Intel Macs: nothing of this.
#
# Usage:  bash instalar.sh [--pdi <data-integration folder>] [--instalar-pdi [--destino DIR]]
#                          [--sin-claude] [--sin-codex] [--puerto N] [--rosetta]
set -euo pipefail

REPO="IvI2511/pentaho-mcp"
BASE="https://github.com/$REPO/releases/latest/download"
ADOPTIUM_API="https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=mac&vendor=eclipse"
PDI="${PDI_HOME:-}"; INSTALAR_PDI=0; DESTINO="$HOME/pentaho"; SIN_CLAUDE=0; SIN_CODEX=0; PUERTO=8765; ROSETTA=0
while [ $# -gt 0 ]; do
  case "$1" in
    --pdi) PDI="$2"; shift 2 ;;
    --instalar-pdi) INSTALAR_PDI=1; shift ;;
    --destino) DESTINO="$2"; shift 2 ;;
    --sin-claude) SIN_CLAUDE=1; shift ;;
    --sin-codex) SIN_CODEX=1; shift ;;
    --puerto) PUERTO="$2"; shift 2 ;;
    --rosetta) ROSETTA=1; shift ;;
    *) echo "unknown option: $1"; exit 1 ;;
  esac
done
JAVA_X64="$DESTINO/java-x64"
YO="$0"; case "$YO" in *.sh) ;; *) YO=instalar.sh ;; esac   # with "curl ... | bash", $0 is "bash"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
paso() { echo; echo "== Step $1 - $2"; }
bajar() { echo "   downloading $1"; curl -fsSL "$1" -o "$2"; }
descomprimir() {   # unzip exits 1 on warnings only, like "appears to use backslashes as path separators"
  local c=0       # (the plugin zip is made with Compress-Archive on Windows): the files come out fine
  unzip -q -o "$1" -d "$2" || c=$?
  [ "$c" -le 1 ]
}
sq() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }   # single-quoted, for a /bin/sh script
version_java() {   # first line of "<java> -version"; fails if it does not run
  local v; v=$("$1" -version 2>&1) || return 1
  case "$v" in *version*) echo "${v%%$'\n'*}" ;; *) return 1 ;; esac
}
command -v unzip >/dev/null || { echo "'unzip' is missing (apt install unzip / brew install unzip)"; exit 1; }

# ---- Mac with an Apple chip (step 3)
chip_apple() {   # also true when this shell already runs under Rosetta
  [ "$(uname -s)" = Darwin ] && { [ "$(uname -m)" = arm64 ] || [ "$(sysctl -n hw.optional.arm64 2>/dev/null)" = 1 ]; }
}
hay_rosetta() { arch -x86_64 /usr/bin/true 2>/dev/null; }
es_java_x64() {   # a Java home whose bin/java runs as x86_64 (also a universal binary)
  [ -x "$1/bin/java" ] || return 1
  case "$(file -bL "$1/bin/java" 2>/dev/null)" in *x86_64*) return 0 ;; *) return 1 ;; esac
}
preguntar() {   # only an explicit yes in the terminal (Enter alone is no)
  local r
  printf '   %s [y/N] ' "$1"; read -r r || return 1
  case "$r" in [yYsS]*) return 0 ;; *) return 1 ;; esac
}
instalar_rosetta() {
  echo "   installing Rosetta 2: softwareupdate --install-rosetta --agree-to-license"
  softwareupdate --install-rosetta --agree-to-license 2>&1 | sed 's/^/   /'
  hay_rosetta
}
instalar_java_x64() {   # Temurin 21 x64 into $JAVA_X64, with the SHA-256 of the Adoptium API (as preparar.ps1)
  local json par sha url
  json=$(curl -fsSL "$ADOPTIUM_API") || { echo "   could not read $ADOPTIUM_API"; return 1; }
  # the .tar.gz is binary.package (binary.installer is the .pkg); JavaScript for Automation reads the JSON
  par=$(osascript -l JavaScript -e \
    'function run(a) { var p = JSON.parse(a[0])[0].binary.package; return p.checksum + " " + p.link }' "$json" 2>/dev/null) \
    || par=""
  sha=$(printf '%s' "${par%% *}" | tr 'A-F' 'a-f'); url="${par#* }"
  case "$sha" in *[!0-9a-f]*) sha="" ;; esac
  [ ${#sha} = 64 ] || sha=""
  case "$url" in https://github.com/adoptium/*) ;; *) url="" ;; esac
  if [ -z "$sha" ] || [ -z "$url" ]; then
    echo "   the Adoptium API gave no valid SHA-256 and link: nothing downloaded"; return 1
  fi
  bajar "$url" "$TMP/java-x64.tar.gz" || return 1
  [ "$(shasum -a 256 "$TMP/java-x64.tar.gz" | cut -d' ' -f1)" = "$sha" ] \
    || { echo "   SHA-256 does not match (expected $sha): not installed"; return 1; }
  echo "   SHA-256 verified: $sha"
  rm -rf "$JAVA_X64" && mkdir -p "$JAVA_X64" && tar -xzf "$TMP/java-x64.tar.gz" -C "$JAVA_X64" --strip-components 1 \
    || return 1
  es_java_x64 "$JAVA_X64/Contents/Home"
}
LANZADOR=""
mac_apple() {   # Rosetta 2 + an x64 Java + spoon-rosetta.sh; leaves LANZADOR set if Spoon can open
  local jx="" c falta_rosetta=0 falta_java=0 si=0 v
  echo "   Mac with an Apple chip: PDI 9.4 ships its graphics library (SWT) for x86_64 only, so Spoon runs"
  echo "   under Rosetta 2 with an x64 Java (spoon.sh alone stops: \"this Mac platform [arm64] is not yet supported\")."
  for c in "${PENTAHO_JAVA_HOME:-}" "$JAVA_X64/Contents/Home" "${JAVA_HOME:-}" \
           "$(/usr/libexec/java_home -a x86_64 2>/dev/null || true)"; do
    if [ -n "$c" ] && es_java_x64 "$c"; then jx="$c"; break; fi
  done
  if hay_rosetta; then echo "   Rosetta 2: installed"; else falta_rosetta=1; echo "   Rosetta 2: NOT installed"; fi
  if [ -n "$jx" ]; then echo "   x64 Java: $jx"
  else falta_java=1; echo "   x64 Java: none (Temurin 21 x64 would go to $JAVA_X64, ~190 MB)"; fi

  if [ $falta_rosetta = 1 ] || [ $falta_java = 1 ]; then
    echo "   To open Spoon here, this installer can:"
    if [ $falta_rosetta = 1 ]; then
      echo "     - install Rosetta 2, Apple's x86_64 translator: softwareupdate --install-rosetta --agree-to-license"
      echo "       (that accepts Apple's Rosetta license; it needs an administrator account)"
    fi
    if [ $falta_java = 1 ]; then
      echo "     - download Eclipse Temurin 21 x64 (~190 MB) from Adoptium, check its SHA-256 and unpack it into"
      echo "       $JAVA_X64 (any other Java you have stays as it is)"
    fi
    if [ "$ROSETTA" = 1 ]; then echo "   --rosetta: doing it."; si=1
    elif [ ! -t 0 ]; then echo "   No terminal to ask (an agent, or curl | bash): nothing installed."
    elif preguntar "Do it now?"; then si=1
    else echo "   Nothing installed."; fi
    if [ $si = 1 ]; then
      if [ $falta_rosetta = 1 ]; then
        instalar_rosetta || echo "   Could not install Rosetta 2 (it needs an administrator account)."
      fi
      if [ $falta_java = 1 ]; then
        if instalar_java_x64; then jx="$JAVA_X64/Contents/Home"; echo "   x64 Java: $jx"
        else echo "   Could not install the x64 Java."; fi
      fi
    fi
  fi

  if hay_rosetta && [ -n "$jx" ]; then
    if v=$(arch -x86_64 "$jx/bin/java" -version 2>&1); then echo "   ${v%%$'\n'*}"; fi
    LANZADOR="$PDI/spoon-rosetta.sh"
    {
      echo '#!/bin/sh'
      echo '# Opens Spoon on a Mac with an Apple chip: under Rosetta 2 with an x64 Java (the SWT of PDI 9.4 is'
      echo '# x86_64 only). Written by instalar.sh of Pentaho MCP; running it again rewrites this file.'
      echo "export PENTAHO_JAVA_HOME=$(sq "$jx")"
      echo "exec arch -x86_64 /bin/sh $(sq "$PDI/spoon.sh") \"\$@\""
    } > "$LANZADOR"
    chmod +x "$LANZADOR"
    echo "   launcher: $LANZADOR"
  else
    echo "   Spoon will not open on this Mac without both. To install them as above, rerun with --rosetta:"
    echo "     bash \"$YO\" --pdi \"$PDI\" --rosetta"
  fi
}

paso 1 "Pentaho Data Integration"
if [ "$INSTALAR_PDI" = 1 ]; then
  echo "   PDI 9.4 CE with the plugin already installed (~420 MB), into $DESTINO"
  bajar "$BASE/pdi-ce-9.4-con-pentaho-mcp.zip" "$TMP/pdi.zip"
  mkdir -p "$DESTINO" && descomprimir "$TMP/pdi.zip" "$DESTINO"
  PDI="$DESTINO/data-integration"
  # the 0.4.0 and 0.4.1 zips lost their Unix permissions: without this neither the scripts nor the Mac .app run
  for f in "$PDI"/*.sh "$PDI"/*.command "$PDI/Data Integration.app/Contents/MacOS/JavaApplicationStub"; do
    if [ -f "$f" ]; then chmod +x "$f"; fi
  done
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
  descomprimir "$TMP/pentaho-mcp.zip" "$TMP"
  # its folder entries have no MS-DOS attributes, so unzip makes them without the x bit (macOS's cp -R fails)
  chmod -R u+rwX,go+rX "$TMP/pentaho-mcp"
  rm -rf "$PDI/plugins/pentaho-mcp" && cp -R "$TMP/pentaho-mcp" "$PDI/plugins/pentaho-mcp" \
    || { echo "Could not replace the plugin: close Spoon and run the installer again."; exit 4; }
  echo "   installed in $PDI/plugins/pentaho-mcp"
fi

paso 3 "Java"
if chip_apple; then
  mac_apple
else
  # the Java that spoon.sh picks, in the order of PDI's set-pentaho-env.sh (PENTAHO_JAVA_HOME wins over JAVA_HOME)
  JH=""; DE="the PATH"
  if [ -n "${PENTAHO_JAVA_HOME:-}" ]; then JH="$PENTAHO_JAVA_HOME"; DE=PENTAHO_JAVA_HOME
  else
    for d in jre java ../jre ../java ../../jre ../../java; do
      if [ -x "$PDI/$d/bin/java" ]; then JH="$PDI/$d"; DE="the $d folder of PDI"; break; fi
    done
    if [ -z "$JH" ] && [ -n "${JAVA_HOME:-}" ]; then JH="$JAVA_HOME"; DE=JAVA_HOME
    elif [ -z "$JH" ] && [ -n "${JRE_HOME:-}" ]; then JH="$JRE_HOME"; DE=JRE_HOME; fi
  fi
  if [ -n "$JH" ]; then JAVA="$JH/bin/java"; else JAVA="$(command -v java || true)"; fi
  # without a JDK, macOS's /usr/bin/java is only a stub that asks to install one
  if [ "$JAVA" = /usr/bin/java ] && [ "$(uname -s)" = Darwin ] && ! /usr/libexec/java_home >/dev/null 2>&1; then JAVA=""; fi
  if [ -n "$JAVA" ] && V=$(version_java "$JAVA"); then echo "   $V  (from $DE${JH:+: $JH})"
  elif [ -n "$JH" ]; then echo "   NO Java in $JH (from $DE): Spoon will not open. Point $DE to a Java 11+ home."
  else echo "   NO Java: Spoon will not open. Install Java 11+ (e.g. Temurin 21: https://adoptium.net)"; fi
fi

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
  descomprimir "$TMP/repo.zip" "$TMP"
  mkdir -p "$CODEX_DIR/skills" && rm -rf "$CODEX_DIR/skills/pentaho-etl"
  cp -R "$TMP/pentaho-mcp-main/claude/skills/pentaho-etl" "$CODEX_DIR/skills/pentaho-etl"
  echo "   Codex: skill in $CODEX_DIR/skills/pentaho-etl (if it does not show up, enable skills and restart Codex)"
elif [ "$SIN_CODEX" = 0 ]; then echo "   Codex is not installed: skipped"; fi

echo
echo "Done. Next:"
if [ -n "$LANZADOR" ]; then
  echo "  1. Open (or restart) Spoon: $LANZADOR"
  echo "     (spoon.sh under Rosetta 2 with the x64 Java; to type just 'spoon': echo \"alias spoon=$(sq "$LANZADOR")\" >> ~/.zshrc)"
elif chip_apple; then
  echo "  1. Spoon needs Rosetta 2 and an x64 Java on this Mac: rerun with --rosetta (step 3), then open it with"
  echo "     the $PDI/spoon-rosetta.sh that it leaves"
else
  echo "  1. Open (or restart) Spoon: $PDI/spoon.sh"
fi
echo "  2. Restart the agent (Claude Code / Codex) so it picks up the MCP."
echo "  3. Check: curl http://127.0.0.1:$PUERTO/ping  ->  {\"ok\":true,...}"
