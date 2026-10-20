# Informes 0.5.0: notas del contrato (WP0)

Guía corta para los paquetes WP1–WP10. La especificación sigue siendo `docs/informes-diseno.md`; acá
está lo que WP0 dejó hecho, cómo correr las pruebas y **lo que difiere del diseño** (§5 de esta nota).
Si hace falta cambiar un archivo de WP0, se pide al coordinador: no se edita directo.

## 1. Paquetes y dueños

Rutas bajo `spoon-plugin/src/main/java/io/github/ivi2511/pentahomcp/`.

| Archivo | Dueño | Estado |
|---|---|---|
| `informes/modelo/*` (Informe, Seccion, Bloque, Parrafo, Lista, Tabla, Indicadores, Indicador, Diagrama, Imagen, Grafico, Serie, Tramo, Aviso, Narrativa, **ModeloJson**) | WP0 | hecho |
| `informes/{Recolector,Renderizador,Contexto,Progreso,Captura,Pedido,Formato,Resultado,Rotulos,SeccionId,Disponibilidad}.java` | WP0 | hecho |
| `informes/tema/Tema.java` | WP0 | hecho |
| `resources/…/informes/textos/secciones_{en,es,pt}.properties` (títulos de las secciones) | WP0 | hecho |
| `InformeServicio`, `CapturaUi`, `ContextoKettle` | WP2 | stub (`sinBinarios` ya anda) |
| `SecLinaje` | WP3 | stub: devuelve la sección «omitida» |
| `SecMetricas`, `SecPerfil` | WP4 | stub: «omitida» |
| `SecDestino` | WP5 | stub: «omitida» |
| `informes/tema/{Ajustes,SimboloIdc,Paleta}`, `informes/graficos/{GraficosSvg,SvgAPng}` | WP6 | stub (`Ajustes.cargar()` devuelve `new Tema()`) |
| `informes/render/{RenderHtml,RenderMarkdown}` | WP7 | stub |
| `informes/navegador/{Chromium,RenderPdf,PrerenderMermaid}` | WP8 | stub |
| `informes/render/RenderPptx` | WP9 | stub |
| `informes/ui/PanelInformes` (`mostrar`) | WP1 | stub |
| `spoon-plugin/test.ps1`, cambios de `build.ps1` | WP0 | hecho |
| `src/test/java/…/{Pruebas,Verificador,EntornoKettle,ModeloMuestra,ContextoPrueba,PruebaModelo,PruebaEntorno}.java` | WP0 | hecho |
| `src/test/resources/ej/` (01–11 + `datos/`) y `db/dw.sqlite` | WP0 | copiados |

Cada stub lleva `@dueño WPn` en su javadoc: desde ahora es de ese paquete, que puede cambiar el cuerpo
y **agregar** métodos. Cambiar una firma que ya usa otro paquete (las de §7.3 del diseño y las de §5
de esta nota) se coordina.

## 2. Convenciones del contrato que conviene saber

- **JSON.** Usar siempre `ModeloJson.mapper()` / `escribir(o, indentado)` / `leer(json, clase)`. El tipo
  de bloque va en `"tipo"` (`parrafo`, `lista`, `tabla`, `indicadores`, `diagrama`, `grafico`, `imagen`,
  `aviso`; constantes en `Bloque`). Las propiedades `null` no salen; las desconocidas se ignoran al leer.
- **Estados** de sección: `Seccion.OK|PARCIAL|OMITIDA|ERROR`; `Seccion.omitida(id, titulo, motivo)`.
- **Strings enumerados** tienen constantes: `Grafico.BARRAS_H|GANTT|LINEA`, `Diagrama.FLUJO|JOB|LINAJE|ER`,
  `Aviso.INFO|WARN|ERROR`, `Indicador.OK|WARN|ERROR` (o `null`), `Tramo.INIT|EXEC`,
  `Narrativa.USUARIO|IA`, `Informe/Captura.TRANSFORMACION|JOB`, `Pedido.METRICAS_*`.
- **Imagen:** `ancho`/`alto` son los píxeles de `datos`; se muestra a `ancho/escala` (escala 2 = lienzo
  al doble, el HTML lo muestra al 50 %). `escala` vale 1 por defecto.
