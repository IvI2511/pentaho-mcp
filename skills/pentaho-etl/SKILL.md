---
name: pentaho-etl
description: Construir, modificar, revisar o depurar ETLs de Pentaho Data Integration 9.4 (Spoon/Kettle) — transformaciones .ktr y jobs .kjb. Usar cuando el usuario pida armar una transformación o un job de Pentaho/PDI/Kettle/Spoon, agregar steps (CSV input, Filter rows, Group by, Stream lookup, Table output...), conectar a una base desde Pentaho, o entender por qué falla un .ktr/.kjb. Sirve en vivo con el MCP "pentaho" (Spoon abierto) o escribiendo el XML y verificando con Pan/Kitchen.
---

# Pentaho Data Integration 9.4 — ETLs

Todo lo de esta carpeta está **verificado contra una instalación real de PDI 9.4.0.0-343 (CE)**: los
ids de step salen del `PluginRegistry`, los ejemplos corren con Pan/Kitchen y los errores fueron
provocados a propósito. No inventes tags: buscalos acá.

## Dos formas de trabajar

**A. En vivo, con el MCP `pentaho`** (si sus herramientas están disponibles: `estado`, `agregar_step`…).
El usuario tiene Spoon abierto y ve aparecer cada nodo. Es la forma preferida.

Si el MCP `pentaho` figura pero falla al conectar, es que Spoon está cerrado o le falta el plugin. El
plugin de Spoon es la carpeta `pentaho-mcp` del zip `pentaho-mcp-<versión>.zip` de los releases de
https://github.com/IvI2511/pentaho-mcp: se descomprime en `data-integration/plugins/` y se reinicia
Spoon. Escucha en `127.0.0.1:8765` (otro puerto: variable `PENTAHO_MCP_PORT`, igual en Spoon y en Claude).

1. `estado` → qué hay abierto (ids T1, J1…). `nueva_transformacion` / `abrir_archivo` si hace falta.
2. Por cada step, de izquierda a derecha:
   - `tipos_de_step buscar=...` si no sabés el id exacto (el `<type>` NO es el nombre en pantalla).
   - `plantilla_step` para ver los tags; mandá en `agregar_step` **solo los tags que cambian**
     (el resto queda por defecto) y `despues_de=<step anterior>` para que se ubique y conecte solo.
   - `campos_step` del step anterior **antes** de configurar el siguiente: así usás los nombres de
     columna reales, no los que suponés.
3. `validar`, después `preview_step` sobre el último step para ver filas reales.
4. `guardar` con ruta `.ktr`/`.kjb`. `ejecutar` (modo visible) si el usuario quiere verlo correr.
5. Si algo sale mal: `ver_step`, `configurar_step` (mezcla sobre la config actual), `borrar_step`.
   El usuario puede deshacer cualquier cambio tuyo con Ctrl+Z.

Jobs: `nuevo_job`, `agregar_entrada tipo=START` primero, después entradas `TRANS`/`JOB`/`SUCCESS`
con `despues_de` y `condicion` (`siempre` | `si_ok` | `si_falla`).

**B. Escribiendo archivos** (sin MCP, o para generar muchos de una): escribís el `.ktr`/`.kjb` en
UTF-8 partiendo de la plantilla recomendada de `manual/01-formato-ktr.md` §1.2 y lo verificás con Pan
o Kitchen (ver abajo). El usuario lo abre en Spoon cuando terminás.

## Dónde está cada cosa

| Necesito… | Leer |
|---|---|
| Reglas de oro y esqueleto de un `.ktr` | `manual/01-formato-ktr.md` (empezá por el §0, checklist) |
| Jobs: START, TRANS, hops con condición | `manual/02-formato-kjb.md` |
| Un error de Pan/Kitchen, o un resultado raro con exit 0 | `manual/03-errores-comunes.md` |
| El id exacto de un step o entry por categoría | `catalogo/INDICE.md` |
| Cómo se configura un step común, con ejemplo y trampas | `catalogo/steps-comunes.md` (~45 steps) |
| El XML por defecto de CUALQUIER step / entry | `catalogo/steps/<id>.xml`, `catalogo/job-entries/<id>.xml` |
| Ejemplos completos que corren | `manual/ejemplos/` (README con qué hace cada uno) |

## Trampas que más cuestan (detalle en el manual)

- `<type>` es el **id del plugin**: Add constants = `Constant`, Add sequence = `Sequence`,
  User defined Java expression = `Janino`, Excel writer = `TypeExitExcelWriterStep`. START y Dummy
  de un job son `<type>SPECIAL</type>`.
- Cada `<step>` lleva `<copies>1</copies>` y `<GUI>…<draw>Y</draw></GUI>`; sin `copies` Pan falla
  sin explicar, sin `draw` el step no se ve en Spoon.
- Nombres de step **únicos**: un duplicado hace desaparecer un step en silencio.
- **Group by** y **Merge join** necesitan la entrada **ordenada** por la clave (Sort rows antes);
  si no, dan resultados mal sin error.
- **Stream lookup** referencia al step de búsqueda con `<from>` (no existe `<lookup_step>`) y además
  necesita el hop desde ese step. Lo mismo Filter rows (`send_true_to`/`send_false_to`) y Switch/case:
  la referencia en el XML **y** el hop.
- Números: declarar `<decimal>.</decimal>` explícito; vacío con locale español lee `2.50` como 250.
- En Generate rows y Add constants el valor va en `<nullif>`.
- Rutas siempre relativas con `${Internal.Entry.Current.Directory}/…` (la carpeta del propio archivo).
- Excel `.xlsx`: `<spreadsheet_type>POI</spreadsheet_type>` (el default JXL solo lee `.xls`).
- Escapar `&` y `<` en el XML (`&amp;`, `&lt;`).
- Drivers JDBC incluidos: PostgreSQL, H2, SQLite, HSQLDB, Derby y otros. **No vienen** MySQL, MariaDB,
  SQL Server ni Oracle: hay que copiar el `.jar` del conector a `data-integration/lib/` y reiniciar Spoon.
  Con MySQL Connector/J 8 o posterior (`mysql-connector-j-*.jar`) alcanza el tipo `MYSQL` normal: PDI 9.4
  usa `com.mysql.cj.jdbc.Driver` si está (verificado con `tipos_de_base`); no hace falta "Generic database".

## Verificar con Pan / Kitchen (Windows, desde PowerShell)

```powershell
Remove-Item Env:NoDefaultCurrentDirectoryInExePath -ErrorAction SilentlyContinue
& cmd.exe /c call '<data-integration>\Pan.bat' "/file:<ruta.ktr>" /level:Basic '"/param:X=1"'
# jobs: Kitchen.bat con los mismos argumentos
```

- Sin quitar `NoDefaultCurrentDirectoryInExePath`, `Pan.bat` no encuentra `Spoon.bat`.
- Con `& Pan.bat` directo, `$LASTEXITCODE` da 0 aunque falle: usar `cmd /c call`.
- `/param` entre comillas dobles, si no cmd corta en el `=`.
- Exit 0 no alcanza: mirá la salida (ver "errores silenciosos" en `03-errores-comunes.md`).
- Para no tocar la configuración del usuario (`~/.kettle`), definir `KETTLE_HOME` en una carpeta propia.
