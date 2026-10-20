# Pentaho Live (no oficial)

[English](README.md)

Pedile a Claude que arme ETLs en **Pentaho Data Integration 9.4 (Spoon)** mientras mirás: agrega steps,
los conecta, los configura, valida, muestra filas de preview y ejecuta, todo en la ventana de Spoon que
tenés abierta. Cada cambio se deshace con Ctrl+Z.

Proyecto independiente, sin relación con Hitachi Vantara. Pentaho es una marca de Hitachi Vantara.

## Instalar

**Lo más fácil: pedíselo a tu agente.** En Claude Code o Codex escribí:

> instalá IvI2511/pentaho-mcp

El agente sigue [`INSTALL.md`](INSTALL.md): te pregunta si ya tenés Pentaho, baja lo que falta, instala
el plugin de Spoon y se configura solo.

| Si… | Bajás | Script |
|---|---|---|
| **ya tenés PDI** | `pentaho-mcp.zip` (el plugin, ~140 KB) → se descomprime en `data-integration/plugins/` | `instalar.ps1 -PdiHome <data-integration>` |
| **no tenés PDI** | `pdi-ce-9.4-con-pentaho-mcp.zip` (PDI 9.4 CE entero con el plugin, ~370 MB) | `instalar.ps1 -InstalarPdi` |

Los dos están en el [último release](https://github.com/IvI2511/pentaho-mcp/releases/latest).

En Claude Code, a mano:
```
/plugin marketplace add IvI2511/pentaho-mcp
/plugin install pentaho-live@pentaho-tools
```

Después: abrir Spoon y pedirle algo como *"armá una transformación que lea ventas.csv, sume el importe
por ciudad y lo escriba en un CSV"*.

MySQL, MariaDB, SQL Server y Oracle no traen driver en PDI: copiar el `.jar` del conector a
`data-integration/lib/`. Con MySQL Connector/J 8+ alcanza el tipo de conexión MySQL normal.

La skill y la documentación interna (`docs/api-spoon.md`) están en español.
