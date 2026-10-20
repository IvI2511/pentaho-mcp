# Informes en Spoon: diseño (Pentaho Live 0.5.0)

Documento de diseño para implementar la perspectiva **Reports / Informes / Relatórios** dentro de
Spoon (PDI 9.4.0.0-343 CE). La perspectiva arma sola el informe de una transformación o de un job y
lo exporta a HTML, PDF, Markdown y PowerPoint. Sale de cuatro investigaciones hechas el 20-oct-2026
sin abrir Spoon: perspectiva, navegador embebido, librerías y fuentes de datos. Los experimentos
están listados en el Anexo A. El Anexo B corrige `docs/api-spoon.md`.

**Cómo leer las marcas de evidencia.** Cada afirmación lleva una:

| Marca | Qué significa |
|---|---|
| **[P]** | Probado con un experimento ejecutable fuera de Spoon, con los jars reales de PDI 9.4 y Java 21. |
| **[S]** | Ya funciona dentro de Spoon en el plugin 0.4.0 (`generate_report`, `canvas_screenshot`, `validate`, `preview_step`, `run`). |
| **[B]** | Leído en el bytecode o con javap. No se ejecutó. |
| **[NP]** | **No probado.** Hay que probarlo antes de darlo por bueno. El §9 junta todos estos puntos. |

---

## 0. Decisiones en una tabla

| Tema | Decisión |
|---|---|
| Dónde vive | En el mismo jar `pentaho-mcp-core-<v>.jar`, dentro de `data-integration\plugins\pentaho-mcp\`. Es una segunda clase anotada, `@SpoonPlugin`, al lado del `@LifecyclePlugin` que ya existe. No lleva XUL ni `plugin.xml`. |
| Classloader | Las dos anotaciones dejan `classLoaderGroup` **vacío**, como está hoy. Así cargan en el mismo `KettleURLClassLoader` y comparten el estado estático [P]. |
| Perspectiva | Id `pentaho-live-reports`. Se llama Reports, Informes o Relatórios según el idioma. Aparece sola en el desplegable de perspectivas de la barra [B]. `getActiveMeta()` devuelve `null`. |
| Modelo | Un solo árbol de POJOs, serializable con el Jackson 2.9 de `lib\`: **Informe → Secciones → Bloques**. Hay ocho tipos de bloque: párrafo, lista, tabla, indicadores, diagrama, gráfico, imagen y aviso. Además, cada sección guarda sus `datos` crudos en JSON. Los recolectores producen bloques y los renderizadores solo saben dibujar bloques. |
| Gráficos | **SVG propio generado en Java**: barras, Gantt y líneas. No se usan Chart.js ni JFreeChart. El mismo SVG sirve en IE11, en el HTML, en el PDF (queda vectorial), en el Markdown y en el PPTX. |
| Diagramas | **Mermaid 11.4.1** (MIT), embebido en el jar. El diagrama de flujo va además como la imagen real del lienzo (TransPainter/JobPainter) [S]. |
| HTML | Un solo archivo, autocontenido y offline. Por defecto es **interactivo**: Mermaid va inline y el archivo pesa unos 2,6 MB. La variante **liviana** no tiene JS, lleva los SVG ya renderizados y pesa entre 50 y 300 KB; solo existe si hay Edge o Chrome. |
| Vista previa en Spoon | El SWT de PDI solo trae **IE11** [P]. La vista previa muestra **HTML estático sin JS**. Los diagramas Mermaid llegan en una segunda pasada, renderizados por **Edge headless** [P]. Siempre hay un botón «Abrir en el navegador». Edge embebido queda como opción de fase 2: requiere cambiar el `swt.jar`. |
| PDF | Edge o Chrome headless con `--print-to-pdf` sobre el HTML [P]. Si no hay navegador, se abre el HTML con el diálogo de impresión. **No se usa iText.** |
| Markdown | Un `.md` más la carpeta `<nombre>_archivos/` con el PNG del lienzo y los SVG de los gráficos. Los diagramas van en bloques ```` ```mermaid ````, que Obsidian y GitHub dibujan solos. |
| PowerPoint | **Apache POI 4.1.1 XSLF**. Se copia en el build desde `plugins\pdi-core-plugins\lib` a `plugins\pentaho-mcp\lib` [P]. Las diapositivas llevan el lienzo como PNG, los gráficos como SVG con un PNG de respaldo, y los diagramas Mermaid como PNG si hay navegador; si no, como tablas. |
| Tema | La configuración vive en `<KETTLE_HOME>\.kettle\pentaho-live\informes.json`, junto con `logo.png` o `logo.jpg`. El estilo es neutro, con el logo, los colores, el autor y la institución del usuario. Lleva un pie de una línea («Pentaho Live · IDC Studios») y el símbolo IDC como marca de agua al 8 %. |
| Hilos | En el hilo de UI solo pasan la captura (clon del documento y pintado del lienzo) y los widgets. Todo lo demás corre en un pool de 2 hilos (`pentaho-live-informes`). Nada bloquea la UI. |
| MCP | Tres tools nuevas: `build_report`, `get_report_data` y `show_report`. Las tres usan el mismo motor (`InformeServicio`). La narrativa de la IA es opcional y entra como un texto más del modelo. `generate_report` y `canvas_screenshot` no cambian. |
| Sin IA | Todo se arma solo. Cada sección trae una frase de resumen hecha con plantillas por idioma. |

---

## 1. Arquitectura

### 1.1 Piezas

```
                pentaho-mcp-core-<v>.jar   (un solo KettleURLClassLoader child-first, padre = lib\*.jar + swt.jar)
 ┌──────────────────────────────────┐        ┌─────────────────────────────────────────┐
 │ @LifecyclePlugin                 │        │ @SpoonPlugin + @SpoonPluginCategories    │
 │ McpLifecycleListener (existe)    │        │ ReportsSpoonPlugin                       │
 │   McpHttpServer + *Tools         │        │   ReportsPerspective (SpoonPerspective)  │
 │   ReportTools: build_report,     │        │     informes.ui.PanelInformes (SWT)      │
 │   get_report_data, show_report   │        │                                          │
 └───────────────┬──────────────────┘        └───────────────────┬──────────────────────┘
                 └─────────────────────┬─────────────────────────┘
                          InformeServicio   (motor, todo estático)
     ┌──────────────────┬─────────────────┴───────┬──────────────────────┬─────────────────────┐
  CapturaUi        Recolectores Sec*          Renderizadores           navegador.Chromium
  (hilo UI)        (RunMotor, ReportMotor,    RenderHtml/Markdown/     (Edge/Chrome headless:
                    ReportImagen, metaverse)  Pptx + RenderPdf          PDF y pre-render Mermaid)
```

### 1.2 Cómo se registra la perspectiva

**Cómo la encuentra Spoon [B]; el registro también se probó fuera de Spoon [P].**