- **Renderizador.escribir(inf, tema, destino, p):** `destino` es el archivo principal (`…\informe.html`);
  los extras van al lado (`informe_archivos\`). El `Resultado` lista el principal primero
  (`resultado.archivo(f)` suma los bytes).
- **Rotulos.** `Rotulos.de(area, idioma)`: lee `textos/<area>_<idioma>.properties` en UTF-8; si falta la
  clave usa `en`, y si tampoco está devuelve la clave (nunca null ni excepción). `f(clave, args)` es
  **`String.format(locale, …)`** (no MessageFormat): `%,d` pone los miles y `%.1f` los decimales del
  idioma (`es` → «400.000» y «2,7»; `en` → «400,000» y «2.7»); un `%` literal va como `%%`.
  `Rotulos.normalizar("es_AR") = "es"`; otro idioma → `en`. Cada WP crea solo sus archivos de área.
- **Títulos de sección:** `SeccionId.titulo(id, idioma)` (área `secciones`, de WP0). Orden del informe:
  `SeccionId.ORDEN` (el índice lo pone el renderizador).
- **Tema** = el `informes.json` de §4.1, con clases anidadas: `tema.identidad.autor`,
  `tema.colores.primario`, `tema.salida.formatos`, `tema.perfil.mostrarValores`, `tema.marcaDeAgua`,
  `tema.rotularIa`… Los neutros de §4.2 son constantes (`Tema.TEXTO`, `TEXTO_TENUE`, `BORDE`, `FONDO`,
  `FUENTE`, `FUENTE_CODIGO`) y el pie es `Tema.PIE` («Pentaho Live · IDC Studios», con el «·» literal).
  `logoDatos`/`logoMime` no van al JSON (los carga `Ajustes`). A un worker se le pasa `tema.copia()`.
- **Progreso.NULO** sirve cuando no hace falta avisar nada.

## 3. Pruebas

Una clase `Prueba*` con constructor público sin argumentos, en cualquier paquete bajo `src/test/java`.
El runner (`Pruebas`) la descubre solo y corre, en orden alfabético, sus métodos **públicos, de
instancia, sin argumentos, que empiezan con `prueba`**. Una sola instancia por clase. Opcionales:
`public void preparar()` (antes de todos) y `public void cerrar()` (después). Tope de 600 s por método.

- Aserciones: `import static io.github.ivi2511.pentahomcp.Verificador.*;` → `cierto`, `falso`, `igual`,
  `distinto`, `nulo`, `noNulo`, `contiene`, `noContiene`, `archivo(f, minBytes, msg)`, `lanza`, `falla`.
  `saltear("no hay Edge")` cuenta como **salteada**, no como falla.
- `EntornoKettle.iniciar()`: `KettleEnvironment.init()` una vez por JVM (unos 6 s), con el cliente en
  `SPOON`. `trans("03")`, `job("10")`, `captura("03")` (sin lienzo), `ejemplo("07")` → archivo; todo
  sobre una **copia** de `ej\` en `<Salida>\prueba\ej`. `copiaDw()` da una copia nueva de `dw.sqlite`.
  `salida("graficos")` → carpeta para dejar archivos generados. `leer`/`escribir` en UTF-8.
- `ContextoPrueba.de("03", "es")`: un `Contexto` simple (getStepFields directo, con memo) para probar un
  recolector sin WP2. Expone `avisos`, `pasos` y `cancelar`.
- `ModeloMuestra.crear()`: informe completo (12 secciones, 8 tipos de bloque, los 4 estados, los 3
  gráficos, diagramas flujo/linaje/er, un step `<b>&"x`, `|` en celdas, `ia = true`). Además
  `tema()` (con logo), `svgPrerender(inf)` y `pngPrerender(inf)` (simulan `PrerenderMermaid`, clave =
  `Diagrama.id`), `svgMuestra(texto, w, h)` (SVG con rect de fondo) y `png(w, h, texto)`.

**Cómo correr** (cada agente con **su propia** `-Salida`):

```powershell
powershell -ExecutionPolicy Bypass -File spoon-plugin\test.ps1 `
  -PdiHome "<PDI>\data-integration" `
  -Salida "<repo>\spoon-plugin\build\wp3" `
  -Solo PruebaLinaje            # o "PruebaLinaje,PruebaModelo.pruebaTema"; sin -Solo corre todas
# -SinCompilar reusa lo compilado en -Salida
```

- Compila main y test con `--release 8` contra `lib\*`, `libswt\win64\*`, `classes\` y POI; verifica
  major 52 en todas las clases; corre con cwd, `KETTLE_HOME` (variable y `-D`), `user.home` y metastore
  bajo `<Salida>\prueba\`, `KETTLE_PLUGIN_BASE_FOLDERS=<PDI>\plugins`, los `--add-opens` y
  `-Djava.locale.providers=COMPAT,SPI` de `Spoon.bat`, y `java.awt.headless=true`.
- Salida: una línea `OK|FALLA|SALTEADA Clase.metodo (ms)` por método y al final
  `RESULTADO: n ok, n fallas, n salteadas`. Código: 0 bien, 1 falla, 2 nada coincidió con `-Solo`,
  3 no compiló o hay bytecode que no es Java 8.
- Sin `-PdiHome` usa `PDI_HOME` o `<repo>\..\PDI 9.4\data-integration`.
- **`build.ps1` no es para correr en paralelo** (borra y rearma `dist\`). Para compilar y probar, `test.ps1`.
- `KETTLE_PLUGIN_BASE_FOLDERS` apunta al `plugins\` real de PDI, que hoy tiene instalado el
  `pentaho-mcp` 0.4.0: ese jar se registra con su propio loader (no se instancia sin Spoon). La prueba de
  registro de WP1 tiene que armar su propia carpeta `plugins` temporal, como dice el diseño.

## 4. Build

`build.ps1` ahora compila también contra `poi-4.1.1`, `poi-ooxml-4.1.1` y `poi-ooxml-schemas-4.1.1`
(de `plugins\pdi-core-plugins\lib\`), los copia a `dist\pentaho-mcp\lib\` y copia
`spoon-plugin\THIRD-PARTY-NOTICES.md` (lo escribe WP7) a `dist\pentaho-mcp\` si existe, así va dentro
del zip. El zip pasa de ~0,4 MB a ~10,9 MB. La versión sigue en 0.4.0 (el cambio de versión es del release).

## 5. Lo que difiere del diseño o lo completa

1. **`informes/Disponibilidad.java`** (nuevo, WP0): el tipo que devuelve `InformeServicio.disponibles`
   (§2.3 no lo listaba): `id, aplica, motivo, porDefecto, costo` con `COSTO_NADA|UI|CAMPOS|BASE|EJECUTA`
   (`none|ui|fields|database|runs`).
2. **`informes/modelo/ModeloJson.java`** (nuevo, WP0) y en `Informe`: `seccion(id)`, `copia()` y
   `sinBinarios()` (copia profunda con `Imagen.datos = null`, también la del `Diagrama.imagen`).
   `InformeServicio.sinBinarios` ya delega ahí; WP2 puede dejarlo así.
3. Ayudas en el modelo: constructores cortos (`new Parrafo(t)`, `new Tabla("A","B").fila(…)`,
   `new Aviso(nivel, t)`, `new Indicador(e, v[, detalle, nivel])`, `new Serie(n, v…)`,
   `new Tramo(c, tipo, ini, fin)`, `new Imagen(mime, bytes, w, h, escala)`), `Seccion.con(b)`,
   `Indicadores.con(i)`. Listas ya inicializadas (`columnas`, `filas`, `items`, `series`, `tramos`,
   `categorias`); `Tabla.alineacion` es null = todo a la izquierda.
4. **Área de textos `secciones`** (WP0) con los títulos de las 12 secciones en en/es/pt, y
   `SeccionId.titulo(id, idioma)`, `SeccionId.ORDEN`, `SeccionId.valido(id)`. Las constantes de id tienen
   nombre en castellano (`SeccionId.LINAJE = "lineage"`, `DESTINO = "target_model"`, `FLUJO_JOB = "job_flow"`…).
5. **Tema** mapea §4.1 con clases anidadas y nombres snake_case por `@JsonProperty`; agrega las
   constantes de §4.2, `PIE`, `copia()`, y `logoDatos`/`logoMime` (`@JsonIgnore`). `secciones = null`
   significa «los defaults de cada sección»; `idioma = null`, «el de Spoon».
6. **Firmas elegidas donde el diseño no las daba** (los dueños pueden agregar, no cambiar sin avisar):
   - `Ajustes.carpeta(): File`, `cargar(): Tema` (nunca null), `guardar(Tema)`.
   - `SimboloIdc.svg(String variante, float opacidad): String`, `png(int anchoPx, String variante, float opacidad): byte[]` (bytes de archivo PNG ARGB).
   - `Paleta.serie(Tema, int i): String`, `Paleta.claro(String hex, float fraccion): String`.
   - `SvgAPng.png(String svg, int anchoPx): byte[]`.
   - `Chromium.ubicar(): File` (null si no hay), `Chromium.correr(List<String> args, long timeoutMs): int`.
   - `PrerenderMermaid.svg(List<Diagrama>, Tema): Map<String,String>` y `png(…): Map<String,byte[]>`,
     con clave `Diagrama.id`; los que fallan no están en el mapa.
   - `new RenderPptx(Map<String,byte[]> pngMermaid)`: `render` no puede ver `navegador`, así que los PNG
     de Mermaid entran por el constructor (igual que `svgPrerender` en `RenderHtml`).
   - `new RenderPdf()`, `new RenderMarkdown()` sin argumentos; `RenderHtml.Modo {INTERACTIVO, ESTATICO}`.
   - `PanelInformes.mostrar(docId, pedido)` es **estático** (show_report no tiene la instancia).
   - `new ContextoKettle(Captura, Pedido, Tema, Progreso)`.
7. Los stubs `Sec*` devuelven `noAplica = null` y `armar` → sección «omitida» con motivo `"WPn"`.
8. Pruebas extra de WP0: `ContextoPrueba` y `PruebaEntorno` (no estaban en la lista del diseño).
9. `build.ps1` usa `build\jar\` y ya **no borra todo `build\`**: antes se llevaba las carpetas
   `-Salida` de las pruebas de los demás.
10. Datos de prueba: `ej\` sin `salida\` (son resultados); los `.ktr/.kjb` son idénticos a
    `claude/skills/pentaho-etl/manual/ejemplos`. La base de 07 es `${Internal.Entry.Current.Directory}/salida/demo.sqlite`,
    que **no existe** en la copia: es justo el caso «no se crea archivo si la ruta no existe» de WP5.
    `dw.sqlite` es otra base (12 KB) para la prueba con conexión.
11. ~~Abierto para WP2 y WP7: quién llena `Informe.rotulos`~~ Resuelto: las claves (`page`, `of`,
    `contents`, `generated_with`, `ai_label`, `generated`) viven en el área `render` (WP7),
    `InformeServicio` las copia y los renderizadores vuelven a esa área si `rotulos` viene vacío.
12. Cambios de contrato de la revisión final (20-oct-2026, un solo dueño):
    - `Grafico.leyenda` (opcional): nota de la leyenda del Gantt; `null` = la de siempre (los steps corren en
      paralelo), `""` = ninguna. Las métricas de un job la usan: sus entradas corren una tras otra.
    - `Captura.lienzoFallo`: por qué no se pudo pintar el lienzo de un documento con steps; el informe lo avisa.
    - `Rotulos.propio(clave)`: el texto solo en el idioma pedido (sin caer al inglés), o null.
    - `PanelInformes.mostrar(docId, pedido, informe)`: con el informe que ya armó `build_report`
      (`show_in_spoon`) el panel solo lo muestra. Por el MCP el panel nunca ejecuta nada (ni perfil ni
      Medir) y no corta una tarea del usuario: el pedido espera a que termine.
    - `Servicio.hayNavegador(Tema)`: con la ruta del navegador del tema primero, como el PDF y el pre-render.
    - `RunMotor.validar(…, TextosChequeo)`: los chequeos propios con textos traducidos para los informes;
      la tool `validate` sigue con los suyos (`TEXTOS_MCP`). **Desde la integración del linter (WP4)** los
      chequeos propios son reglas del motor (`RunMotor.REGLAS_DE_VALIDATE`) con los textos de
      `lint_{en,es,pt}.properties`: `TextosChequeo.idioma()` elige el idioma y `texto()` solo da `kettle_fallo`
      (las demás claves `val.propio.*` se borraron). Cambia lo que muestra la sección `validation`: otras
      palabras (y el nombre del step dentro del mensaje), el reparto a steps del mismo tipo ya no se anota y las
      entradas ocultas (`draw=N`) de jobs viejos ya no salen como «sin hops».
    - Área de textos nueva `plugins_{en,es,pt}`: nombres de tipos de step y de entrada en el idioma del
      informe (`NombresPlugin`), porque el registro de Kettle los traduce con el idioma de Spoon.
