<p align="center">
  <img src="docs/images/pentaho-live-icono.svg" alt="" width="88"><br><br>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/pentaho-live-oscuro.svg">
    <img src="docs/images/pentaho-live-claro.svg" alt="IDC Studios | Pentaho Live" height="34">
  </picture>
</p>

<p align="center">
  <strong>Claude arma tus ETLs de Pentaho Data Integration (Spoon) en vivo, mientras miras.</strong><br>
  Un plugin de Spoon que levanta un servidor MCP local, más un plugin de Claude Code con la skill para usarlo.
</p>

<p align="center">
  <a href="https://github.com/IvI2511/pentaho-mcp/releases/latest"><img src="https://img.shields.io/github/v/release/IvI2511/pentaho-mcp?label=release&color=22d3a6" alt="Último release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-4f8cff" alt="Licencia MIT"></a>
  <a href="#instalar"><img src="https://img.shields.io/badge/Claude%20Code-plugin-2c3542?logo=claude&logoColor=white" alt="Plugin de Claude Code"></a>
  <img src="https://img.shields.io/badge/PDI-9.4%20CE-3f74d8" alt="PDI 9.4 CE">
  <img src="https://img.shields.io/badge/MCP-local%20HTTP-1c9a80" alt="MCP">
</p>

<p align="center">
  <a href="README.md">English</a> · <strong>Español</strong> · <a href="README.pt.md">Português</a>
</p>

> [!TIP]
> **¿Eres un agente (Claude Code, Codex…) y te han pedido instalar esto?** Sigue [`INSTALL.md`](INSTALL.md).

<!-- GIF DE DEMO: reemplazar este comentario y la línea de abajo por
<p align="center"><img src="docs/images/demo.gif" alt="Claude armando una transformación en vivo en Spoon" width="860"></p>
-->
<p align="center"><em>El GIF de demo se agrega pronto: Claude arma una transformación y cada step aparece en Spoon.</em></p>

## Qué hace

Abres Spoon, le pides a Claude un ETL con tus palabras y lo ves pasar en el lienzo: Claude agrega los
steps, los conecta y los configura, revisa qué campos salen de cada uno, valida, muestra filas reales de
preview y ejecuta la transformación. Cada cambio pasa por el deshacer de Spoon, así que **Ctrl+Z**
siempre funciona.

**Informes.** Spoon suma una perspectiva nueva, **Informes**, que documenta sola la transformación o el
job abierto: portada, diagrama de flujo, linaje de campos, diccionario de datos, modelo de las tablas
destino, perfil de datos, métricas de ejecución con gráficos, validación, calidad técnica, conexiones y
parámetros. Exporta a HTML, PDF, Markdown (para Obsidian o GitHub), PowerPoint, Word, Excel e imágenes de
cada diagrama, de un documento o de varios combinados en un solo archivo, con tu nombre, institución, logo
y colores. Claude puede armarte el mismo informe y sumarle una narrativa corta escrita a partir de los
números reales.

**Calidad.** Claude también puede revisar un ETL buscando lo que Kettle deja pasar sin error: Group by o
Merge join sin ordenar, el símbolo decimal vacío que en una computadora en español convierte `2.50` en
`250`, rutas fijas, contraseñas guardadas en el archivo, una rama de error que termina en Success. Te da un
puntaje de calidad técnica de 0 a 100 que muestra de dónde sale cada punto que falta, y aplica los arreglos
seguros con un solo Ctrl+Z (antes de los que cambian lo que hace el ETL, te pregunta). La misma revisión
es una sección del informe.

**Corrector.** Para docentes: a partir de la solución se arma un pack de ejercicio (un caso visible y otro
oculto con otros datos, la salida esperada, reglas y una rúbrica) y se corrige una carpeta entera de
entregas. Cada una corre en una JVM aparte, nunca dentro de Spoon, y su salida se compara fila a fila con la
esperada: sale la nota con los criterios que fallaron, una pista para cada error típico (Group by sin ordenar,
símbolo decimal vacío, duplicados sin quitar, rutas fijas), la devolución de cada alumno sin los valores del
caso oculto y una planilla para Excel. Spoon suma la perspectiva **Corrector** con lo mismo y un asistente que
arma el pack desde la transformación abierta.