- `Spoon.registerUIPluginObjectTypes()` agrega `SpoonPluginType` antes de `KettleEnvironment.init()`. Por eso el mismo escaneo de anotaciones que encuentra `@LifecyclePlugin` encuentra también `@SpoonPlugin`.
- `SpoonPluginType` solo mira `plugins` y `plugins/repositories`, relativo al directorio de trabajo, que es `data-integration`. **No mira `~/.kettle/plugins` ni `KETTLE_PLUGIN_BASE_FOLDERS`.** El jar tiene que estar en `data-integration\plugins\pentaho-mcp\`, que es donde lo pone el instalador.
- Orden de arranque, dentro de `Spoon.init(null)`:
  1. Se carga `ui/spoon.xul`.
  2. Se crea `MainSpoonPerspective`.
  3. `SpoonPluginManager.<clinit>` llama a `pluginAdded` para cada `@SpoonPlugin`: instancia la clase y, si `getPerspective() != null`, llama a `addPerspective`.
  4. `applyPluginsForContainer("spoon", …)`.
  5. `setDeck` e `initialize()`, que arma el desplegable de perspectivas.
  6. Después, `lifecycleSupport.onStart`, que levanta el servidor MCP.
- `initialize()` agrega **un solo botón desplegable** a `main-toolbar` con todas las perspectivas registradas. **No hace falta ningún botón ni XUL propio**: la nuestra aparece sola en esa lista [B]. El ítem en *Ver › Perspectivas* es XUL estático y queda fuera de la v1 [NP].

```java
// io.github.ivi2511.pentahomcp.ReportsSpoonPlugin
@SpoonPlugin( id = "PentahoLiveReports", image = "", name = "Pentaho Live Reports" )  // SIN classLoaderGroup
@SpoonPluginCategories( { "spoon" } )
public class ReportsSpoonPlugin implements SpoonPluginInterface {
  private final ReportsPerspective perspective = new ReportsPerspective(); // sin widgets: corre en un <clinit>
  public ReportsSpoonPlugin() { }
  public void applyToContainer( String category, XulDomContainer c ) { /* v1: nada */ }
  public SpoonLifecycleListener getLifecycleListener() { return null; }
  public SpoonPerspective getPerspective() { return Interruptor.informesApagados() ? null : perspective; } // Spoon la llama 2 veces: misma instancia
}
```

> **Trampa del sketch de la investigación.** `scratchpad\informes\perspectiva\sketch\ReportsSpoonPlugin.java`
> declara `classLoaderGroup = "pentaho-live"` solo en el `@SpoonPlugin`. Eso es justo la variante C
> del experimento: **dos classloaders, dos copias de cada clase y el estado partido** [P]. No hay que
> copiarlo. Las dos anotaciones se dejan sin grupo.

**Reglas duras.** `Spoon.init` solo atrapa `Exception` en el tramo de los pasos 2 a 5 [B]:

- Una excepción en `applyToContainer`, `getId` o `getDisplayName` saltea `setDeck` e `initialize()`, y Spoon arranca sin lienzo.
- Una `RuntimeException` en el constructor o en `getPerspective()` se convierte en `ExceptionInInitializerError`, y **Spoon no arranca**.

Por eso esos métodos tienen que ser triviales y envolver todo en `try/catch (Throwable)`.

**Interruptor.** La propiedad `PENTAHO_LIVE_REPORTS=off` (system property o línea en `kettle.properties`) hace que `getPerspective()` devuelva `null`. Se lee con `EnvUtil.getSystemProperty`, igual que `McpConfig`. `kettle.properties` ya está cargado cuando corre `SpoonPluginManager.<clinit>`, porque `KettleEnvironment.init()` va antes en `Spoon.main` [B]. Sirve para desactivar la perspectiva si algo sale mal en la máquina de alguien, sin desinstalar el MCP.

**`ReportsPerspective implements SpoonPerspective` [B, más la prueba de SWT con `setParent` [P]]:**

| Método | Qué hace |
|---|---|
| `getId()` | Devuelve la constante `"pentaho-live-reports"`. La tarjeta del deck queda como `"perspective-pentaho-live-reports"`, y `Spoon.loadPerspective(String)` la usa. |
| `getDisplayName(Locale)` | Devuelve `en` → «Reports», `es` → «Informes», `pt` → «Relatórios». Spoon la llama una sola vez, con `LanguageChoice.getInstance().getDefaultLocale()` [B]. No puede tirar excepciones. |
| `getUI()` | Es perezoso. Crea un `Composite` sobre `Spoon.getInstance().getShell()` con `GridData(FILL, FILL, true, true)`, y dentro, en un try, el `PanelInformes`. Spoon lo re-cuelga con `setParent` (reparenting probado [P]). Si el panel falla, muestra un `Label` con el error en lugar de propagarlo. |
| `setActive(boolean)` | Avisa a los listeners. Al activarse, refresca el combo de documentos y, si el documento cambió, lanza la vista previa liviana (§5.3). Todo dentro de `catch Throwable`, porque `loadPerspective(int)` solo atrapa `KettleException` [B]. |
| `getOverlays()` / `getEventHandlers()` | Devuelven `Collections.emptyList()`. |
| `getPerspectiveIcon()` | Devuelve `null`: Spoon 9.4 nunca la llama [B]. |
| `getActiveMeta()` | Devuelve **`null`** (es la opción 1 de la investigación). Con Informes al frente, Ctrl+S queda deshabilitado y no guarda una pestaña oculta. Lo que esto rompe en el MCP se arregla en §1.5. |
| `onFileClose()` | No está en la interfaz. `tabCloseSelected` la busca por reflexión cuando hay otra perspectiva activa [B]. Devuelve `false`. Qué hace Spoon con ese valor: [NP]. |

**Cosas que no se tocan desde la perspectiva.** No se llama a `spoon.openFile`, `saveFileAs`, `tabClose`, `PreviewRowsDialog`, `ErrorDialog`/`MessageBox` automáticos, `Docs.activar` ni `SWT.EDGE`, que en este SWT no existe y no compila. `FileDialog`, `DirectoryDialog`, `ColorDialog` y `MessageBox` se abren **solo** cuando el usuario hace clic, nunca desde hilos del MCP ni desde `setActive`.

### 1.3 Mismo jar, mismo código

| Situación | Resultado |
|---|---|
| Las dos anotaciones sin `classLoaderGroup` | Mismo `KettleURLClassLoader`, compartido por carpeta. Las clases y los estáticos son los mismos [P]. **Es lo que se usa.** |
| Mismo grupo en las dos | También se comparte [P]. No hace falta. |
| Grupo solo en una | Dos loaders y el estado partido [P]. **Prohibido.** |
| El jar copiado además en `~\.kettle\plugins` | El lifecycle de esa copia tendría su propio loader [B]. El instalador tiene que garantizar una sola copia. |

**Chequeo en tiempo de ejecución.** Cada punto de entrada escribe la identidad de su loader en una system property: `pentaho.live.loader.lifecycle` y `pentaho.live.loader.spoon`. En `onStart`, el MCP compara las dos y, si difieren, deja un `logError` claro. Las system properties sí cruzan loaders; una clase compartida no serviría, porque estaría duplicada.

**Qué se comparte de verdad.**

- Las clases que ya existen, todas *package-private* en `io.github.ivi2511.pentahomcp`: `RunMotor` (pool, `enSegundoPlano`, `validar`, `recortar`, `paso`, `ejecutarTrans/Job`, `STEPS_QUE_ESCRIBEN`), `ReportMotor` (Mermaid, «qué hace cada step», cálculo de campos), `ReportImagen` (lienzo a PNG), `ReportTools.clonar` (copia desligada) y `Docs` (ids y documentos abiertos).
- El motor nuevo, `InformeServicio`, al que llaman tanto el panel como las tools MCP.

**Paquetes.**

| Paquete | Contenido | Puede ver |
|---|---|---|
| `io.github.ivi2511.pentahomcp` | Puntos de entrada (`ReportsSpoonPlugin`, `ReportsPerspective`), `InformeServicio`, `CapturaUi`, `ContextoKettle` y los recolectores `Sec*` (necesitan las clases *package-private* de arriba) | Todo |
| `…pentahomcp.informes` | El contrato: interfaces (`Recolector`, `Renderizador`, `Contexto`, `Progreso`) y datos (`Captura`, `Pedido`, `Formato`, `Resultado`, `Rotulos`, `SeccionId`) | Kettle (tipos), Jackson |
| `…informes.modelo` | Los POJOs del informe | Jackson |
| `…informes.tema` | `Tema`, `Ajustes`, `SimboloIdc`, `Paleta` | Kettle `Const`, Jackson |
| `…informes.graficos` | `GraficosSvg`, `SvgAPng` (Batik) | Modelo, tema |
| `…informes.render` | `RenderHtml`, `RenderMarkdown`, `RenderPptx` (POI) | Modelo, tema, gráficos |
| `…informes.navegador` | `Chromium`, `RenderPdf`, `PrerenderMermaid` | Modelo, render |
| `…informes.ui` | `PanelInformes` y sus partes (SWT) | Servicio, tema |

Solo hay SWT en `informes.ui`, `CapturaUi` y `ReportImagen`, que ya existe. La única excepción es `Captura`, que guarda una `ImageData` como dato, sin dibujar nada. Los renderizadores no conocen ni Kettle ni SWT: se pueden probar sin PDI arrancado.

### 1.4 Hilos

| Dónde | Qué |
|---|---|
| Hilo de UI | Callbacks de la perspectiva y del panel. `CapturaUi.tomar` (clon con `ReportTools.clonar` y `ReportImagen.pintar`, que devuelve `ImageData`). Lectura de `TransGraph.getTrans()` / `JobGraph.getJob()` de la última corrida [S]. `browser.setUrl`. Diálogos por clic. |
| Pool `pentaho-live-informes` (2 hilos daemon) | `InformeServicio.armar` (recolectores), codificación de PNG (`ReportImagen.png`), renderizadores y lanzamiento de procesos de Chromium. **El context classloader del hilo apunta al loader del plugin** mientras dura la tarea: POI lo necesita para JAXB [P]. |
| Pool de `RunMotor` (existe) | Subtareas con timeout: cálculo de campos, validación, linaje, corridas de perfil y medición (`RunMotor.enSegundoPlano`). |
| Procesos externos | `msedge.exe` o `chrome.exe` headless, con un semáforo de 2. La salida se redirige a un archivo para que el pipe no se llene. Timeout de 60 s. |
| Hilos HTTP del MCP | Usan `Docs.ui(...)` para la captura y después `InformeServicio.armar(...).get(timeout)`. Nunca hacen trabajo pesado en la UI. |

Reglas:

- **Nunca hacer `Future.get()` en la UI.** Los resultados vuelven con `display.asyncExec` y se chequea `isDisposed()` antes de tocar nada.
- Cada pedido del panel lleva un número de generación. Si llega uno nuevo, el anterior se cancela con `Progreso.cancelado()` → `trans.stopAll()` / `destroyForcibly()`.
- Las tools MCP y el panel pueden correr a la vez porque el pool tiene 2 hilos. Las tareas no comparten estado mutable: la caché de SVG pre-renderizados es un `ConcurrentHashMap`.

**Secuencia de una vista previa:**

1. **UI.** `CapturaUi.tomar(spoon, metaVivo)` devuelve una `Captura`: la copia, la `ImageData` del lienzo a escala 2 y las referencias a la última corrida.
2. **Worker.** `InformeServicio.armar(captura, pedido, progreso)` devuelve el `Informe`.
3. **Worker.** `RenderHtml(ESTATICO)` escribe `%TEMP%\plive\vista\<docId>\vista.html`. Después, en la UI, `browser.setUrl(file)`.
4. **Worker, solo si hay Chromium.** `PrerenderMermaid.svg(diagramas)` vuelve a renderizar en modo ESTATICO, ahora con los SVG, y hace `setUrl` otra vez.

**Secuencia de `build_report` (MCP):**

1. **Hilo HTTP.** `Docs.ui(() -> CapturaUi.tomar(...))` toma la captura **sin activar la pestaña ni cambiar de perspectiva**.
2. `armar(...).get(timeout)`.
3. `exportar` para cada formato pedido.
4. Devuelve rutas y avisos.

### 1.5 La parte MCP

**Tools nuevas en `ReportTools.register`.** Todas toman `document` como las demás.

| Tool | Argumentos | Qué hace |
|---|---|---|
| `build_report` | `document?`; `sections?` (ids de §2.3; por defecto, los marcados por defecto que aplican); `formats` (`html`, `html_light`, `pdf`, `md`, `pptx`, `json`); `output_dir?` (por defecto, el de la configuración, y si no hay, `<carpeta del .ktr>\informes`); `file_name?`; `language?` (`en`/`es`/`pt`); `title?`; `narrative?`; `lineage_step?` (step destino del linaje; por defecto, los steps finales); `profile_step?` + `profile_rows?` (sin `profile_step`, la sección de perfil no corre, porque ejecuta la transformación); `metrics?` (`last_run`, `run_copy` o `none`; `run_copy` ejecuta una copia y **escribe** las salidas, y la descripción lo dice); `query_database?` (bool; consulta PK y FK de las tablas destino); `show_in_spoon?` (bool; abre la perspectiva con ese informe, solo si el usuario lo pidió) | Arma el informe y lo exporta. Devuelve `{files:[{format,path,bytes}], sections:[{id,status,reason}], warnings:[]}`. |
| `get_report_data` | `document?`, `sections?`, `language?`, `max_rows?` | Devuelve el modelo en JSON, **sin binarios** (`InformeServicio.sinBinarios`). Le sirve a la IA para escribir una narrativa basada en los datos reales. |
| `show_report` | `document?`, `sections?`, `language?`, `narrative?` | Lleva la perspectiva Informes al frente (`spoon.loadPerspective(ReportsPerspective.ID)` en la UI), selecciona el documento y lanza la vista previa con `PanelInformes.mostrar(docId, pedido)`. Solo si el usuario lo pide. |

**Narrativa de la IA.** Es texto plano con poco Markdown: párrafos, viñetas `- ` y `**negrita**`. Cada texto tiene un tope de 2.000 caracteres. Forma:

```json
{"summary": "…", "sections": {"lineage": "…", "metrics": "…"}, "conclusions": "…"}
```

- `summary` va al principio de «Resumen».
- Cada `sections.<id>` va al principio de esa sección.
- `conclusions` arma la sección `narrative`.
- Los bloques marcan `ia=true`. Con `Tema.rotularIa` (que viene activo por defecto) el informe muestra un rótulo discreto: «Texto redactado con asistencia de IA».
- Todo se escapa; no se acepta HTML crudo.

**Arreglos al código existente (todos en el paquete de trabajo WP10):**

- `Docs.activo()` (`Docs.java:108`) deja de usar `spoon.getActiveMeta()`. Pasa a leer `getActiveTransGraph()` / `getActiveJobGraph()`, que leen el tab folder directamente y no dependen de la perspectiva activa [B]. Así `status` y las tools sin `document` siguen funcionando con Informes al frente.
- `DocumentTools` `new_transformation` y `new_job` (líneas 60 y 75) activan primero `MainSpoonPerspective`; hoy leen `getActiveTransformation()` justo después de `newTransFile()`.
- `Docs.resolverSinActivar(JsonNode)`: igual que `resolver`, pero no llama a `activar()`. Las tools de informes usan esta; `activar()` traería al usuario de vuelta a «Data Integration».
- `McpLifecycleListener.INSTRUCCIONES` suma una línea sobre `build_report` / `get_report_data`.
- `generate_report` queda igual en 0.5.0; en 0.6 pasa a usar el motor nuevo.

---

## 2. Modelo del informe y secciones

### 2.1 Modelo

Son POJOs con campos públicos, serializados con Jackson 2.9.10 de `lib\`. `Bloque` usa `@JsonTypeInfo(use = NAME, property = "tipo")` con `@JsonSubTypes`. Los `byte[]` salen en base64 y `sinBinarios()` los saca.

```java
package io.github.ivi2511.pentahomcp.informes.modelo;

public final class Informe {
  public int modelo = 1;                       // versión del esquema
  public String titulo, subtitulo, tipoDoc;    // tipoDoc: "transformation" | "job"
  public String archivo, nombreDoc, idioma;    // idioma: en | es | pt
  public String generado;                      // ISO-8601 con zona
  public Map<String, String> rotulos;          // "page","of","contents","generated_with","ai_label"... ya traducidos
  public List<Seccion> secciones = new ArrayList<>();
  public List<String> avisos = new ArrayList<>();
}
public final class Seccion {
  public String id;            // SeccionId.*
  public String titulo;        // ya traducido
  public String resumen;       // 1-2 frases automáticas (plantilla, sin IA)
  public String estado;        // "ok" | "parcial" | "omitida" | "error"
  public String motivo;        // por qué parcial/omitida/error (traducido)
  public List<Bloque> bloques = new ArrayList<>();
  public com.fasterxml.jackson.databind.JsonNode datos;   // forma cruda (las de §2.4); para JSON/MCP
}
public abstract class Bloque { public String id; public String titulo; public String nota; public boolean ia; }
public final class Parrafo     extends Bloque { public String texto; }                     // **negrita**, saltos de línea
public final class Lista       extends Bloque { public List<String> items; }
public final class Tabla       extends Bloque { public List<String> columnas; public List<List<String>> filas;
                                                public List<String> alineacion; /* "l","r","c" */ public String pie; }
public final class Indicadores extends Bloque { public List<Indicador> items; }
public final class Indicador   { public String etiqueta, valor, detalle; public String nivel; /* ok|warn|error|null */ }
public final class Diagrama    extends Bloque { public String clase;    /* flujo|job|linaje|er */
                                                public String mermaid;  /* sintaxis compatible con Mermaid 9+ (§3.3) */
                                                public Imagen imagen;   /* p.ej. lienzo real; opcional */
                                                public Tabla alternativa; /* cuando no se puede dibujar */ public String alt; }
public final class Imagen      extends Bloque { public String mime; public byte[] datos; public int ancho, alto;
                                                public float escala; /* 2 = pintada al doble para nitidez */ public String alt; }
