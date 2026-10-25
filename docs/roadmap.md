# Hoja de ruta

Lo que viene después de la 0.4.0, en el orden en que se va a hacer. Cada fase es una versión.
Decidido con Iván el 20-oct-2026. **El seguimiento vive en Linear** (team Externos): projects
«Pentaho Live 0.4.1» (P-EXT-34) y «Pentaho Live 0.5.0» (P-EXT-35); las ideas de 0.6 y 0.7 están en
Triage (EXT-447 a EXT-454). Este archivo es el resumen; lo que hay que hacer está en Linear.

## 0.4.1 · Instalador — publicada el 20-oct-2026

Salió sin esperar Informes (decidido el 20-oct-2026), junto con el autor «Iván Castaño · IDC Studios».

- **Instalador de Windows** (Inno Setup 7, convención 04 del portafolio): completo (PDI 9.4 CE +
  plugin + Java Temurin 21) o solo el plugin en un PDI existente; accesos directos con el ícono, sin
  admin, en inglés/español/portugués; comando `pentaho-live` en el PATH para que un agente pueda
  abrir Spoon (con permiso del usuario). El zip sigue disponible para quien no quiera un `.exe` sin
  firmar.

## 0.4.2 · Arreglos del instalador

- Lo que Iván encontró con la 0.4.1: los accesos abren el splash del lanzador de IDC mientras carga
  Spoon, sin el aviso de seguridad de `Spoon.bat` (PDI de un zip bajado), y la carpeta de instalación
  ya no puede quedar dentro de un PDI (la 0.4.1 instalada ahí se muda sola). Diseño:
  `docs/lanzador-042-diseno.md`.

## 0.5.0 · Informes

- **Informes** — una pestaña propia en Spoon («Reports», junto a «Data Integration») que arma sola,
  sin IA, el informe de una transformación o un job: portada, resumen, diagrama (la imagen real del
  lienzo + Mermaid), linaje de campos, diccionario de datos, perfil de una muestra, métricas de la
  última ejecución (barras + Gantt), flujo de jobs, modelo de las tablas destino, validación,
  conexiones y parámetros. Vista previa adentro de Spoon y exportación a **HTML** (un archivo,
  offline, para presentar), **PDF**, **Markdown** (Obsidian/GitHub) y **PowerPoint**. Estilo neutro
  con el logo, los colores y los datos de quien lo usa (facultad, empresa), y una firma discreta
  «Pentaho Live · IDC Studios» con el símbolo de las celdas como marca de agua. Narrativa opcional
  escrita por Claude vía MCP. Diseño: `docs/informes-diseno.md`.

## 0.6.0 · Calidad y clases — hecha el 22-oct-2026 (rama `feat/0.6.0`, sin publicar)

Las cuatro piezas se integraron en `feat/0.6.0` (73 herramientas MCP, guías 06 calidad, 07 corrector,
08 modo clase y 09 robustez). Lo que cada una dejó para después está en 0.7.0.

- **Linter de ETL** — **hecho** (21-oct-2026, rama `feat/calidad`, sin publicar). Buenas prácticas más
  allá de `validate`: rutas fijas, contraseñas a la vista, Group by / Merge join sin ordenar, steps con
  nombre por defecto, la trampa del decimal con locale español… Con puntaje y cómo arreglar cada cosa.
  Quedó así: `lint_etl` (28 reglas encendidas, puntaje 0-100 y modo profundo que lee las fuentes con
  previews de solo lectura), `apply_lint_fixes` y `suppress_lint`, el recurso
  `pentaho://lint/rules` y la sección «Calidad técnica» de los Informes. Salidas sin manejo de errores
  quedó como regla apagada (para el corrector y el modo clase) y la conversión perezosa se podó (sin daño
  demostrado en 9.4). Falta: clic en un hallazgo de la vista previa para seleccionar el step y un botón
  «Revisar calidad». Diseño: `docs/linter-diseno.md`; integración: `docs/linter-integracion.md`.
- **Robustez automática** — **hecha** (22-oct-2026, rama `feat/robustez`, sin publicar). Pedida por Iván
  después de robustecer a mano dos transformaciones de clase: que una carga tolere datos sucios sin pararse
  ni perder filas, y que lo haga la herramienta o la IA sola. Quedó así: la guía `manual/09-robustez.md`
  (recetas R01-R14) y el patrón `06_carga_tolerante`; 17 reglas `robustness` del linter (apagadas por
  defecto, con su propio puntaje: `lint_etl robustness=true`); `harden_etl` (plan con diff, un solo
  Ctrl+Z) y `stress_test` (variantes sucias de las entradas en una JVM hija aislada, balance de filas,
  re-ejecución sobre SQLite). Falta: el diálogo «Robustecer…» en Spoon, las recetas
  manuales con aplicación (claves estables, Insert/Update, agregar al grano, partir sin fallar), seguir
  los renombres para deducir los obligatorios. En los Informes, los hallazgos de robustez van dentro de la
  sección «calidad» (no hay una sección de robustez aparte). Diseño:
  `docs/robustez-diseno.md`.