**Modo clase.** Claude también puede *enseñar* un ETL: construye la transformación despacio, un step por
cada clic en **Siguiente ▶** de una pestaña nueva, **Lección**, y explica cada step con una nota numerada en
el lienzo (qué hace, por qué está, el error típico), con las primeras filas después de cada paso. Pensado
para una clase proyectada o para aprender por tu cuenta; al terminar puedes ocultar las notas para el
ejercicio y convertir la lección en una guía en PDF o Word. La skill trae tres lecciones probadas (filtrar,
Sort + Group by, Stream lookup frente a Merge join) en español, inglés y portugués.

**Robustez.** Pídele a Claude que una carga tolere datos sucios: cada fila mala (un campo que falta, una
fecha que no se puede leer, un texto demasiado largo para su columna, una venta de un cliente que no
existe) va a un archivo de rechazos con el motivo, en vez de parar la carga o perderse. Claude encuentra
lo que falta, te muestra el plan y lo aplica con un solo Ctrl+Z; después lo demuestra con una prueba de
datos sucios: copias rotas de tus archivos de entrada corren contra una copia de la transformación en un
entorno aislado, y la prueba comprueba que cada fila leída se cargó o se rechazó con su motivo, y que
ejecutarla dos veces no duplica nada.

¿No tienes Spoon abierto? La skill igual sirve: Claude escribe el `.ktr` / `.kjb` y lo prueba con Pan o
Kitchen.

## Qué le puedes pedir

| Le dices… | Claude… |
|---|---|
| *"Arma una transformación que lea `ventas.csv`, le agregue la ciudad desde `clientes.csv`, sume el importe por ciudad y lo escriba en un CSV."* | la arma step por step en el lienzo, la valida y te muestra el resultado |
| *"Añade un Filter rows después del CSV input que deje las filas con saldo > 1000 y enséñame el preview."* | inserta el step en la transformación abierta y te muestra las filas que pasan |
| *"Conéctate a mi base PostgreSQL y carga este archivo en la tabla `clientes`."* | agrega la conexión (la contraseña la escribes tú en Spoon), lista tablas y columnas y mapea los campos |
| *"Haz un job que ejecute estas dos transformaciones en orden y que pare si falla la primera."* | arma el job con sus entradas y los hops de éxito / error |
| *"El lienzo es un lío, ordénalo."* | acomoda los steps de izquierda a derecha, sin líneas cruzadas |
| *"¿Por qué este `.ktr` falla en Pan?"* | lo lee, encuentra el problema con la lista de errores verificados y lo corrige |
| *"Revisa las buenas prácticas de esta transformación antes de que la entregue y arregla lo que sea seguro."* | corre la revisión de calidad, te explica cada hallazgo, aplica los arreglos seguros con un Ctrl+Z y te pregunta antes del resto |
| *"Da la lección de Stream lookup paso a paso, nivel principiante."* | la construye un step por cada clic en Siguiente ▶, con una nota numerada en el lienzo por step y las filas que produce |
| *"Hazme un informe de esta transformación para la práctica, en PDF y PowerPoint, con una conclusión corta."* | arma el informe (linaje, diccionario de datos, métricas…), escribe la conclusión con los datos reales y exporta los dos archivos |
| *"Con esta solución, un pack de corrección con un caso oculto; y después, las notas de las entregas de `C:\TP3\entregas`."* | corre la solución para sacar la salida esperada, corrige cada entrega en JVMs aparte y resume las notas, quién falló qué y por qué; la planilla y la devolución de cada alumno quedan en la carpeta de resultados |

## Cómo funciona

```mermaid
flowchart LR
    you(["Tú"])
    subgraph agent ["Claude Code"]
        claude["Claude"]
        skill["skill pentaho-etl<br/>formato .ktr/.kjb, catálogo de steps"]
    end
    subgraph pc ["Spoon (PDI 9.4) en tu computadora"]
        plugin["plugin Pentaho Live<br/>servidor MCP local"]
        canvas["lienzo de Spoon"]
        engine["motor de Kettle<br/>validar, preview, ejecutar"]
    end
    you -- "le pides" --> claude
    claude -. "consulta" .-> skill
    claude <-- "MCP por HTTP<br/>127.0.0.1:8765" --> plugin
    plugin <--> canvas
    plugin <--> engine
    canvas -- "ves aparecer cada step" --> you
```