public final class Grafico     extends Bloque { public String clase;   /* barras_h | gantt | linea */
                                                public String unidad; public List<String> categorias;
                                                public List<Serie> series; public List<Tramo> tramos; }
public final class Serie       { public String nombre; public List<Double> valores; }
public final class Tramo       { public String categoria, tipo; /* init|exec */ public long inicioMs, finMs; }
public final class Aviso       extends Bloque { public String nivel; /* info|warn|error */ public String texto; }
public final class Narrativa   { public String resumen, conclusiones; public Map<String, String> porSeccion; public String origen; /* usuario|ia */ }
```

**Por qué bloques genéricos.** Los renderizadores dibujan ocho tipos de bloque, no doce tipos de sección. Agregar una sección nueva no toca HTML, PDF, MD ni PPTX. Y los equipos de recolectores y de renderizadores pueden trabajar en paralelo contra un modelo de muestra (`ModeloMuestra`, §7).

### 2.2 Cómo dibuja cada formato los bloques

| Bloque | HTML (interactivo) | Vista previa IE11 y HTML liviano | PDF | Markdown | PPTX |
|---|---|---|---|---|---|
| Parrafo, Lista | `<p>` / `<ul>` | igual | igual | texto / `- ` | cuadro de texto con viñetas |
| Tabla | `<table>` con cabecera en el color primario | igual | igual, sin cortar filas entre páginas | tabla GFM | tabla XSLF, 10 filas por diapositiva y «(cont.)» |
| Indicadores | tarjetas (`inline-block`) | igual | igual | tabla de 2 columnas | 3 o 4 tarjetas por diapositiva |
| Diagrama | `imagen` si la hay, más `<pre class="mermaid">` | `imagen` más SVG pre-renderizado; si no hay, un recuadro y `alternativa` | Mermaid vectorial con texto seleccionable [P] | archivo PNG más ```` ```mermaid ```` | `imagen`, o PNG de Mermaid si hay navegador, o `alternativa` |
| Grafico | SVG inline (`GraficosSvg`) | igual (IE11 dibuja SVG inline [P]) | vectorial | archivo `.svg` | SVG con PNG de respaldo (`addSvgImage` [P]) |
| Imagen | `<img src="data:…">` al 50 % si `escala = 2` | igual | igual | archivo PNG | imagen ajustada |
| Aviso | recuadro con borde de color | igual | igual | `> [!WARNING]` (Obsidian y GitHub) | texto en cursiva y color de aviso |

### 2.3 Secciones

| id | Trans | Job | Marcada por defecto | Costo / efectos | Fuente de datos (API) | Bloques | Evidencia |
|---|---|---|---|---|---|---|---|
| `cover` | sí | sí | sí | nada | `getName`, `getFilename`, `getDescription`, `getExtendedDescription`, `getTransversion`/`getJobversion`, `getCreatedUser`, `getModifiedDate`; más `Tema` (autor, institución, logo) | Imagen (logo) y Lista | getters [B, javap] |
| `summary` | sí | sí | sí | nada (junta lo de las demás) | Cuentas de steps, entradas y hops; archivos leídos y escritos con `TransMeta.getResourceDependencies()` (como `ReportMotor.archivosDe`); salidas según `RunMotor.STEPS_QUE_ESCRIBEN`; los `resumen` de cada sección | Indicadores, Parrafo, Narrativa | [S] |
| `flow` | sí | – | sí | pintar en la UI | `ReportImagen.pintar` a escala 2 [S]; `ReportMotor.mermaidTrans` [S]; `ReportMotor.queHaceStep` [S] | Diagrama (imagen y mermaid), Tabla «qué hace cada step» | [S] |
| `job_flow` | – | sí | sí | pintar en la UI | `ReportImagen.pintar` (JobPainter) [S]; `ReportMotor.mermaidJob` [S]; `queHaceEntrada` [S]; las transformaciones y jobs que llama (nombre de archivo resuelto con las variables internas) | Diagrama, Tabla de entradas, Tabla «qué ejecuta» | [S] |
| `lineage` | sí | – | sí | cálculo de campos (puede conectarse a la BD) y metaverse | §2.4.1 | Diagrama (clase linaje), Tabla, Aviso | [P] fuera de Spoon |
| `dictionary` | sí | – | sí | cálculo de campos | `ReportMotor.calcularCampos` [S], que usa `getStepFields` con timeout; `ValueMetaInterface`: `getName`, `getTypeDesc`, `getLength`, `getPrecision`, `getConversionMask`, `getOrigin`, `getComments` | Tabla por cada salida final, Tabla anexa por step (opcional) | [S] |
| `profile` | sí | – | **no** | **ejecuta una copia** hasta N filas | §2.4.3 | Tabla por campo, Grafico (porcentaje de nulos), Aviso | [P] fuera de Spoon |
| `metrics` | sí | sí | sí, si hubo corrida | lee la última corrida; «Medir» ejecuta una copia | §2.4.2 | Indicadores, Grafico (barras, Gantt, línea), Tabla | [P] fuera de Spoon; lectura en Spoon [B] |
| `target_model` | si tiene Table output o Insert/Update | – | sí, si aplica | sin BD; con «consultar la base», se conecta | §2.4.4 | Diagrama `erDiagram`, Tabla de columnas, Aviso | [P] sobre SQLite |
| `validation` | sí | sí | sí | `checkSteps` (puede conectarse a la BD) | `RunMotor.validar(copia, …)` [S] | Indicadores (errores y advertencias), Tabla | [S] |
| `quality` (0.6, linter) | sí | sí | sí | cálculo de campos; con `Pedido.calidadProfunda` (`quality_deep`), previews de solo lectura que leen las fuentes | `LintMotor.analizar` sobre la copia (`SecCalidad`, docs/linter-diseno.md §8 y docs/linter-integracion.md §2.8) | Indicadores (puntaje y banda), Aviso por error, Tabla de hallazgos, Tabla de puntos, Tabla de lo no evaluado | [P] fuera de Spoon |
| `connections` | sí | sí | sí | nada | Lo mismo que `ReportMotor.conexiones/parametros` [S], pero estructurado: nombre, tipo, host, base. **Nunca contraseñas.** Más parámetros con su valor por defecto y su descripción (`listParameters`, `getParameterDefault`, `getParameterDescription` [B]) | Tabla y Tabla | [S] |
| `narrative` | sí | sí | si hay texto | nada | `Pedido.narrativa` (usuario o IA) | Parrafo (`ia` según el origen) | – |

**Orden en el informe:** cover, índice, summary, flow o job_flow, lineage, dictionary, target_model, profile, metrics, validation, quality (0.6), connections, narrative.

**Disponibilidad.** `InformeServicio.disponibles(captura, idioma)` es barato: no consulta campos, ni la base, ni ejecuta nada, así que puede llamarse en la UI. Para cada sección devuelve `{id, aplica, motivo, porDefecto, costo}`. El panel usa eso para habilitar o deshabilitar los checks y explicar por qué.

**Textos automáticos (sin IA).** Cada recolector arma `Seccion.resumen` con plantillas en su archivo de textos. El separador decimal y el de miles siguen el idioma. Ejemplo en `es`:

> «Lee 1 archivo CSV (ventas.csv), calcula, ordena y agrupa, y escribe 1 archivo. 8 steps y 7 hops.
> La última ejecución procesó 400.000 filas en 2,7 s; el step más lento fue «Leer ventas» (1,7 s).»

### 2.4 Detalle de las secciones con más riesgo

#### 2.4.1 Linaje de campos (WP3)

**Motor principal: el plugin metaverse que trae PDI, sin OSGi [P en 01–06].** El cliente sale de `PentahoSystem.get(ILineageClient.class)`. Si devuelve `null`, se usa el motor propio. La receta es la de la investigación:

1. Llamar por reflexión a `TransExtensionPointUtil.addLineageGraph(copia)`. La clase se carga desde el loader del cliente.
2. Obtener el grafo con `LineageGraphMap.getInstance().get(copia).get(60, SECONDS)`.
3. Calcular los caminos con `lc.getOperationPaths(copia, step, campos)`.
4. Hacer `map.remove(copia)` en un `finally`.

Hay cuatro condiciones:

- **Renombrar la copia antes** de llamar a `addLineageGraph`. Un `realClone` es `equals` al documento original y pisaría su entrada en el mapa [P].
- `KettleClientEnvironment.getClient()` no puede ser `null`. Spoon lo pone en `SPOON` [S/B]; las pruebas headless tienen que ponerlo.
- La primera vez tarda unos 490 ms y después unos 66 ms. Hay un lock global, así que hay que usar un timeout.
- Spoon trae `KETTLE_LINEAGE_EXECUTION_RUNTIME=off` por defecto: el grafo se arma a pedido, que es justo lo que hacemos.

**Motor propio de respaldo (`LinajePropio`) [P, mismos caminos que metaverse en 01–06].** Trabaja con `getStepFields`, `findPreviousSteps(s, true)` menos `getInfoStep`, y reglas por tipo de step:

| Tipo de step | Regla |
|---|---|
| Select values | `getFieldnameLineage()` |
| Calculator | `getCalculation()`, incluso las encadenadas |
| Group by y Memory group by | Agregado sobre el campo sujeto |
| Stream lookup | Valor que devuelve el step de búsqueda |
| Merge join | Campos por posición |
| Formula | Lee el XML y busca las referencias `[campo]` |
| Cualquier otro | Mismo nombre → pasa igual; sin entradas → es fuente; si no, «creado por <tipo>» |

`ValueMetaInterface.getOrigin()` es solo una pista. `TransDataLineage` es un stub: no sirve.

**Salida.** `datos` tiene la forma `{"section":"lineage","engine":"metaverse|kettle-metadata|mixed",…}` de la investigación (`mixed`: metaverse falló en algún destino y ese lo cubrió el motor propio), con `kind` ∈ {source, pass, rename, convert, calc, aggregate, lookup, join, created, unknown}. En `origins`, `role:"key"` marca las claves de lookup o join, que metaverse mezcla con los valores.

**Diagrama.** `flowchart LR`: un `subgraph` por step, con sus campos adentro y la operación escrita sobre la flecha. Hay un tope de unos 40 campos o 150 nodos; si se pasa, va solo la tabla con un aviso. El linaje se calcula hacia el step destino: por defecto, cada step final que escribe; el usuario puede elegir otro en el panel (`Pedido.linajeStep`).

**Riesgos.** Las clases internas de metaverse (`TransExtensionPointUtil`, `LineageGraphMap`) se llaman por reflexión y pueden cambiar después de 9.4; si fallan, se usa el motor propio. Dentro de Spoon, metaverse no se probó [NP]. El ejemplo 07 (Table input) necesita la base en los dos motores: esos steps quedan en `unresolved_steps` con su motivo.

#### 2.4.2 Métricas de ejecución (WP4)

| Fuente | Cómo se obtiene | Evidencia |
|---|---|---|
| `last_run` (por defecto) | `TransGraph.getTrans()` / `JobGraph.getJob()` se leen en la UI durante la captura y se procesan en el worker. Spoon conserva la última corrida hasta la próxima. | Lectura en Spoon [B]; `getTrans()` ya lo usa `ReportImagen` [S] |
| `run_copy` («Medir…») | `RunMotor.ejecutarTrans/Job` sobre una copia con `setGatheringMetrics(true)` y `setCapturingStepPerformanceSnapShots(true)` | [P] fuera de Spoon |

Si la corrida sigue en curso, la sección queda `parcial` con el motivo «ejecución en curso».

**Transformaciones** [P en 400.000 filas]:

- `new StepStatus(StepInterface)` por cada copia: líneas, errores y estado. El estado se toma con el `enum` `getStatus().name()`; `getStatusDescription()` viene traducido y no sirve.
- `getRuntime()` da los ms. La velocidad se calcula aparte: `getSpeed()` también viene localizado.
- Gantt: `MetricsUtil.getAllDurations(trans.getLogChannelId())`. Solo hay datos si se recolectaron métricas, y en Spoon eso pasa solo si el usuario marcó *Gather performance metrics* [B].
- Si no hay métricas, el Gantt «aproximado» dibuja cada step de 0 a su runtime y lo dice.
- Rendimiento: `getStepPerformanceSnapShots()`. Solo existe si la transformación tiene activado el monitoreo.

**Trampas observadas [P]:**

- `getStartDate()` es 1900-01-01. Para el inicio va `getCurrentDate()` y para el fin `getLogDate()`.
- `Result.getElapsedTimeMillis()` da 0.
- `StepListener.stepActive` no se dispara.

