# Pentaho MCP

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

1. **En Spoon**: bajar `pentaho-mcp-<versión>.zip` del último release, descomprimirlo dentro de
   `data-integration/plugins/` (queda `plugins/pentaho-mcp/`) y reiniciar Spoon.
2. **En Claude Code**:
   ```
   /plugin marketplace add IvI2511/pentaho-mcp
   /plugin install pentaho@pentaho-tools
   ```
3. Con Spoon abierto, pedirle a Claude algo como *"armá una transformación que lea ventas.csv, sume
   el importe por ciudad y lo escriba en un CSV"*.

Otro puerto: definir `PENTAHO_MCP_PORT` igual para Spoon (variable de entorno o `kettle.properties`)
y para Claude Code.

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