- **Corrector de ejercicios** — **hecho** (21-oct-2026, rama `feat/corrector`, sin publicar). El docente arma
  un pack desde su solución (enunciado, datos de un caso visible y de uno oculto, salida esperada, reglas y
  rúbrica); el plugin corre cada entrega en una JVM aparte con una política de steps, compara filas y arma la
  nota con lo que falló, una pista por error típico, la devolución del alumno (sin los valores del caso oculto)
  y la planilla para Excel. Quedó así: `create_exercise_pack`, `validate_exercise_pack`, `grade_exercise`,
  `grade_submissions` y `get_grading_status`, la perspectiva **Corrector** en Spoon (plan B:
  perspectiva propia, porque Informes no tiene pestañas) con el asistente «Crear pack desde esta
  transformación», las devoluciones y el informe del curso con los renderizadores de Informes, la guía
  `manual/07-corrector.md` y el pack de ejemplo de Grano de Oro (`ejemplo/pack-correccion/`). Falta: corregir
  jobs (`.kjb`), el menú «Herramientas › Corrector de ejercicios…», la pestaña «Corrección» junto al lienzo
  para la autocorrección del alumno y la prueba en Spoon real. Diseño: `docs/corrector-diseno.md`.
- **Modo clase** — **hecho** (22-oct-2026, rama `feat/modo-clase`, sin publicar). Claude construye despacio
  y deja una nota en el lienzo junto a cada step explicando qué hace y por qué. Quedó así: `lesson_start`
  (guion completo validado y planificado antes de tocar nada), `lesson_next`, `lesson_status` (con
  `wait_s`), `lesson_control` y `lesson_notes`; la pestaña «Lección» en el panel de
  resultados con «Siguiente ▶», «◀ Atrás» (un Ctrl+Z real), pausa y vista previa; ritmos guiado, automático
  y manual; notas en bandas sin choques (en columnas de 3 o más steps, una nota compacta con el número; las
  notas lejanas se acercan); glosario de clase en/es/pt (`pentaho://glossary`); la guía para repartir
  como sección `lesson` de los Informes; el manual `08-modo-clase.md` y tres lecciones probadas (L1-L3,
  es/en/pt) más la de la demo. Desviación: Spoon 9.4 no llama al gancho `loadTab`, así que la pestaña
  vuelve con un vigilante mientras el panel está cerrado.
  Falta: jobs, un botón «Guía…» en la pestaña, reproducir una lección guardada sin IA y que el relayout
  justo después de una lección no mueva notas de más. Diseño: `docs/modo-clase-diseno.md`.

## 0.7.0 · Trabajo en equipo y datos

- **Diff visual** entre dos versiones de una transformación (agregado / borrado / cambiado, marcado en
  el lienzo).
- **Pruebas de regresión** — **hechas** (25-oct-2026, rama `feat/070-pruebas`, sin publicar; P1 y P2 de la
  propuesta 0.7). Quedó así: `tests/<nombre>.test.json` junto a la `.ktr` (formato `pentaho-live-test/1`,
  JSON Schema en `docs/schemas/`), con datasets que reemplazan steps de entrada (un Injector con el RowMeta
  original), salidas comparadas fila a fila con CSV dorados, ajustes (`skip_step`, `remove_step`,
  `set_variable`, `set_parameter`) y bases de prueba descartables; el motor es el del corrector (el caso,
  `compare`, las bases y las tablas son su formato) en una JVM aparte. `test_create` (snapshot),
  `test_run`, `test_run_all`, `test_list`; **IDC › Pruebas…** (verde y rojo, ejecutar con cancelar, la
  diferencia fila a fila, crear desde la vista previa) y **ejecutar al guardar** (apagado por defecto: solo
  las pruebas afectadas por el cambio, indicador ✓ 12/12 o ✗ 1 en el botón IDC y un globo si falla). Guía:
  `manual/12-pruebas.md`; ejemplo: `ejemplo/pruebas/`. Falta: correrlas con Pan en CI (P4, sin Spoon:
  un `pentaho-live test` de línea de comandos sobre `PruebasMain`), pruebas de jobs, el diff de E1 para
  elegir las pruebas afectadas con más precisión, y una prueba que congele una salida a archivo leyendo el
  archivo (hoy se captura la salida del step).
- **Generador de datos «sucios»** para practicar limpieza (nulos, duplicados, fechas y decimales mal
  escritos).
- **Catálogo de una carpeta** — qué job llama a qué transformación, qué tablas lee y escribe cada una.
- **Patrones listos** — carga incremental por fecha, dimensión SCD tipo 2, deduplicación,
  CSV → staging.
- **Pendiente de 0.6.0**:
  - Corrector: corregir jobs (`.kjb`); bloquear Execute row SQL script y Dynamic SQL row cuando el pack
    tiene base (hoy solo avisan `dynamic_sql`, y un `ATTACH` armado con datos de las filas no se frena);
    revisar las carpetas después de cada corrida (que todo quedó en `salida/` o `tmp/`); el menú
    «Herramientas › Corrector de ejercicios…» y la pestaña «Corrección» para el alumno; prueba en Spoon real.
  - Modo clase: jobs; un botón «Guía…» en la pestaña; reproducir una lección guardada sin IA; prueba del
    salto de vista previa con `allow_write=false`; prueba en Spoon real.
  - Robustez: el diálogo «Robustecer…» en Spoon; lista blanca completa de steps para `stress_test`
    (hoy, lista negra más un comodín por nombre); que `confirm=["all"]` no cubra recetas que apartan
    filas de una carga; la regla `empty_decimal_symbol` también en Select values; las recetas manuales
    con aplicación (claves estables, Insert/Update, agregar al grano, partir sin fallar); la sección de
    robustez de los Informes; probar `harden_etl` en Spoon real (simulación, aplicar, Ctrl+Z).
  - Linter: clic en un hallazgo de la vista previa para seleccionar el step y un botón «Revisar calidad».