**Jobs [P en 10 y 11]:**

- El árbol de `getJobTracker()` trae cada entrada dos veces: con resultado `null` al empezar y con resultado al terminar.
- `getReason()` dice qué hop se siguió.
- Para las filas de las sub-transformaciones hace falta un `JobEntryListener` y `JobEntryTrans.getTrans()`. Solo existe en `run_copy`; para las corridas del usuario habría que engancharse a `JobStart` (fase 2, [NP]).

**Opcional, apagado por defecto: `MetricasHook`.** Es un `@ExtensionPoint` sobre `TransformationPrepareExecution` que activa la recolección de métricas en todas las corridas del usuario. Hay que poner el flag de snapshots y restaurarlo, porque si no se guarda en el `.ktr`. Que el `ExtensionPointPluginType` escanee nuestra carpeta y que funcione en Spoon: [NP].

**Datos.** `datos` tiene la forma `run_metrics` de la investigación (trans o job). Los bloques son:

- Indicadores: duración, filas leídas y escritas, errores, step más lento.
- `barras_h`: filas escritas y ms por step, ordenadas, como mucho 25.
- `gantt`: tramos `init` y `exec`.
- `linea`: filas por intervalo de los 5 steps con más volumen, si hay snapshots.
- Tabla de StepStatus.

En jobs, el Gantt va por entrada, con las sub-transformaciones anidadas.

#### 2.4.3 Perfil de datos (WP4)

Usa lo que ya está en el plugin: `RunMotor.paso`, `recortar`, `prepararVariables`, `padreDeLog` y `esperarFin` [P, llamados en el jar instalado].

Un `RowListener` sobre el step elegido mantiene un acumulador por campo, sin guardar filas:

- `count`, nulos, vacíos y largo mínimo y máximo;
- mínimo y máximo con metadata de *normal storage*, porque la conversión perezosa del CSV entrega bytes;
- media, desvío y suma;
- distintos, con un tope de 5.000 y una marca de «al menos»;
- los 5 valores más frecuentes.

Cifras de la investigación: 20.000 filas en unos 990 ms [P].

- **Por defecto: 5.000 filas, las primeras N** (lo dice el informe), 60 s de timeout.
- **Efectos secundarios.** Si hay steps que escriben aguas arriba del step elegido (`RunMotor.escribe`), el panel pide confirmación con la lista de esos steps. Por MCP solo corre si viene `profile_step`.
- **Privacidad.** Los valores frecuentes, el mínimo y el máximo de los textos son datos reales. Con `perfil.mostrarValores = false` se ocultan; viene activado, pero el panel avisa cuando se marca la sección.

#### 2.4.4 Modelo destino (WP5)

**Sin conexión** (por defecto) [P]. Para cada step `TableOutput` o `InsertUpdate` se lee:

- conexión, esquema, tabla y truncate;
- el mapeo de columnas: `getFieldDatabase`/`getFieldStream` si `specifyFields`; si no, los nombres de los campos de entrada;
- las claves y `getUpdate()` en Insert/Update;
- el tipo SQL con `DatabaseMeta.getFieldDefinition(valueMeta, null, null, false, false, false)`.

**No hay que usar `TransMeta.analyseImpact`.** En Table output lista los nombres del stream y no las columnas [P].

**Con «consultar la base»** [P en SQLite]:

1. `new Database(log, dbMeta)` y `connect()`.
2. `checkTableExists`, `getPrimaryKeyColumnNames` y `getTableFieldsMeta`.
3. JDBC `getImportedKeys` para las claves foráneas.
4. `disconnect()` en un `finally`.

Todo con un timeout de 15 s. **Antes de conectar, si la base es de archivo (SQLite, H2, Access…), se chequea que el archivo exista.** Si no existe, al conectarse se crea una base vacía [P].

**Salida.** `datos` tiene la forma `target_model` de la investigación. El diagrama es `erDiagram`: entidades con columnas, `PK`, `UK "lookup key"` y relaciones `||--o{` por FK de la base o por claves de lookup. Hoy cubre solo Table output e Insert/Update; Update, Dimension lookup y los bulk loaders quedan para más adelante.

### 2.5 Pedido y defaults

```java
public final class Pedido {
  public List<String> secciones;          // null = las marcadas por defecto que aplican
  public String idioma = "en";            // el panel arranca con el idioma de Spoon
  public String titulo;                   // si no, el nombre del documento
  public String linajeStep;               // null = steps finales
  public String perfilStep; public int perfilFilas = 5000;
  public String metricas = "last_run";    // last_run | run_copy | none
  public boolean destinoConsultarBase;
  public Narrativa narrativa;
  public long timeoutMs = 120000;         // total; cada sección tiene su tope (campos 60 s, linaje 60 s,
                                          // perfil 60 s, BD 15 s, validación 60 s, Chromium 60 s)
}
```

Si una sección se pasa de su tope o falla, queda en estado `parcial` o `error` con su motivo, y el resto del informe sale igual.

---

## 3. Renderizadores

### 3.1 HTML (WP7)

**Dos modos con el mismo código:**

- **`INTERACTIVO`** (es la exportación por defecto). Lleva el Mermaid 11.4.1 inline y los gráficos e imágenes inline. No hace ninguna petición de red. Pesa alrededor de 2,6 MB.
- **`ESTATICO`** (vista previa IE11 y «HTML liviano»). No lleva ningún `<script>`. Los diagramas van como SVG ya renderizado (`Map<id, svg>` que entrega `PrerenderMermaid`). Si un diagrama no tiene SVG, va un recuadro «Diagrama disponible al exportar o al abrir en el navegador» más la `alternativa` (tabla).

**Firma:**

```java
new RenderHtml( Modo modo, Map<String,String> svgPrerender /* puede ser null */, boolean paraImprimir )
```

**CSS compatible con IE11, porque es el mismo CSS en los dos modos.**

- No se usan `var()`, `grid` ni `dominant-baseline`. IE11 no los soporta.
- Los colores del `Tema` se escriben en el CSS desde Java.
- Las tarjetas son `inline-block` o tablas.
- Se usa `<meta http-equiv="X-UA-Compatible" content="IE=edge">`: con esa meta, el experimento dio `documentMode=11` [P].
- La tipografía es `"Segoe UI", Arial, sans-serif`. No hay fuentes web.

**Configuración de Mermaid.** Es la misma para la exportación, el PDF y el pre-render, así los tres dan el mismo dibujo:

```js
mermaid.initialize({ startOnLoad:false, securityLevel:'strict', theme:'base',
  htmlLabels:false, flowchart:{htmlLabels:false}, class:{htmlLabels:false},
  themeVariables:{ fontFamily:'Segoe UI, Arial, sans-serif', primaryColor:<primario 10%>,
                   primaryBorderColor:<primario>, lineColor:'#6B7785', primaryTextColor:'#1F2933' } });
mermaid.run({ querySelector:'pre.mermaid' });
```

`htmlLabels:false` es obligatorio. Con los rótulos HTML de siempre (`foreignObject`), el SVG pre-renderizado se ve **sin textos** en IE; con `false` se ven todos [P, probado en flowchart y class]. En `erDiagram`: [NP].

> **Cambio hecho en WP7/WP8.** Con `htmlLabels:false`, Mermaid 11.4.1 muestra literales los escapes de `escaparMermaid` (`&#60;b…`) en los nodos y rompe los títulos de *subgraph*. Por eso el HTML interactivo y el PDF usan `htmlLabels:true`, y solo el pre-render para IE (`RenderHtml.scriptMermaid(tema, true)`) sigue con `false` y repara los rótulos después (`PrerenderMermaid.JS_ARREGLAR_ROTULOS`). `erDiagram` con `false` se dibuja con todos sus rótulos en IE11 [P, WP8]. La marca de agua del PDF lleva `opacity:0.08` en el CSS del `div`: con la transparencia dentro del SVG, Edge la imprimía solo en la primera página [P, WP7].

**Estructura de la página:**

- Portada.
- Índice con anclas.
- Secciones, cada una con `<section id>`, título, resumen y bloques.
- Pie.
- Marca de agua: `position:fixed` abajo a la derecha.

**Impresión (`paraImprimir`):**

- `@page { size: A4; margin: 18mm 16mm 20mm }`, o Letter según el `Tema`.
- `@bottom-left { content: "Pentaho Live · IDC Studios" }` y `@bottom-right { content: "<Página> " counter(page) " <de> " counter(pages) }`. El experimento lo probó en Edge 155 [P]. Hay que usar el carácter «·» literal; escapado salió roto [P].
- `break-before: page` en cada sección y `break-inside: avoid` en las filas, figuras y tarjetas.
- `svg { max-width:100%; height:auto }`.
- Las páginas apaisadas para diagramas anchos (`@page ancho { size: A4 landscape }` con `page: ancho`) son [NP]. Son opcionales.

**Escapes.** Todo texto del usuario (nombres de steps, notas, SQL o descripciones) pasa por `Html.esc`. Las etiquetas de Mermaid pasan por `ReportMotor.escaparMermaid` [S].

### 3.2 PDF (WP8)

**Decisión: Chromium headless imprime el HTML interactivo** [P]. Comando probado:

```
msedge.exe --headless=new --disable-gpu --no-first-run --no-default-browser-check --disable-extensions
  --user-data-dir=%TEMP%\plive-<n> --no-pdf-header-footer --virtual-time-budget=15000
  --print-to-pdf=<salida.pdf> file:///…/informe-imprimir.html
```

Lo que salió del experimento:

- Tarda de 2 a 5 s en frío y devuelve código 0.
- Respeta `@page`: A4 sale a 595x842 pt.
- Mermaid queda vectorial y con texto seleccionable.
- Embebe Segoe UI.
- Funciona sin red.
- Chrome da el mismo resultado.

**Validación.** Código 0, que el archivo exista, que empiece con `%PDF` y que mida más de 1 KB.

**Búsqueda del navegador** (`Chromium.ubicar`), en este orden. El resultado se guarda en caché.

1. `Tema.navegador`, si el usuario cargó una ruta.
2. `reg query "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\msedge.exe" /ve`, y después HKCU.
3. `%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe` y `%ProgramFiles%\…`.
4. Las mismas tres búsquedas para `chrome.exe`.
5. Si no aparece ninguno, no hay navegador.

**Reglas del proceso:**

- `--user-data-dir` **corto y único** por corrida (`%TEMP%\plive-<n>`). Con rutas largas aparecen errores de MAX_PATH [P]. Se borra al terminar.
- La salida va a un archivo.
- `waitFor(60, SECONDS)`. Si se vence, `destroyForcibly()`; si el runtime es Java 9 o más nuevo, también `taskkill /PID <pid> /T /F` usando `Process.pid()` por reflexión, porque en bytecode 8 no existe. Si quedan procesos hijos colgados después de un timeout: [NP].

**Sin navegador.** Se escribe `<nombre>.imprimir.html`, una copia con `window.onload = () => print()`, y se abre con `org.eclipse.swt.program.Program.launch(ruta)`; el usuario elige «Guardar como PDF» [NP]. Por MCP, la tool devuelve el aviso y no falla.

**Descartados.**

- iText 2.1.7: es MPL/LGPL, habría que empaquetarlo y el texto con estilos salía mal.
- `PrintToPdf` de WebView2: SWT no expone `ICoreWebView2_7` [B].
- Imprimir las diapositivas a PDF: aparecía la negrita pegada y se perdían símbolos [P].

### 3.3 Markdown (WP7)

Pensado para Obsidian y GitHub. Archivos que genera:

- `<nombre>.md`.
- `<nombre>_archivos/lienzo.png`.
- `<nombre>_archivos/<grafico-id>.svg`.

Contenido del `.md`:

