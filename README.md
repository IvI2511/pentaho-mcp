# Pentaho MCP

> **¿Sos un agente (Claude Code, Codex…) y te pidieron instalar esto?** Seguí [`INSTALL.md`](INSTALL.md).

Para que Claude arme ETLs en **Pentaho Data Integration 9.4 (Spoon)** mientras uno mira: agrega
steps, los conecta, los configura, valida, muestra filas de preview y ejecuta, todo en la ventana de
Spoon que tenés abierta. Cada cambio se deshace con Ctrl+Z.

Son dos piezas:

| Pieza | Qué es | Dónde |
|---|---|---|
| **Plugin de Spoon** | Un `@LifecyclePlugin` de Kettle que al abrir Spoon levanta un servidor MCP en `127.0.0.1:8765` con ~50 herramientas | `plugin/` (Java 8, compila contra la instalación de PDI) |
| **Plugin de Claude Code** | La skill `pentaho-etl` (formato `.ktr`/`.kjb`, catálogo de los 216 steps y 77 job entries sacado de la instalación, errores comunes verificados, ejemplos que corren) y la configuración del MCP | `.claude-plugin/`, `.mcp.json`, `skills/` |

La skill sirve sola también: sin Spoon abierto, Claude escribe el `.ktr` y lo verifica con Pan.

## Instalar

**Lo más fácil: pedíselo a tu agente.** En Claude Code o Codex escribí:

> instalá IvI2511/pentaho-mcp

El agente sigue [`INSTALL.md`](INSTALL.md): te pregunta si ya tenés Pentaho, baja lo que falta,
instala el plugin de Spoon y se configura a sí mismo.

Hay dos opciones, según tengas o no PDI:

| Si… | Bajás | Script |
|---|---|---|
| **Ya tenés PDI** | `pentaho-mcp.zip` (el plugin, ~140 KB) → se descomprime en `data-integration/plugins/` | `instalar.ps1 -PdiHome <data-integration>` |
| **No tenés PDI** | `pdi-ce-9.4-con-pentaho-mcp.zip` (PDI 9.4 CE entero con el plugin puesto, ~420 MB) | `instalar.ps1 -InstalarPdi` |

Los dos están en el [último release](https://github.com/IvI2511/pentaho-mcp/releases/latest).
Instalación a mano, macOS/Linux (`instalar.sh`) y problemas comunes: [`INSTALL.md`](INSTALL.md).

Después: abrir Spoon y pedirle al agente algo como *"armá una transformación que lea ventas.csv,
sume el importe por ciudad y lo escriba en un CSV"*.

MySQL, MariaDB, SQL Server y Oracle no traen driver en PDI: copiar el `.jar` del conector a
`data-integration/lib/`. Con MySQL Connector/J 8+ alcanza el tipo de conexión MySQL normal.

## Desarrollo

| Qué | Cómo |
|---|---|
| Compilar el plugin | `powershell -ExecutionPolicy Bypass -File plugin\build.ps1 [-PdiHome <data-integration>]` → `plugin/dist/pentaho-mcp/` y el zip del release |
| Regenerar el catálogo | `bash tools/generador-catalogo/generar.sh` (lee los plugins de la instalación) |
| Validar el plugin de Claude | `claude plugin validate --strict .` |
| Diseño interno (API de Spoon, hilos, diálogos que hay que evitar) | `docs/api-spoon.md` |

Probar a mano sin Claude: `curl http://127.0.0.1:8765/ping`.
