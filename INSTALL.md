# Instalar Pentaho MCP — guía para agentes (Claude Code, Codex…) y personas

> **Agente:** si el usuario te pidió "instalá IvI2511/pentaho-mcp" (o parecido), seguí esta guía de
> punta a punta. Explicale cada paso en una línea antes de hacerlo. Ejecutá los comandos vos si
> tenés permiso; si no, dáselos para que los pegue. No le pidas contraseñas ni tokens.

## Qué se instala

| Pieza | Para qué | Dónde queda |
|---|---|---|
| **Plugin de Spoon** (`pentaho-mcp.zip`) | Al abrir Spoon levanta el servidor MCP en `http://127.0.0.1:8765/mcp`: el agente edita la transformación abierta y el usuario ve aparecer los nodos | `data-integration/plugins/pentaho-mcp/` |
| **Plugin de Claude Code** (marketplace `pentaho-tools`, plugin `pentaho`) | La skill `pentaho-etl` (formato .ktr/.kjb, catálogo de 216 steps) + la conexión al MCP | lo maneja Claude Code |
| **Codex** (opcional) | El MCP por URL + la misma skill | `~/.codex/config.toml`, `~/.codex/skills/pentaho-etl/` |

Requisitos: **Java 11 o posterior** (probado con 21) y, si no tiene PDI, ~450 MB de disco.

## Paso 0 — Preguntale al usuario (una sola pregunta)

**"¿Ya tenés Pentaho Data Integration (Spoon) instalado? Si sí, ¿dónde está la carpeta `data-integration`?"**

- **Sí, y sabe dónde** → Opción A con `-PdiHome`.
- **Sí, pero no sabe dónde** → Opción A sin ruta (el script lo busca).
- **No** → Opción B (baja PDI 9.4 CE con el plugin ya puesto).

Si Spoon está abierto, pedile que lo cierre antes (Windows traba el `.jar` del plugin).

## Opción A — Ya tiene PDI: agregar el plugin

**Windows (PowerShell):**
```powershell
Invoke-WebRequest -UseBasicParsing https://raw.githubusercontent.com/IvI2511/pentaho-mcp/main/instalar.ps1 -OutFile $env:TEMP\instalar-pentaho-mcp.ps1
powershell -ExecutionPolicy Bypass -File $env:TEMP\instalar-pentaho-mcp.ps1 -PdiHome "C:\ruta\a\data-integration"
```
(sin `-PdiHome` lo busca en Descargas, Documentos, Escritorio, OneDrive, `C:\pentaho` y Archivos de programa)

**macOS / Linux:**
```bash
curl -fsSL https://raw.githubusercontent.com/IvI2511/pentaho-mcp/main/instalar.sh -o /tmp/instalar-pentaho-mcp.sh
bash /tmp/instalar-pentaho-mcp.sh --pdi "/ruta/a/data-integration"
```

## Opción B — No tiene PDI: bajar PDI 9.4 CE con el plugin

**Windows:** el mismo script con `-InstalarPdi` (por defecto en `%USERPROFILE%\Pentaho`; otra carpeta con `-Destino`):
```powershell
powershell -ExecutionPolicy Bypass -File $env:TEMP\instalar-pentaho-mcp.ps1 -InstalarPdi
```
**macOS / Linux:** `bash /tmp/instalar-pentaho-mcp.sh --instalar-pdi`

Es Pentaho Data Integration 9.4.0.0-343 Community Edition (Apache 2.0, de Hitachi Vantara) sin
cambios, salvo `plugins/pentaho-mcp/`. No trae el contenedor OSGi (karaf), así que no están los steps
de big data/streaming que dependen de él; los 216 steps del catálogo sí.

## Qué devuelve el script (para el agente)

Imprime `== Paso N - ...` en cada etapa y termina con un código:

| Código | Qué pasó | Qué hacer |
|---|---|---|
| 0 | Listo | Ir a "Después de instalar" |
| 2 | Hay varias instalaciones de PDI (las lista) | Preguntar cuál y repetir con `-PdiHome` / `--pdi` |
| 3 | No encontró PDI | Preguntar la ruta, o usar la Opción B |
| 4 | Spoon abierto traba el plugin | Pedir que cierre Spoon y repetir |
| 1 | Otro error (el mensaje dice cuál) | Mostrárselo al usuario |

El paso 4 configura solo los agentes que encuentre en el PATH. Desde la app de escritorio de Claude
(sin CLI) el plugin se agrega en **Configuración → Plugins → Agregar → marketplace** `IvI2511/pentaho-mcp`
y después se instala **Pentaho** desde **Descubrir**.

## Hacerlo a mano (sin script)

1. Bajar `pentaho-mcp.zip` de https://github.com/IvI2511/pentaho-mcp/releases/latest y descomprimirlo
   dentro de `data-integration/plugins/` → tiene que quedar `plugins/pentaho-mcp/pentaho-mcp-core-*.jar`.
   (Sin PDI: bajar `pdi-ce-9.4-con-pentaho-mcp.zip` del mismo release y descomprimirlo donde quiera.)
2. Claude Code:
   ```
   /plugin marketplace add IvI2511/pentaho-mcp
   /plugin install pentaho@pentaho-tools
   ```
3. Codex: `codex mcp add pentaho --url http://127.0.0.1:8765/mcp` y copiar la carpeta
   `skills/pentaho-etl` del repo a `~/.codex/skills/pentaho-etl`.

## Después de instalar

1. El usuario abre Spoon (`Spoon.bat` en Windows, `spoon.sh` en macOS/Linux).
2. Reinicia el agente (o `/reload-plugins` en Claude Code) para que conecte el MCP.
3. Verificá: `curl http://127.0.0.1:8765/ping` → `{"ok":true,"spoon":true,"tools":50}`, o llamá a la
   herramienta `estado` del MCP `pentaho`.
4. Proponele una prueba: *"armá una transformación que lea un CSV, filtre y escriba el resultado"*.

## Problemas comunes

| Síntoma | Causa | Arreglo |
|---|---|---|
| El MCP `pentaho` dice "connection refused" | Spoon cerrado, o el plugin no está en `plugins/` | Abrir Spoon; revisar `plugins/pentaho-mcp/` |
| `ping` responde pero el agente no ve herramientas | El agente se configuró antes de abrir Spoon | Reiniciar el agente |
| Puerto 8765 ocupado | Otro programa lo usa | `-Puerto 8770` en el script (setea `PENTAHO_MCP_PORT` para Spoon y Claude) |
| Spoon no abre | Falta Java | Instalar Java 11+ (`winget install EclipseAdoptium.Temurin.21.JRE`) |
| Error de driver al conectar a MySQL / SQL Server / Oracle | PDI no trae esos drivers | Copiar el `.jar` del conector a `data-integration/lib/` y reiniciar Spoon (MySQL: `mysql-connector-j-*.jar`, tipo de conexión MySQL normal) |
| ChatGPT (web) no conecta | ChatGPT solo usa MCP públicos por HTTPS; este escucha en `127.0.0.1` a propósito | Usar Claude Code o Codex en la misma PC |