- El **plugin de Spoon** es un plugin de ciclo de vida de Kettle. Cuando abre Spoon, levanta un servidor
  MCP en `127.0.0.1:8765` con 73 herramientas y aplica cada cambio en el hilo de interfaz de Spoon,
  con deshacer. El mismo jar agrega las perspectivas **Informes** y **Corrector** al selector de
  perspectivas de Spoon.
- El **plugin de Claude Code `pentaho-live`** conecta a Claude con ese servidor y suma la skill
  `pentaho-etl`, para que Claude use los ids de step y el XML exactos en vez de adivinarlos.

## Instalar

**Lo más fácil: pedíselo a tu agente.** En Claude Code o Codex escribí:

> instala IvI2511/pentaho-mcp

El agente sigue [`INSTALL.md`](INSTALL.md): te pregunta si ya tienes Pentaho, baja lo que falta, instala
el plugin de Spoon y se configura solo.

| Si… | Descargas del [último release](https://github.com/IvI2511/pentaho-mcp/releases/latest) | Script de Windows |
|---|---|---|
| **ya tienes PDI** | `pentaho-mcp.zip` (el plugin de Spoon, ~11 MB con la librería de PowerPoint): se descomprime en `data-integration/plugins/` | `instalar.ps1 -PdiHome <data-integration>` |
| **no tienes PDI** | `pdi-ce-9.4-con-pentaho-mcp.zip` (PDI 9.4 CE con el plugin ya puesto, ~370 MB) | `instalar.ps1 -InstalarPdi` |
| **prefieres un instalador de Windows** | `Instalador_PentahoLive_X.Y.Z.exe`: PDI 9.4 CE + plugin + Java 21, o solo el plugin en tu PDI; acceso en el menú Inicio y el comando `pentaho-live`. Todavía no está firmado: en el aviso de SmartScreen, **Más información → Ejecutar de todas formas** ([detalles](INSTALL.md#option-c--windows-installer-exe)) | no hace falta |

Después abre Spoon, reinicia Claude Code (o ejecuta `/reload-plugins`) y pídele tu primer ETL.

<details>
<summary><strong>Instalación a mano y otros agentes</strong></summary>

**Plugin de Claude Code:**

```
/plugin marketplace add IvI2511/pentaho-mcp
/plugin install pentaho-live@pentaho-tools
```

En la app de escritorio de Claude: **Settings → Plugins → Add → marketplace** `IvI2511/pentaho-mcp`, y
después instalá **Pentaho Live** desde **Discover**.

**Plugin de Spoon con el script, Windows (PowerShell):**

```powershell
Invoke-WebRequest -UseBasicParsing https://raw.githubusercontent.com/IvI2511/pentaho-mcp/main/instalar.ps1 -OutFile $env:TEMP\instalar-pentaho-mcp.ps1
powershell -ExecutionPolicy Bypass -File $env:TEMP\instalar-pentaho-mcp.ps1 -PdiHome "C:\ruta\a\data-integration"
```

Sin `-PdiHome` el script busca en las carpetas habituales; `-InstalarPdi` baja PDI 9.4 CE con el plugin
ya puesto.

**macOS / Linux:**

```bash
curl -fsSL https://raw.githubusercontent.com/IvI2511/pentaho-mcp/main/instalar.sh -o /tmp/instalar-pentaho-mcp.sh
bash /tmp/instalar-pentaho-mcp.sh --pdi "/ruta/a/data-integration"   # o --instalar-pdi
```

**Codex:** `codex mcp add pentaho --url http://127.0.0.1:8765/mcp`, y copia `claude/skills/pentaho-etl`
a `~/.codex/skills/pentaho-etl`.

**Para probar:** con Spoon abierto, `curl http://127.0.0.1:8765/ping` responde `{"ok":true,"spoon":true,...}`.

**Otro puerto:** ejecuta el script con `-Puerto 8770` (le pone `PENTAHO_MCP_PORT` a Spoon) y cambia la
opción `mcp_url` del plugin a `http://127.0.0.1:8770/mcp` en `/config`.

Códigos de salida, problemas comunes y todos los pasos: [`INSTALL.md`](INSTALL.md) (en inglés).

</details>

## Qué incluye

| Pieza | Qué es | Dónde |
|---|---|---|
| **Plugin de Spoon** | `@LifecyclePlugin` de Kettle que levanta el servidor MCP al abrir Spoon, y las perspectivas Informes y Corrector (`@SpoonPlugin`) en el mismo jar (bytecode Java 8, compilado contra PDI 9.4) | [`spoon-plugin/`](spoon-plugin/) |
| **Skill `pentaho-etl`** | Formato `.ktr` / `.kjb`, catálogo de 216 steps y 77 job entries generado desde una instalación real, guía comentada de los steps más usados, ejemplos que corren y los errores que dan de verdad Pan y Kitchen | [`claude/skills/pentaho-etl/`](claude/skills/pentaho-etl/) |
| **Conexión MCP `pentaho`** | Apunta Claude Code al plugin de Spoon (dirección configurable con `mcp_url`) | [`claude/.mcp.json`](claude/.mcp.json) |
| **Instaladores** | Encuentran o bajan PDI, instalan el plugin de Spoon y configuran Claude Code y Codex | [`instalar.ps1`](instalar.ps1), [`instalar.sh`](instalar.sh) |

<details>
<summary><strong>Qué cubren las herramientas MCP</strong></summary>

| Área | Qué puede hacer Claude |
|---|---|
| Documentos | Ver qué hay abierto; crear, abrir, guardar, cambiar y cerrar transformaciones y jobs |
| Steps y hops | Buscar un tipo de step y su XML por defecto; agregar, configurar, renombrar, mover, borrar y conectar steps; habilitar o deshabilitar hops; elegir si un step copia o reparte las filas |
| Jobs | Agregar, configurar y conectar entradas; hops de éxito / error / incondicionales |
| Campos | Listar los campos que salen de cada step, antes de configurar el siguiente |
| Controles | Validar; preview de filas reales de cualquier step |
| Calidad | Revisar buenas prácticas más allá de la validación, con un puntaje de 0 a 100 (y, si lo pides, leyendo las fuentes para probar un problema); aplicar los arreglos propuestos; silenciar una regla donde es a propósito |
| Modo clase | Reproducir una lección paso a paso con notas en el lienzo y una pestaña Lección (ritmo guiado, automático o manual), seguirla, volver atrás, ocultar, mostrar o quitar sus notas y convertirla en una guía |
| Robustez | Encontrar lo que pararía una carga o perdería filas con datos sucios; robustecerla (rechazos con su motivo, campos obligatorios, duplicados, huérfanos) con un solo Ctrl+Z; demostrarlo con una prueba de datos sucios en un entorno aislado que solo usa copias y bases de datos de archivo |
| Ejecución | Ejecutar una transformación o un job, seguir su estado y su log, detenerlo |
| Bases de datos | Listar tipos de conexión; agregar, probar y borrar conexiones; ver tablas y columnas; correr consultas SQL (solo lectura salvo que pidas escribir) |
| Parámetros | Listar, definir y borrar parámetros |
| Lienzo | Agregar notas, seleccionar steps, ordenar el lienzo, sacar una imagen del lienzo |
| Corrector | Armar un pack de ejercicio desde una solución (casos, casos ocultos, salida esperada, reglas, rúbrica, copia para alumnos), validarlo, corregir una entrega o una carpeta entera en JVMs aparte, seguir el lote, y obtener la planilla y la devolución de cada alumno |
| Informes | Armar el informe completo de una transformación o un job (HTML, PDF, Markdown, PowerPoint, Word, Excel, imágenes, JSON), solo o con varios documentos combinados, con linaje, diccionario de datos, métricas, calidad técnica y una narrativa opcional; leer sus datos; abrirlo en la perspectiva Informes de Spoon; un informe rápido en Markdown con diagrama Mermaid |

</details>

## Privacidad y seguridad

Todo corre en tu computadora. No hay servidores, cuentas, analíticas ni telemetría.

- La conexión MCP va solo a `http://127.0.0.1:8765/mcp`. El plugin de Spoon escucha únicamente en la
  interfaz loopback y rechaza pedidos de páginas web de otros orígenes.
- Las contraseñas de las bases se enmascaran antes de devolverle cualquier cosa a Claude. Lo recomendado
  es que escribas tú las contraseñas en Spoon.
- Solo se escriben archivos donde le pides a Claude que guarde; las transformaciones se ejecutan solo
  cuando lo pides.
- Los informes nunca llevan contraseñas. Pueden llevar nombres de hosts, rutas de archivos y, en el perfil
  de datos y la revisión profunda de calidad, valores reales de tus datos (se pueden ocultar en los ajustes
  del informe). El perfil y la
  opción «Medir» ejecutan una copia de la transformación solo cuando lo pides, y esa copia escribe sus
  salidas como una ejecución normal.
- La revisión de calidad (`lint_etl`) no ejecuta la transformación. Su modo profundo lee hasta 5000 filas
  de tus fuentes solo cuando lo pides, con previews limitadas a steps de solo lectura, y sus hallazgos
  pueden citar valores que leyó.
- El corrector ejecuta la solución y las entregas solo cuando se le pide, cada una en un proceso Java
  aparte con una configuración de Kettle vacía y sobre copias de los datos; nunca modifica una entrega y
  escribe los resultados solo en la carpeta elegida. El modo confianza (dejar correr steps peligrosos)
  existe solo en la perspectiva Corrector de Spoon, nunca por MCP.
- La prueba de datos sucios (`stress_test`) solo corre cuando la pides, sobre una copia de la
  transformación en un proceso de Java aparte con su propia configuración de Kettle, y solo si todas las
  bases de datos que usa son archivos SQLite o H2 (que antes copia). Nunca se conecta a otra base de datos
  y solo escribe en una carpeta temporal.
- Lo que devuelven las herramientas (steps, nombres de campos, logs, filas de preview) pasa a ser parte
  de tu conversación con Claude, como cualquier otra cosa que compartas.

Política completa: [`PRIVACY.md`](PRIVACY.md) (en inglés).

## Compatibilidad

| | Estado |
|---|---|
| Pentaho Data Integration | **9.4 CE** (probado con 9.4.0.0-343) |
| Java | **11 o posterior** (probado con Java 21) |
| Windows | Probado |
| macOS | Probado en macOS 15 y 26 (GitHub Actions): chip Apple bajo Rosetta 2 con un Java x64, e Intel. Instalación, Pan y todas las llamadas MCP andan; falta mirar la ventana de Spoon en un Mac de verdad. [Pasos](INSTALL.md#macos-and-linux) |
| Linux | Probado en Ubuntu 24.04 y 26.04 (GitHub Actions); necesita GTK 2 (`libgtk2.0-0t64`) |
| Agentes | Claude Code (CLI y app de escritorio); Codex con la URL del MCP y la skill |

## Limitaciones

- **ChatGPT en la web no se puede conectar**: solo usa servidores MCP públicos por HTTPS, y este escucha
  en `127.0.0.1` a propósito. En el chat de claude.ai solo sirve la skill; para editar en vivo hace falta
  un agente en la misma computadora donde corre Spoon.
- **PDI no trae drivers de MySQL, MariaDB, SQL Server ni Oracle** (sí de PostgreSQL, SQLite y H2).
  Copia el `.jar` del conector a `data-integration/lib/` y reinicia Spoon (con MySQL Connector/J 8+
  alcanza el tipo de conexión MySQL normal).
- El PDI 9.4 CE del release no tiene contenedor OSGi (karaf), así que faltan los steps de big data y
  streaming que dependen de él. Los 216 steps del catálogo están todos.
- Abrí Spoon antes de arrancar el agente; si el agente arrancó primero, reinicialo para que vea las
  herramientas.
- Corrector: corregir una entrega es ejecutar código escrito por otra persona. El proceso aparte y la
  política de steps (se bloquean los de código, red y borrado de archivos) bajan el riesgo, pero **no es un
  sandbox real**: para entregas de desconocidos, una máquina virtual. Corrige transformaciones (`.ktr`);
  los jobs todavía no.
- Informes: el PDF y las imágenes de los diagramas usan **Microsoft Edge o Google Chrome** (Windows trae
  Edge). Sin ninguno de los dos, el PDF sale por el diálogo de impresión del navegador y en PowerPoint los
  diagramas de linaje y de tablas van como tablas. La vista previa dentro de Spoon usa el motor de
  Internet Explorer que trae PDI, así que no tiene diagramas interactivos: **Abrir en el navegador**
  muestra el informe completo.
- El modo clase funciona **solo con transformaciones** (todavía no con jobs), una lección por
  transformación; con más de 12 pasos avisa y propone partirla. Si la lección muestra filas, guarda antes el `.ktr` junto a sus datos.

## Lo nuevo en 0.6.0

«Calidad y clases», 73 herramientas MCP en total:

- **Linter** (`lint_etl`, `apply_lint_fixes`, `suppress_lint`): lo que Kettle deja pasar (un Group by sin
  ordenar antes, el símbolo decimal vacío, rutas absolutas, contraseñas en el archivo...) con una nota de
  calidad de 0 a 100, los arreglos seguros en un solo Ctrl+Z y una sección de calidad en los informes.
- **Corrector de ejercicios** (`create_exercise_pack`, `grade_exercise`, `grade_submissions`...): armas un
  pack desde tu solución y corriges una carpeta entera de entregas, cada una en una JVM aparte, con una pista
  por error típico, la devolución de cada alumno y la planilla para Excel. Spoon suma la perspectiva
  **Corrector**.
- **Modo clase** (`lesson_start`, `lesson_notes`...): Claude construye la transformación un step por cada
  clic en **Siguiente ▶**, con una nota numerada en el lienzo por step, y la lección se convierte en apuntes.
- **Robustez** (`lint_etl robustness=true`, `harden_etl`, `stress_test`): cada fila mala va a un archivo de
  rechazos con su motivo, y copias rotas de las entradas demuestran que no se pierde ninguna fila y que
  volver a correr la carga no duplica nada. Guía: `manual/09-robustez.md`.

Detalle en [`CHANGELOG.md`](CHANGELOG.md).

## Lo nuevo en 0.5.1

La perspectiva **Informes** en Spoon y tres herramientas MCP que la acompañan: `build_report` (escribe el
informe en cualquier formato), `get_report_data` (los datos del informe en JSON, para que Claude escriba
una narrativa con números reales) y `show_report` (abre el informe en Spoon). Los informes se exportan a
HTML, PDF, Markdown, PowerPoint, **Word**, **Excel** e **imágenes** de cada diagrama; varios documentos (un
job y sus transformaciones, los ejercicios de una entrega) pueden ir en un solo archivo con portada e
índice. Los diagramas de flujo y de linaje se reparten en filas para llenar la página o la diapositiva.
Detalle en [`CHANGELOG.md`](CHANGELOG.md).

Además:

- **[`ejemplo/`](ejemplo/)**, una práctica de ETL completa como las de la facultad («Grano de Oro», cuatro
  cafeterías, datos sucios en CSV, Excel y JSON cargados en un modelo estrella en SQLite). Muestra todo:
  la ETL construida en vivo en el lienzo de Spoon por el MCP ([GIF](ejemplo/capturas/construccion-en-vivo.gif)),
  comprobada al número y documentada por la pestaña Informes ([PDF](ejemplo/informes/04_hechos_ventas.pdf)).
  Con un [guion de demo](ejemplo/guion-demo.md).
- **Patrones listos** en la skill (`claude/skills/pentaho-etl/patrones/`): CSV a staging, deduplicación,
  carga incremental por fecha, dimensión SCD tipo 2 y carga de hechos con claves subrogadas, cada uno
  probado con Kitchen.
- **Botón «Ordenar lienzo»** y opción del menú contextual en los lienzos de transformaciones y jobs de Spoon (un solo Ctrl+Z).
- **`set_error_handling`** (58 herramientas): el manejo de errores de un step, como «Definir manejo de
  errores...» de Spoon, para que Claude mande los rechazos con un campo de motivo.

## Desarrollo

| Qué | Cómo |
|---|---|
| Compilar el plugin de Spoon | `powershell -ExecutionPolicy Bypass -File spoon-plugin\build.ps1 -PdiHome <data-integration>` → `spoon-plugin/dist/` |
| Validar el plugin de Claude | `claude plugin validate --strict claude` y `claude plugin validate --strict .` |
| Regenerar el catálogo de steps | `bash tools/generador-catalogo/generar.sh <data-integration>` |
| Diseño interno (API de Spoon, hilos, diálogos a evitar) | [`docs/api-spoon.md`](docs/api-spoon.md) |

Las contribuciones son bienvenidas: mira [`CONTRIBUTING.md`](CONTRIBUTING.md). La documentación interna
y los comentarios del código están en español; la skill, las herramientas MCP, la ficha del plugin y la
documentación para usuarios, en inglés.

## Licencia

[MIT](LICENSE) © IvI2511

---

<sub>Proyecto comunitario independiente. Sin relación con Hitachi Vantara, que no lo respalda ni le da
soporte. Pentaho es una marca de Hitachi Vantara. PDI 9.4 CE lo distribuye Hitachi Vantara con licencia
Apache 2.0.</sub>