- *Front matter* YAML: `title`, `date`, `tags: [pentaho, etl, informe]`, `source: <ruta del .ktr>`, `generator: Pentaho Live <v>`.
- Diagramas: un bloque ```` ```mermaid ```` con el mismo texto que en el HTML. El texto Mermaid tiene que usar **sintaxis compatible con Mermaid 9+**: formas clásicas, `flowchart`, `erDiagram` y nada de `@{ shape: … }`, porque la versión de Mermaid que trae Obsidian no se verificó [NP].
- Imágenes y gráficos con la sintaxis estándar `![alt](<nombre>_archivos/x.svg)`, que funciona en los dos.
- Tablas GFM, con `|` escapado.
- Avisos como `> [!WARNING]`, `> [!NOTE]`.
- Una línea final en cursiva: «Generado con Pentaho Live · IDC Studios».

En Markdown no hay marca de agua.

**Nombres de archivo estables.** Al regenerar se sobrescribe, para que no se rompan los enlaces de Obsidian. Con la opción «agregar fecha» va `-AAAAMMDD-HHMM`.

### 3.4 PowerPoint (WP9)

**Librería.** POI 4.1.1 (`poi`, `poi-ooxml`, `poi-ooxml-schemas`, Apache-2.0) va en `plugins\pentaho-mcp\lib\`. Las otras dependencias ya están en `lib\` de PDI y **no se empaquetan**: xmlbeans 3.1.0, commons-compress, collections4, math3, codec, curvesapi, JAXB y Batik 1.9.1. Una segunda copia en un loader child-first arriesga `LinkageError`. Antes de exportar se chequea que `XmlBeans.getVersion()` empiece con `3.1`; si no, PPTX queda deshabilitado con un mensaje.

**Formato:**

- 16:9: `setPageSize(new Dimension(960, 540))`, en puntos.
- Plantillas de diseño `TITLE`, `TITLE_AND_CONTENT` y `TITLE_ONLY`.
- Títulos en *placeholders* reales, así aparecen en la vista de esquema. Hay que hacer `setAnchor` porque la plantilla por defecto es 4:3 [P].

**Diapositivas:**

1. Portada: banda del color primario, título, documento, autor, institución, fecha y logo.
2. Agenda.
3. Resumen: indicadores y viñetas.
4. Una o varias por sección:

| Sección | Qué lleva |
|---|---|
| Flujo | El lienzo ajustado a la diapositiva |
| Linaje | La imagen, o la tabla paginada |
| Diccionario | Tabla paginada, 10 filas por diapositiva |
| Perfil | Tabla y gráfico |
| Métricas | Barras y Gantt |
| Modelo destino | Diagrama ER, o una tabla por entidad |
| Validación | Cuentas y principales problemas |
| Conexiones | Tabla |
| Conclusiones | Texto |

**Notas del orador.** Llevan el `resumen` de la sección y la narrativa, si la hay [P: notas en el *placeholder* `BODY`]. Hay que borrar los *placeholders* de fecha y número que trae la plantilla de notas [NP].

**Gráficos.**

- Por defecto, `XSLFPictureShape.addSvgImage(…)` con el SVG de `GraficosSvg`. POI genera el PNG de respaldo con Batik [P].
- `GraficosSvg` siempre incluye un `<rect>` de fondo que cubre todo el `viewBox`. Sin ese rect, POI calcula mal el tamaño [P].
- Opcional: gráfico **nativo y editable** (XDDF `ChartTypes.BAR`) para las filas por step [P]. El ancla de `addChart` va en **EMU**: hay que multiplicar por `Units.EMU_PER_POINT` o el gráfico no se ve [P].

**Diagramas Mermaid** (linaje y ER). Van como PNG de `PrerenderMermaid.png` si hay navegador; si no, como `alternativa` (tablas). El flujo y el job usan siempre el PNG del lienzo, sin necesidad de navegador.

**Context classloader.** Se pone el del plugin durante toda la exportación. Hoy solo hace falta para `XSLFSlide.draw`, que no usamos, pero es barato y evita el `JAXBException` [P].

**Validación del experimento.** El archivo se volvió a leer con POI y con python-pptx, y se convirtió con LibreOffice [P]. **PowerPoint real no se probó [NP].**

### 3.5 De Mermaid a imagen (`PrerenderMermaid`, WP8)

| Paso | Cómo | Evidencia |
|---|---|---|
| SVG estático | Una página temporal con todos los diagramas y la configuración de §3.1. Se corre `--dump-dom --virtual-time-budget=15000`, se agrega `<!DOCTYPE html>` (el volcado lo pierde) y se extrae cada `<svg>` por el id de su contenedor, con un parser de profundidad: los SVG pueden traer otros adentro. | [P] (volcado de unos 50 KB que IE dibuja) |
| PNG, intento 1 | Los SVG estáticos se ponen en una página **sin JS**, apilados en posiciones absolutas conocidas (el tamaño sale de cada `viewBox`). Una sola corrida de `--screenshot --window-size=W,H --force-device-scale-factor=2 --hide-scrollbars`, y después se recorta cada caja con `ImageIO`. | [NP]. Es lo primero que tiene que probar WP8; falta ver si hay un tope de alto de ventana. |
| PNG, intento 2 | `SvgAPng` (Batik `PNGTranscoder`) sobre el SVG estático. | Batik con **nuestro** SVG [P]; con el SVG de Mermaid (estilos CSS embebidos) [NP] |
| Respaldo final | Se usa la `Tabla alternativa` del bloque. | – |

**Caché.** Un `ConcurrentHashMap` con clave `sha1(mermaid + colores)`. Se descarta al salir de Spoon.

**Costo.** Una o dos corridas de Edge por informe, de 2 a 5 s cada una. En la vista previa no frena nada, porque llega en la segunda pasada.

### 3.6 Gráficos SVG propios (`GraficosSvg`, WP6)

**Firma:**

```java
static String svg( Grafico g, Tema t, int anchoPx )
```

Devuelve SVG 1.1 autocontenido y compatible con IE11:

- Atributos `fill`, `stroke` y `font-family` inline. Nada de CSS ni de variables.
- `text-anchor` y desplazamientos `dy` en lugar de `dominant-baseline`.
- `<rect>` de fondo blanco.
- El alto se calcula según las categorías: 22 px por fila, más ejes.

**Tipos:**

| Tipo | Cómo se dibuja |
|---|---|
| `barras_h` | Categorías ordenadas, valor escrito al final de cada barra, como mucho 25 barras y «+N más». Admite 1 o 2 series (por ejemplo filas y ms). |
| `gantt` | Eje de tiempo en ms o s. Los tramos `init` van en gris y los `exec` en el color primario. Que se solapen es normal: los steps corren en paralelo, y la leyenda lo explica. |
| `linea` | Hasta 5 series, con su leyenda. |

**Colores.** Los da `Paleta`: primario, acento y secundario del `Tema`, más grises neutros. Si el usuario no cambia nada, el default es neutro (§4.2).

**`SvgAPng.png(svg, anchoPx)`.** Usa Batik `PNGTranscoder` con `KEY_WIDTH` [P]. Lo usan PPTX y el `.png` opcional del Markdown.

### 3.7 Dónde termina cada sección, por formato

| Sección | HTML | PDF | MD | PPTX | Vista previa IE11 |
|---|---|---|---|---|---|
| flow / job_flow | Lienzo PNG, Mermaid y tabla | igual | PNG, mermaid y tabla | Lienzo PNG y tabla | PNG; SVG Mermaid cuando llega |
| lineage | Mermaid y tabla | igual | mermaid y tabla | PNG si hay navegador; si no, tabla | SVG o recuadro, y tabla |
| target_model | erDiagram y tabla | igual | mermaid y tabla | PNG o tablas | SVG o tablas |
| metrics / profile | SVG propio y tablas | vectorial | `.svg` y tablas | SVG con PNG de respaldo | SVG |
| el resto | texto y tablas | igual | igual | igual | igual |

---

## 4. Tema y marca

### 4.1 Archivo de configuración

**Ruta.** `Const.getKettleDirectory()` + `\pentaho-live\informes.json`. Esa función resuelve `KETTLE_HOME` (primero la variable de entorno, después la system property, después `user.home`) y le suma `\.kettle` [B, javap -c]. Así respeta un `KETTLE_HOME` distinto, como el de las pruebas con Claude.

**Logo.** Se copia a la misma carpeta como `logo.png` o `logo.jpg`. **Solo se aceptan PNG o JPG** de hasta 2 MB. SVG no, para no pasar archivos externos por Batik, que tiene CVE conocidas en la 1.9 [NP: de memoria].

**Escritura atómica.** Se escribe en `.tmp` y después se hace `move`. Lo maneja `Ajustes` (WP6). Si el archivo no existe o está roto, se usan los defaults y el panel avisa una vez.

```json
{
  "version": 1,
  "identidad": { "autor": "Nombre Apellido", "institucion": "Universidad o empresa", "curso": "Big Data I", "contacto": "" },
  "logo": "logo.png",
  "colores": { "primario": "#243B53", "acento": "#3F74D8", "secundario": "#1C9A80" },
  "pagina": "A4",
  "idioma": "es",
  "marca_de_agua": true,
  "rotular_ia": true,
  "salida": { "carpeta": "", "formatos": ["html", "pdf"], "html": "interactivo", "agregar_fecha": false },
  "secciones": ["cover","summary","flow","job_flow","lineage","dictionary","metrics","target_model","validation","connections"],
  "perfil": { "filas": 5000, "mostrar_valores": true },
  "navegador": ""
}
```

### 4.2 Plantilla neutra

| Elemento | Valor |
|---|---|
| Fondo | Blanco |
| Texto | `#1F2933` |
| Texto tenue | `#6B7785` |
| Bordes | `#D9DEE5` |
| Primario (títulos, banda de portada, cabecera de tablas) | `#243B53` (pizarra) |
| Acento (series) | `#3F74D8`, el azul «a» del símbolo |
| Secundario | `#1C9A80`, el verde «o» |
| Tipografía | Segoe UI / Arial; código en Consolas |
| Página | A4 por defecto, Letter opcional |

El usuario cambia primario, acento y secundario. `Paleta` deriva los tonos claros: 10 % para fondos y 8 % para la cabecera de las tablas.

### 4.3 Pie y marca de agua (IDC Studios)

**Símbolo.** Es la rejilla de 4 columnas × 3 filas, con celdas de 7×5, separación 3 y radio 1,5. El `viewBox` es `0 0 37 21`. Las celdas encendidas, con clave **(fila, columna)**, son (0,1)=a, (1,2)=o, (2,0)=a y (2,3)=a. Así lo confirman `Manual de marca\herramientas\generar_marca.py` (`ENCENDIDAS`) y `logo\simbolo-claro.svg`.

**Colores.** Se toman de `tokens.json` › `simbolo.claro`: apagada `#d6deea`, a `#3f74d8`, o `#1c9a80`.

**Generación.** `SimboloIdc` arma el SVG desde esas constantes; la fuente de verdad es `generar_marca.py`. Una prueba de desarrollo lo compara rect por rect con `simbolo-claro.svg` cuando esa carpeta existe.

**Pie (siempre).** Una línea de 7,5 pt en el color tenue: el símbolo a color de unos 8 px de alto y el texto «Pentaho Live · IDC Studios». En el PDF va en la caja `@bottom-left`, solo texto (poner la imagen ahí es [NP]), y el número de página va en `@bottom-right`. En PPTX va abajo a la izquierda, con el número de diapositiva a la derecha, en todas menos la portada. En HTML va al final del documento. En Markdown es la línea final.

**Marca de agua (por defecto sí, se puede apagar).**

- Es el símbolo `claro` con **opacidad 0,08**, de unos 40 mm de ancho, en la esquina inferior derecha, detrás del contenido.
- En HTML y PDF es un `div` con `position:fixed`; Chromium repite los elementos fijos en cada página impresa [NP en nuestro HTML].
- En la vista previa IE11 va igual, con `opacity` (IE9+).
- En PPTX es un PNG con alfa (`SimboloIdc.png(anchoPx, "claro", 0.08f)`, `BufferedImage` ARGB) en cada diapositiva.
- Con la opacidad a 0,08, las celdas apagadas casi desaparecen y queda la firma de las cuatro encendidas.

---

## 5. UI de la perspectiva (WP1)

### 5.1 Disposición

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│ Documento [03_ventas_por_producto (transformación) ▾] [⟳] [Abrir archivo…]  Idioma [es ▾]│
├───────────────────────────────┬──────────────────────────────────────────────────────────┤
│ Secciones                     │                                                          │
│ [x] Portada   [x] Resumen     │                                                          │
│ [x] Diagrama de flujo         │                 Browser (vista previa)                   │
│ [x] Linaje de campos  [destino ▾] │                                                      │
│ [x] Diccionario de datos      │                                                          │
│ [ ] Perfil de datos [step ▾] [5000] │                                                    │
│ [x] Métricas (última: 14:32) [Medir…] │                                                  │
│ [x] Modelo destino  [ ] consultar la base │                                              │
│ [x] Validación  [x] Conexiones y parámetros │                                            │
│ [ ] Conclusiones  [ texto… ]  │                                                          │
│ ── Identidad ──               │                                                          │
│ Autor [....] Institución [...]│                                                          │
│ Logo [....][…]  Colores ■ ■ ■ │                                                          │
│ [Guardar como predeterminado] │                                                          │
│ ── Exportar ──                │                                                          │
│ [x]HTML [x]PDF [ ]Markdown [ ]PowerPoint                                                 │
│ Carpeta [.............][…]    │                                                          │
│ [Vista previa F5] [Exportar] [Abrir en el navegador] [Abrir carpeta]                     │
├───────────────────────────────┴──────────────────────────────────────────────────────────┤
│ ▓▓▓▓░░ Calculando linaje… (4/9)  [Cancelar]                ▦ Pentaho Live · IDC Studios │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

**Estructura SWT:**

- La raíz es un `GridLayout(1)` con tres partes: la barra superior, un `SashForm(SWT.HORIZONTAL)` con pesos 28/72 y la barra de estado.
- La parte izquierda es un `ScrolledComposite`, porque con resoluciones bajas no entra todo.
- La parte derecha es un `Composite` con `FillLayout` que contiene el `Browser`.
- Los textos de los botones y labels escapan `&` como `&&`.

### 5.2 Controles y comportamiento

**Documento.**

- El combo lista las pestañas de trans y job abiertas, con `Docs.abiertos()` y `Docs.id()`. Se refresca al activar la perspectiva y con el botón ⟳.
- «Abrir archivo…» (`FileDialog` `*.ktr;*.kjb`) carga el documento **sin abrir pestaña** con `new TransMeta(ruta)` / `new JobMeta(ruta, repo)`. Corre en el worker y el lienzo se pinta en la UI con `ReportImagen.pintar(display, meta, 2f, null, null)`. No llama a `spoon.openFile`, que tiene modales.

**Secciones.**

- Cada check se habilita según `disponibles(...)`. Si una sección no aplica, el tooltip dice el motivo, por ejemplo «solo para transformaciones» o «no hay ejecución previa».
- **Perfil** necesita un step; el combo se llena con los nombres de la copia.
- **Medir…** abre una confirmación con los steps que escriben (`RunMotor.escribe`) y después corre `run_copy`.
- **Consultar la base** avisa que se va a conectar.

**Identidad.**

- Edita el `Tema` en memoria.
- «Guardar como predeterminado» escribe `informes.json`.
- Los colores se eligen con `ColorDialog` y cada botón muestra una muestra del color.

**Vista previa** (botón o F5): sigue la secuencia de §1.4 con **todas** las secciones marcadas.

**Exportar.**

- Exporta los formatos marcados a la carpeta: la de la configuración, la última usada o `<carpeta del .ktr>\informes`.
- Con «Exportar» sin carpeta configurada se abre un `DirectoryDialog`.
- Al terminar, el estado dice «4 archivos en …» y se habilita «Abrir carpeta» (`Program.launch(carpeta)`).
- Si falta Chromium, el PDF sigue el camino de §3.2 y PPTX usa tablas en lugar de diagramas. El estado lo explica en una línea.

**Abrir en el navegador.** Exporta el HTML interactivo a temporales y hace `Program.launch(archivo)`. Siempre se ve completo.

**Estado y progreso.**

- Una `ProgressBar` (`SWT.INDETERMINATE`) con el texto del paso y el botón Cancelar.
- Los mensajes de `Progreso` se agrupan: como mucho 1 `asyncExec` cada 100 ms.
- Mientras corre una tarea, los botones Vista previa y Exportar están deshabilitados.

**Errores.** Van al estado en rojo y al log (`LogChannel("PentahoLive-Informes")`). Nunca se abre un `ErrorDialog` automático.

### 5.3 Vista previa: «se arma casi sola»

**Disparo automático.** Al **activar la perspectiva** o al **cambiar de documento** se arma sola una **vista previa liviana**: solo las secciones que no consultan la base ni ejecutan nada (cover, summary, flow o job_flow, connections). Validation queda afuera porque `checkSteps` puede conectarse a la base. Tiene un *debounce* de 400 ms y cancela la anterior.

**Lo caro, solo a pedido.** Linaje, diccionario, perfil, medición y consulta a la base corren con el botón Vista previa o al exportar.

**Motor.** El `Browser` se crea con `new Browser(parent, 1 << 18)` y se mira `getBrowserType()`:

- `"ie"`, con el SWT 3.115 de PDI [P]: se carga `vista.html` en modo ESTATICO y, cuando llega el pre-render, se recarga.
- `"edge"`: solo pasa si el usuario cambió el `swt.jar` (fase 2, WP11). Se carga directamente el HTML interactivo con `setUrl`. **Nunca `setText`**: WebView2 corta alrededor de 2 MB [P].
- Antes del primer `Browser`, si hace falta, se fija `System.setProperty("org.eclipse.swt.browser.EdgeDataDir", …)` [B].

**Banda en la vista previa liviana.** Una franja chica arriba avisa: «Vista previa: los diagramas completos se ven al exportar o en el navegador», solo si faltan SVG.

**Temporales.** Van en `%TEMP%\plive\vista\<docId>\`. Al arrancar se borran las carpetas de más de 1 día.

### 5.4 Idiomas

- **Idioma de la UI.** Sale de `LanguageChoice.getInstance().getDefaultLocale()` [B]: `es` → español, `pt` → portugués, cualquier otro → inglés.
- **Idioma del informe.** Se elige aparte en la barra superior; arranca igual que la UI.
- **Textos.** Van en `src/main/resources/io/github/ivi2511/pentahomcp/informes/textos/<área>_<en|es|pt>.properties` y los lee `Rotulos`. **Se leen en UTF-8** con `new PropertyResourceBundle(new InputStreamReader(in, UTF_8))`, porque el `ResourceBundle` de Java 8 lee ISO-8859-1. Cada paquete de trabajo es dueño de su área (`secciones` de WP0, `ui`, `estructura`, `linaje`, `ejecucion`, `destino`, `tema` y `graficos` de WP6, `render`, `navegador` de WP8 y `pptx` de WP9), así nadie toca el archivo de otro.

Muestra de `ui_*`:

| Clave | en | es | pt |
|---|---|---|---|
| perspective | Reports | Informes | Relatórios |
| preview | Preview | Vista previa | Pré-visualizar |
| export | Export | Exportar | Exportar |
| open_browser | Open in browser | Abrir en el navegador | Abrir no navegador |
| measure | Measure… | Medir… | Medir… |
| save_default | Save as default | Guardar como predeterminado | Salvar como padrão |
| no_browser | No Edge/Chrome found: PDF opens the print dialog | No hay Edge/Chrome: el PDF abre el diálogo de impresión | Sem Edge/Chrome: o PDF abre a caixa de impressão |

---

## 6. Riesgos y mitigaciones

| # | Riesgo | Mitigación | Evidencia |
|---|---|---|---|
| 1 | **Spoon no arranca** si el constructor o `getPerspective()` tiran una excepción, o arranca **sin lienzo** si `getId`, `getDisplayName` o `applyToContainer` tiran una. | Métodos triviales con `catch Throwable`. Ningún widget en el constructor. El interruptor `PENTAHO_LIVE_REPORTS=off`. Una prueba headless del registro con el jar real. | [B]; registro [P] |
| 2 | `classLoaderGroup` distinto, o el jar en dos carpetas: el estado queda partido. | Las dos anotaciones sin grupo. El chequeo de loaders en `onStart`. El instalador deja una sola copia. | [P] |
| 3 | `getActiveMeta() = null` rompe `status` y `new_*` del MCP con Informes al frente. | Los arreglos de §1.5 (WP10). | [B] |
| 4 | Estado de los menús (Archivo, Ver) al salir de la perspectiva principal: se quita `main_perspective_overlay.xul`. | Probarlo en Spoon. Si molesta, un overlay propio mínimo que deje visibles los mismos ítems. | [NP] |
| 5 | El navegador embebido es **IE11**: no corre Mermaid ni ES2015, y los errores de script son silenciosos. | La vista previa es HTML **sin JS** con CSS apto para IE11 y diagramas pre-renderizados. Botón «Abrir en el navegador». Edge embebido queda como opción de fase 2. | [P] |
| 6 | Edge embebido exige reemplazar `libswt\win64\swt.jar`: hay que elegir entre 3.116.100 (Java 8) y 3.121 (Java 11+), y puede traer regresiones. | Fuera de la v1. Si se hace, es *opt-in*, reversible, con copia de respaldo, chequeo de la versión de Java y la prueba de enlaces estática ya hecha. | Enlaces [P]; en Spoon [NP] |
| 7 | No hay Edge ni Chrome (máquinas bloqueadas). | PDF por el diálogo de impresión. PPTX con tablas. Vista previa con recuadros. Ruta configurable. | – |
| 8 | Chromium se cuelga o deja procesos. | Timeout de 60 s, `destroyForcibly` más `taskkill /T` con el pid si hay Java 9+, perfil temporal único y corto, semáforo de 2. | Zombis [NP] |
| 9 | **PDF**: hay que esperar a que Mermaid termine (es asíncrono). | `--virtual-time-budget=15000` [P]; funcionó con y sin el flag. Validar `%PDF`. | [P] |
| 10 | Tamaños: HTML interactivo de unos 2,6 MB más imágenes; release zip que pasa de 371 KB a unos 11 MB por POI (12,6 MB sin comprimir); jar con unos 0,75 MB más por Mermaid. | Aceptado: el HTML es «un archivo para presentar», y existe la variante liviana. Si el zip molesta, alternativa (b) de la investigación: cargar el POI de `pdi-core-plugins\lib` con un `URLClassLoader` propio. Funciona mecánicamente [P], pero el cableado completo no se probó. | [P] |
| 11 | **Bytecode Java 8**: APIs de 9+ (`Process.pid`, `Redirect.DISCARD`, `InputStream.readAllBytes`, `List.of`). | `javac --release 8` ya las rechaza en compilación. `test.ps1` además verifica la *major version* 52 de todas las clases. Lo que haga falta de 9+ va por reflexión. | – |
| 12 | POI sin el context loader da `JAXBException` al dibujar. | Fijar el context classloader en el pool de informes. | [P] |
| 13 | Una versión futura de PDI cambia xmlbeans. | Chequear `XmlBeans.getVersion()` y deshabilitar PPTX con un mensaje. | – |
| 14 | AWT (Batik, ImageIO, POI) dentro del proceso SWT. | En Windows es esperable que ande. Solo se usa `BufferedImage` e `ImageIO`, nunca ventanas AWT. macOS y Linux quedan fuera del alcance de 0.5.0. | [NP] en Spoon |
| 15 | Perfil y «Medir» ejecutan la transformación y pueden escribir. | Opt-in, confirmación con la lista de steps que escriben, `recortar` deja como Dummy lo que no hace falta, y por MCP solo con un argumento explícito. | [S] (`preview_step`) |
| 16 | Consultar la base crea un SQLite vacío o se cuelga. | Chequear que el archivo exista, timeout de 15 s, solo metadatos y `disconnect` en un `finally`. | [P] |
| 17 | Las clases internas de metaverse cambian, o el mapa retiene la `TransMeta`. | Reflexión con fallback al motor propio, renombrar la copia y `map.remove` en un `finally`. | [P] |
| 18 | **Licencias JS**: Mermaid es MIT, pero el bundle trae DOMPurify (Apache-2.0 o MPL-2.0: elegimos Apache-2.0), lodash, cytoscape, katex y dagre (MIT) y **d3 (ISC)**. El aviso de d3 no está en el bundle. Una búsqueda de cadenas encontró «elk» 22 veces y no aclaró si viene elkjs (EPL-2.0). | **Decisión para Iván: ISC es permisiva y equivalente a MIT, pero no es literalmente «MIT/Apache».** Sin d3 no hay Mermaid. Recomendación: aceptar ISC y BSD. WP7 audita el paquete npm `mermaid@11.4.1` (dependencias y `LICENSE` de cada una) y escribe `THIRD-PARTY-NOTICES.md` con los avisos completos. Si aparece EPL o algo copyleft, se busca otra build o versión. Chart.js queda fuera: no se usa. | Parcial [P]; detalle [NP] |
| 19 | Licencias Java: POI es Apache-2.0 y se empaqueta (con su LICENSE y NOTICE, que vienen en los jars). JFreeChart (LGPL) e iText (MPL/LGPL) **no se usan**. El SWT de fase 2 sería EPL-2.0. | El aviso va en `THIRD-PARTY-NOTICES.md`. | [P] |
| 20 | Privacidad: el informe puede llevar hosts, rutas y valores reales del perfil. | Nunca contraseñas [S]. `perfil.mostrarValores`. Rutas relativas a la carpeta del documento cuando se puede. | – |
| 21 | Inyección en HTML o Mermaid con nombres de steps raros. | `Html.esc` en todo; Mermaid con `securityLevel:'strict'` y `escaparMermaid`; narrativa sin HTML. | [S] (escape de Mermaid) |
| 22 | Documentos grandes: `getStepFields` lento, grafo de linaje enorme o lienzo gigante. | Topes por sección, estado «parcial» con motivo, tope de nodos del linaje y la reducción de escala de `ReportImagen` (8000 px) [S]. | [S] |
| 23 | HiDPI: el lienzo se ve borroso en el PDF. | Pintarlo a escala 2 y mostrarlo al 50 %. | [NP] a 150 % |

---

## 7. Plan de implementación (paquetes paralelos)

### 7.1 Convenciones para todos los paquetes

- **Java 8** (`--release 8`), con el estilo del repo: identificadores y comentarios en castellano y el formato Pentaho `foo( a, b )`. Sin dependencias nuevas, salvo POI (WP0) y Mermaid (WP7).
- **SWT solo** en `informes.ui`, `CapturaUi` y la perspectiva. Nunca diálogos de Spoon. Todo texto visible sale de `Rotulos`.
- **Cada archivo tiene un único dueño.** Los *stubs* que crea WP0 pasan a ser del paquete que figura en su javadoc (`@dueño WPn`). Si hace falta cambiar un archivo del contrato (WP0), lo pide el coordinador; no se toca directo.
- **Pruebas.** Cada paquete agrega sus clases `Prueba*.java` en `spoon-plugin/src/test/java/…`. El *runner* las descubre solo, así no hay ninguna lista compartida.
- **Nada escribe** en `data-integration\` ni en `~\.kettle` real. Las pruebas corren con `KETTLE_HOME` y el cwd en `spoon-plugin\build\prueba\`.

### 7.2 Paquetes

**WP0: contrato, esqueleto y build.** Es la **compuerta**: va primero, solo, y tiene que ser chico.

Archivos nuevos:

- Modelo: `informes/modelo/{Informe,Seccion,Bloque,Parrafo,Lista,Tabla,Indicadores,Indicador,Diagrama,Imagen,Grafico,Serie,Tramo,Aviso,Narrativa}.java`.
- Contrato: `informes/{Recolector,Renderizador,Contexto,Captura,Pedido,Formato,Progreso,Resultado,Rotulos,SeccionId}.java` y `informes/tema/Tema.java`.
- *Stubs* que compilan y devuelven una sección «omitida» o lanzan `UnsupportedOperationException("WPn")`:

| Stub | Dueño |
|---|---|
| `InformeServicio`, `CapturaUi`, `ContextoKettle` | WP2 |
| `SecLinaje` | WP3 |
| `SecMetricas`, `SecPerfil` | WP4 |
| `SecDestino` | WP5 |
| `informes/tema/{Ajustes,SimboloIdc,Paleta}`, `informes/graficos/{GraficosSvg,SvgAPng}` | WP6 |
| `informes/render/{RenderHtml,RenderMarkdown}` | WP7 |
| `informes/navegador/{Chromium,RenderPdf,PrerenderMermaid}` | WP8 |
| `informes/render/RenderPptx` | WP9 |
| `informes/ui/PanelInformes` (`mostrar`) | WP1 |

- Pruebas: `src/test/java/io/github/ivi2511/pentahomcp/{Pruebas,Verificador,EntornoKettle,ModeloMuestra}.java`. `ModeloMuestra` arma un informe **completo** con todos los tipos de bloque, PNG y SVG de ejemplo; con eso los renderizadores trabajan sin recolectores.
- Datos de prueba: `src/test/resources/ej/` (copia de `scratchpad\informes\datos\ej` 01–11) y `src/test/resources/db/dw.sqlite`.
- Script: `spoon-plugin/test.ps1`. Compila main y test contra `lib\*`, `libswt\win64\*` y `classes\`; corre `Pruebas` con `-DKETTLE_HOME=<temp>`, `-DKETTLE_PLUGIN_BASE_FOLDERS=<PDI>\plugins` y los mismos `--add-opens` que `Spoon.bat`; y verifica la *major version* 52.

Cambios en `spoon-plugin/build.ps1`:

- Copiar los 3 jars de POI 4.1.1 de `$PdiHome\plugins\pdi-core-plugins\lib\` a `dist\pentaho-mcp\lib\` y sumarlos al classpath de compilación.
- Copiar `spoon-plugin\THIRD-PARTY-NOTICES.md` a `dist\` si existe.

Listo cuando: `build.ps1` compila con los *stubs*, `test.ps1` corre `PruebaModelo` (ida y vuelta Jackson de `ModeloMuestra`) en verde, y el jar trae POI en `lib\`.

| WP | Qué | Archivos que son suyos (nuevos, salvo que diga *mod*) | Consume | Entrega | Listo cuando |
|---|---|---|---|---|---|
| **WP1** | Perspectiva y panel SWT | `ReportsSpoonPlugin.java`, `ReportsPerspective.java`, `Interruptor.java`, `informes/ui/{PanelInformes,BarraDocumento,GrupoSecciones,GrupoIdentidad,GrupoExportar,VistaPrevia,EstadoUi}.java`, `textos/ui_{en,es,pt}.properties`, `PruebaRegistroPerspectiva.java` (registro y loader con el jar real, como el experimento 1), `PruebaPanelSwt.java` (Shell con un `InformeServicio` falso y captura de pantalla) | `InformeServicio` (WP2), `Ajustes`/`Tema` (WP6) | `PanelInformes.mostrar(String docId, Pedido p)` (UI) | La perspectiva se registra, comparte loader con el lifecycle y el panel se dibuja en el *harness* |
| **WP2** | Motor y secciones estructurales | `InformeServicio.java`, `CapturaUi.java`, `ContextoKettle.java`, `Sec{Portada,Resumen,Flujo,Diccionario,Validacion,Conexiones,Narrativa}.java`, `textos/estructura_*.properties`, `PruebaEstructura.java`, `PruebaServicio.java`; **mod** `ReportMotor.java` (solo visibilidad: la clase `T` y su constructor pasan a *package-private* para reusar `mermaidTrans`, `mermaidJob`, `queHaceStep`, `queHaceEntrada`; sin cambio de comportamiento) | Contrato | `armar`, `exportar`, `vistaPrevia`, `disponibles`, `sinBinarios`, `capturar*`; registro de todos los recolectores y renderizadores | Informe real de 01–11 en JSON; `generate_report` sigue dando lo mismo |
| **WP3** | Linaje | `SecLinaje.java`, `LinajeMetaverse.java`, `LinajePropio.java`, `textos/linaje_*.properties`, `PruebaLinaje.java` | `Contexto` | `Seccion lineage` | Los dos motores coinciden en 01–06 (orígenes y operaciones); 07 queda `parcial` con motivo; no quedan entradas en `LineageGraphMap` |
| **WP4** | Métricas y perfil | `SecMetricas.java`, `SecPerfil.java`, `CorridaMedida.java`, `MetricasHook.java` (opcional, apagado), `textos/ejecucion_*.properties`, `PruebaMetricas.java`, `PruebaPerfil.java` | `Contexto`, `RunMotor` | `Seccion metrics`, `Seccion profile` | Métricas de 03 (`run_copy`) y de los jobs 10 y 11; perfil de 04 y de la copia grande; trampas de fechas y localización resueltas |
| **WP5** | Modelo destino | `SecDestino.java`, `textos/destino_*.properties`, `PruebaDestino.java` | `Contexto` | `Seccion target_model` | erDiagram de 07 sin conexión y con conexión (PK y FK en una copia temporal de `dw.sqlite`); no se crea archivo si la ruta no existe |
| **WP6** | Tema, símbolo y gráficos | `informes/tema/{Ajustes,SimboloIdc,Paleta}.java`, `informes/graficos/{GraficosSvg,SvgAPng}.java`, `PruebaGraficos.java` (deja SVG y PNG en `build/prueba/salida`), `PruebaAjustes.java`, `PruebaSimbolo.java` | Contrato | `Ajustes.cargar()/guardar()`, `SimboloIdc.svg/png`, `GraficosSvg.svg`, `SvgAPng.png` | Gráficos legibles en IE11 (*harness* de WP8 o WP1) y en Batik; símbolo igual a `simbolo-claro.svg` |
| **WP7** | HTML y Markdown | `informes/render/{RenderHtml,RenderMarkdown,Html}.java`, `resources/…/informes/render/informe.css`, `resources/…/informes/vendor/{mermaid.min.js,mermaid.LICENSE}`, `spoon-plugin/THIRD-PARTY-NOTICES.md` (Mermaid y sus dependencias, más POI), `textos/render_*.properties`, `PruebaHtml.java`, `PruebaMarkdown.java` | Modelo, `Tema`, `GraficosSvg` | Los dos modos de HTML y Markdown | Sobre `ModeloMuestra`: cero URLs `http(s)://` salvo los `xmlns`; abre en Edge; el modo estático no tiene `<script>`; el `.md` se ve en Obsidian (prueba manual) |
| **WP8** | Chromium: PDF y pre-render | `informes/navegador/{Chromium,RenderPdf,PrerenderMermaid}.java`, `src/test/resources/html/diagramas.html`, `PruebaChromium.java` (se saltea si no hay navegador) | `RenderHtml` (WP7); hasta que llegue, el HTML fijo de prueba | `RenderPdf`, `PrerenderMermaid.svg/png`, `Chromium.ubicar/correr` | **Primero:** la prueba de concepto de captura y recorte (§3.5). Después: PDF de `ModeloMuestra` válido, SVG que IE11 dibuja con rótulos y sin procesos colgados |
| **WP9** | PowerPoint | `informes/render/{RenderPptx,PptxDiapos}.java`, `textos/pptx_*.properties`, `PruebaPptx.java` (lo vuelve a leer con POI; opcionalmente python-pptx y LibreOffice como en `validate.py`) | Modelo, `Tema`, `SimboloIdc`, `GraficosSvg`/`SvgAPng`, `PrerenderMermaid.png` (opcional) | `RenderPptx` | Sobre `ModeloMuestra`: diapositivas esperadas, notas e imágenes; sin navegador, con tablas en lugar de diagramas |
| **WP10** | MCP, integración y documentación | **mod** `ReportTools.java` (3 tools), **mod** `Docs.java` (`activo()` y `resolverSinActivar`), **mod** `DocumentTools.java` (`new_*`), **mod** `McpLifecycleListener.java` (`INSTRUCCIONES` y chequeo de loaders), **mod** `README*.md`, `CHANGELOG.md`, `docs/api-spoon.md` (Anexo B), nuevo `claude/skills/pentaho-etl/manual/04-informes.md` (cómo escribir la narrativa; no entra al jar), `PruebaMcpInformes.java` | `InformeServicio` (WP2), `PanelInformes.mostrar` (WP1) | Tools MCP | Esquemas válidos; `build_report` sobre una captura de archivo da todos los formatos |
| **WP11** (opcional, fase 2) | Vista previa con Edge | `instalar.ps1` e instalador: paso *opt-in* que hace copia del `swt.jar`, chequea Java y avisa de la licencia EPL | – | – | Fuera del lote paralelo |

**Dependencias:**

```
WP0 ──► { WP1, WP2, WP3, WP4, WP5, WP6, WP7, WP8, WP9, WP10 }   (en paralelo; cada uno compila contra los stubs)
        WP8 usa RenderHtml real cuando llega WP7 (antes, HTML fijo)
        WP10 prueba de punta a punta cuando llega WP2
fusión ──► test.ps1 completo ──► prueba en Spoon real (§8.3) ──► arreglos ──► 0.5.0
```

### 7.3 Contrato entre paquetes (lo que fija WP0)

```java
package io.github.ivi2511.pentahomcp.informes;

public interface Recolector {
  String id();                                  // SeccionId.*
  String noAplica( Captura c, String idioma );  // null si aplica; si no, motivo traducido. Barato: en la UI.
  Seccion armar( Contexto ctx ) throws Exception;  // worker; nunca SWT; respeta ctx.cancelado()/msRestantes()
}
public interface Renderizador {
  Formato formato();                            // HTML, HTML_ESTATICO, PDF, MARKDOWN, PPTX, JSON
  Resultado escribir( Informe inf, Tema tema, File destino, Progreso p ) throws Exception;  // worker
}
public interface Contexto {
  Captura captura(); Pedido pedido(); String idioma(); Tema tema();
  Rotulos rotulos( String area );                                   // textos/<area>_<idioma>.properties
  List<StepMeta> orden();                                           // ReportMotor.ordenTrans
  RowMetaInterface salida( StepMeta s ) throws KettleStepException; // memo + timeout compartidos
  RowMetaInterface entrada( StepMeta s ) throws KettleStepException;
  Progreso progreso(); boolean cancelado(); long msRestantes();
  void aviso( String texto );
}
public interface Progreso { void paso( String texto, int hecho, int total ); boolean cancelado(); }
public final class Captura {                     // la llena CapturaUi en la UI
  public AbstractMeta copia;                     // desligada (ReportTools.clonar), con repo y metastore
  public String docId, nombre, archivo, tipo;    // tipo: transformation | job
  public boolean sinGuardar;
  public org.eclipse.swt.graphics.ImageData lienzoCrudo; // lo deja la UI (ReportImagen.pintar, escala 2); solo dato
  public Imagen lienzo;                          // el worker lo codifica (ReportImagen.png) y vacía lienzoCrudo
  public Trans ultimaTrans; public Job ultimoJob;
  public Date capturado;
}
public final class Resultado { public List<File> archivos; public List<String> avisos; public long bytes; }
// Rotulos: static Rotulos de( String area, String idioma ); String s( String clave ); String f( String clave, Object... args )

// io.github.ivi2511.pentahomcp.InformeServicio (WP2)
public static Captura capturar( Spoon sp, AbstractMeta vivo );                 // UI
public static Captura capturarArchivo( String ruta, Display d ) throws Exception; // carga en el worker, pinta en la UI
public static List<Disponibilidad> disponibles( Captura c, String idioma );    // barato
public static Future<Informe> armar( Captura c, Pedido p, Progreso pr );       // pool de informes
public static Resultado exportar( Informe inf, Tema t, Formato f, File destino, Progreso pr ) throws Exception;
public static File vistaPrevia( Informe inf, Tema t, boolean prerender, Progreso pr ) throws Exception; // HTML_ESTATICO
public static Informe sinBinarios( Informe inf );
```

---

## 8. Plan de pruebas

### 8.1 Automáticas y headless (`spoon-plugin\test.ps1`, sin Spoon)

| Prueba | Qué verifica | Paquete |
|---|---|---|
| `PruebaModelo` | Ida y vuelta Jackson de `ModeloMuestra`; `sinBinarios` no deja ningún `byte[]` | WP0 |
| `PruebaRegistroPerspectiva` | `KettleEnvironment.init()` y `SpoonPluginManager.getInstance()` con el jar real en una carpeta `plugins` temporal: la perspectiva aparece con su id, `getPerspective()` devuelve siempre la misma instancia, y **el loader es el mismo que el del `@LifecyclePlugin`**. Con `PENTAHO_LIVE_REPORTS=off` no hay perspectiva. | WP1 |
| `PruebaEstructura`, `PruebaServicio` | Informes de 01–11 (trans y jobs): estados, tiempos y JSON; pedido cancelado a mitad de camino | WP2 |
| `PruebaLinaje` | Metaverse contra el motor propio en 01–06; 07 queda parcial; el mapa queda limpio | WP3 |
| `PruebaMetricas`, `PruebaPerfil` | `run_copy` de 03 y de los jobs 10 y 11 (con Gantt); perfil de 04 y de la copia grande (20.000 filas en menos de 5 s) | WP4 |
| `PruebaDestino` | Modelo ER de 07 sin y con conexión sobre una copia temporal; no se crea archivo si falta | WP5 |
| `PruebaGraficos`, `PruebaSimbolo`, `PruebaAjustes` | SVG válido (se parsea como XML) y PNG con Batik; el símbolo coincide con el manual; lectura y escritura con `KETTLE_HOME` temporal | WP6 |
| `PruebaHtml`, `PruebaMarkdown` | Sin red, sin `<script>` en el modo estático, escapes (un step llamado `<b>&"x`), front matter y bloques mermaid | WP7 |
| `PruebaChromium` | `ubicar`, PDF válido (`%PDF`, más de 1 KB, código 0), pre-render a SVG y PNG, **ningún proceso `msedge` con nuestro `--user-data-dir` vivo al terminar** | WP8 |
| `PruebaPptx` | POI la vuelve a leer: cantidad de diapositivas, imágenes, tablas, notas y 960x540; sin navegador, con tablas | WP9 |
| `PruebaMcpInformes` | Esquemas JSON de las tools; `build_report` sobre una captura de archivo | WP10 |
| Bytecode | Todas las clases del jar con *major version* 52 | WP0 (script) |

### 8.2 Con escritorio pero sin Spoon (*harness* SWT)

Estas no corren en CI, pero sí en la máquina de desarrollo:

- **IE11.** Un *harness* como `BrowserProbe` de la investigación carga la vista previa estática de `ModeloMuestra` y de un informe real. Verifica `documentMode=11`, la cantidad de `<svg>` y que los rótulos se vean, y guarda una captura de pantalla.
- **El panel en una `Shell`** con un servicio falso (`PruebaPanelSwt`). Sirve para revisar la disposición a 100 % y a 150 %.

### 8.3 En Spoon real (lista para Iván, o para un agente con permiso de usar la PC)

1. Spoon arranca y el log no muestra errores. «Informes» aparece en el desplegable de perspectivas. El log del MCP **no** muestra la alerta de loaders distintos.
2. Ir y volver entre Data Integration e Informes: las pestañas siguen intactas. Revisar cómo quedan los menús Archivo y Ver en Informes (riesgo 4).
3. Con Informes al frente: Ctrl+S y Ctrl+W no hacen nada dañino; Archivo › Cerrar no cierra una pestaña oculta.
4. MCP con Informes al frente: `status`, `new_transformation` y `add_step` funcionan, y las de edición traen al usuario de vuelta a DI. `build_report` **no** cambia de perspectiva. `show_report` sí cambia.
5. El combo de documentos se actualiza al abrir y cerrar archivos. «Abrir archivo…» informa un `.ktr` sin abrir pestaña.
6. La vista previa liviana aparece al activar. La vista previa completa trae los diagramas en la segunda pasada. «Abrir en el navegador» funciona.
7. Métricas después de una corrida normal (sin métricas, Gantt aproximado) y con *Gather performance metrics* (Gantt real). «Medir…» pide confirmación cuando hay salidas.
8. Perfil sobre un step con escritores aguas arriba: aparece la advertencia.
9. Exportar los cuatro formatos. Abrir el PPTX **en PowerPoint**, el PDF en Edge o Acrobat y el `.md` en Obsidian.
10. La identidad y el logo se mantienen después de reiniciar Spoon.
11. Cerrar Spoon con una vista previa en curso: no queda ningún `msedge` colgado (Administrador de tareas) y la próxima vez se borran los temporales.
12. Pantalla al 150 %: vista previa y lienzo nítidos.
13. `PENTAHO_LIVE_REPORTS=off` en `kettle.properties`: la perspectiva desaparece y el MCP sigue andando.

---

## 9. Lo que la investigación NO probó

| Punto | Dónde importa | Quién lo cierra |
|---|---|---|
| **Nada se probó con Spoon abierto.** No se probaron: la perspectiva en el desplegable, el cambio de perspectiva, los menús después de quitar `main_perspective_overlay.xul`, Ctrl+S y Ctrl+W, qué hace Spoon con el valor de `onFileClose()` ni la opción `-perspective=`. | §1.2, riesgos 1, 3 y 4 | §8.3 |
| El ítem en *Ver › Perspectivas* por XUL desde `applyToContainer`, y `registerClassLoader` + `loadOverlay` desde nuestro jar | Fuera de la v1 | – |
| Metaverse, perfil y métricas **dentro de Spoon** (fuera de Spoon sí se probaron) | §2.4 | §8.3 |
| Leer `TransGraph.getTrans()` y `JobGraph.getJob()` de la última corrida para las métricas (solo bytecode; `getTrans()` ya se usa para íconos [S]) | §2.4.2 | §8.3 |
| `MetricasHook` (`@ExtensionPoint` en nuestro jar) y el enganche a `JobStart` | Opcional | WP4 |
| `htmlLabels:false` en **erDiagram** (probado en flowchart y class) | §3.1 | WP8 / §8.2 |
| Captura y recorte de Mermaid a PNG con `--screenshot`, y Batik sobre el SVG de Mermaid | §3.5 | WP8 (lo primero) |
| `position:fixed` repetido en cada página del PDF, una imagen en las cajas `@page`, páginas apaisadas por nombre, enlaces internos del índice dentro del PDF | §3.1, §4.3 | WP7 / WP8 |
| Que no queden procesos de Chromium colgados después de un timeout; Edge headless con el Edge del usuario abierto (solo se probó junto a Chrome) | §3.2 | WP8 |
| La impresión de respaldo con `window.print()` abierta por `Program.launch` | §3.2 | WP8 |
| PowerPoint real (sí se probaron POI, python-pptx y LibreOffice); el borrado de los *placeholders* de notas | §3.4 | WP9 / §8.3 |
| AWT (Batik, ImageIO, POI) dentro del proceso de Spoon | Riesgo 14 | §8.3 |
| La versión de Mermaid que trae Obsidian | §3.3 | WP7 (manual) |
| Spoon con un SWT más nuevo (Edge embebido); sin `X-UA-Compatible`, el `documentMode` de IE | WP11 | – |
| Licencias: el detalle de las dependencias del bundle de Mermaid (d3 ISC, ¿elkjs EPL?); Blueprints/TinkerPop (BSD, de memoria; no se empaqueta); las CVE de Batik e iText (de memoria) | Riesgo 18 | WP7 |
| El HTML con HiDPI al 150 % | Riesgo 23 | §8.3 |

---

## Anexo A: experimentos de referencia

Todos están en la carpeta temporal de la sesión de diseño (`scratchpad\informes\`). Son temporales: si se necesitan, conviene copiar lo útil a `src/test` en WP0.

| Carpeta | Qué hay |
|---|---|
| `perspectiva\` | `sketch\` (plugin, perspectiva y panel; **ojo con el `classLoaderGroup`**), `exp1\` (*harness* de registro y loaders: variantes A, B y C en `run`, `runB` y `runC`; `IeScriptError.java`; `run\preview.png`), `javap\` (volcados de `Spoon`, `SpoonPerspectiveManager`, `SpoonPluginManager`, `SpoonPluginType`, `MainSpoonPerspective`, `PluginRegistry`) |
| `browser-preview\` | `report-test.html`, `report-print.html` (números de página), `report-snap-svg.html` y `dumped-snap-svg.html` (instantánea que IE dibuja), `src\{BrowserProbe,EdgeTabProbe,SwtLinkCheck}.java`, `vendor\` (Mermaid 11.4.1 y licencias), `swtnew\` (SWT 3.116.100, 3.121, 3.135), `out\` (PDF y capturas) |
| `libs-pdi\` | `src\exp\ReportExport.java` (PPTX, XDDF, PDF), `TransImage.java` (TransPainter con SwingGC), `SvgFix.java`, `src\harness\Harness.java` (loader tipo plugin), `validate.py`, `out\` |
| `datos\` | `src\{MvProbe,MvGraph,Linaje,Corrida,Destino,Igual,SqliteSide}.java`, `src\io\github\ivi2511\pentahomcp\{MvClon,Perfil}.java`, `ej\` y `ej_big\` (ejemplos 01–11), `db\dw.sqlite`, `out_linaje_03.json` |

## Anexo B: correcciones a `docs/api-spoon.md` (las aplica WP10)

- Dice «SWT 3.121». **El SWT real es 3.115.100** (`libswt\win64\swt.jar`, 4940r23, diciembre de 2020). `lib\org.eclipse.swt-3.121.0.jar` es un cascarón OSGi de 19 KB, sin clases [P].
- §1.1 dice que `SpoonPluginType` se agrega «en `main`». Lo hace `registerUIPluginObjectTypes()` antes de `KettleEnvironment.init()`, y solo escanea `plugins` y `plugins/repositories` (no `~/.kettle/plugins`) [B, P].
- §2: con una perspectiva propia activa, `getActiveMeta()` le pregunta a esa perspectiva. Hay que usar `getActiveTransGraph()` / `getActiveJobGraph()` para leer la pestaña de DI [B].
