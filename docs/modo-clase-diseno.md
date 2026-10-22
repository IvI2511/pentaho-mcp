# Modo clase (0.6.0) — diseño

Claude arma una transformación **despacio** y la explica en el lienzo de Spoon, para una clase
proyectada o para quien aprende solo. Estado (22-oct-2026): **implementado** en `feat/modo-clase`
(WP1-WP8; sin publicar). Desviaciones respecto de este diseño: el gancho `loadTab` no se llama en Spoon 9.4 y
la pestaña vuelve con un vigilante (§6); «A-/A+» solo cambia la letra de la pestaña; el manual es
`manual/08-modo-clase.md` (no `04-`); L3 son dos guiones (parte A y parte B). Diseño original: 20-oct-2026. Parte de la fase 0.6.0 «Calidad y clases» de `roadmap.md` (el linter y
el corrector de ejercicios son otros diseños; acá solo se nombran los puntos de contacto).

## 0. Decisiones en una lista

1. Las explicaciones son **notas de Spoon** (`NotePadMeta`) numeradas, una por step: «N · Nombre (Tipo)
   / Qué: … / Por qué: …», más notas «¡Ojo!» con errores reales del manual. Nada nuevo en el formato
   `.ktr`: las notas y las marcas viajan dentro del archivo y Pan las ignora (probado, también con
   las notas ocultas guardadas en un atributo).
2. Ubicación por **bandas**: la nota del step de más arriba de cada columna va en una banda sobre el
   flujo; las demás, en una banda debajo. Con el layout calculado **antes** de empezar, nada se mueve
   durante la clase. Sin solapamientos en 196 `.ktr` (§3), casi por construcción; lo difícil es la
   **legibilidad** en lienzos densos (§4.4).
3. El **ritmo lo maneja el plugin, no la IA**: Claude manda el guion completo (`lesson_start`) y Spoon
   lo reproduce paso a paso con un botón «Siguiente ▶» en una pestaña «Lección» del panel de
   resultados. MCP no puede empujar nada al modelo; la IA se entera con `lesson_status` (long-poll).
4. Limpiar, ocultar y mostrar mueven todas las notas de la lección en **una sola `TransAction`** (un
   Ctrl+Z; probado el tipo de acción y leído el bucle de undo de Spoon en el bytecode). Mientras se
   reproduce, cada paso de la lección es su propio Ctrl+Z.
5. La guía para entregar es una sección `lesson` del motor de Informes (0.5.0, sale antes que esta
   versión), exportada con `build_report`. `generate_report` no se toca: en la 0.6 lo migra Informes.
6. Cinco herramientas nuevas (`lesson_start`, `lesson_next`, `lesson_status`, `lesson_control`,
   `lesson_notes`): +5 sobre las que haya (54 hoy, 57 con las tres de Informes → 62; el linter y el
   corrector suman las suyas).
7. **Solo transformaciones**, incluido `lesson_notes`. Jobs: fase 2 (la regla de ubicación no se probó
   con `JobPainter` y el undo de jobs no encadena acciones).

## 1. Para quién y cómo se usa

- **Docente en clase** (Big Data 1, UEM): proyecta Spoon, le pide a Claude «armá la lección de Stream
  lookup, nivel principiante, en español» y avanza con «Siguiente ▶» al ritmo de la clase. Si un
  alumno pregunta, el docente le pregunta a Claude en el chat: `lesson_status` le dice dónde está.
- **Autodidacta**: lo mismo, solo; o ritmo `auto` (avanza cada N segundos, con pausa).
- **Docente que prepara**: arma la lección una vez, guarda el `.ktr` con las notas y lo reparte; los
  alumnos lo abren en cualquier Spoon (sin el plugin se ven las notas igual). `lesson_notes hide`
  deja el lienzo limpio para el ejercicio y `show` las devuelve. **Ocultar no es secreto:** el XML de
  las notas ocultas queda en el `.ktr`. Para repartir un ejercicio sin explicaciones: `remove` sobre
  una copia guardada aparte (o el pack del corrector de ejercicios).

## 2. Qué ya existe y se reutiliza

| Pieza | Para qué en el modo clase |
|---|---|
| `TransLote` (`leer`, `validar`, `empezar`, `agregar`, `cerrar`, paquete) | validar el guion entero antes de tocar el lienzo y crear cada step con su hop. Hoy los hops extra, las referencias por nombre (`<from>`, `send_true_to`) y los cambios de los steps previos se aplican recién en `cerrar`: la lección los necesita **por paso** (WP3) |
| `TransLayout.calcular` | `y` y `col[]` del grafo planificado; la `x` se recalcula con columnas de 240 (§4.3) sin tocar `TransLayout` |
| `UndoGrupo` | un Ctrl+Z por paso de la lección; uno solo para todas las notas al limpiar |
| `RunMotor.preview` | filas después de cada step, sobre una copia (como `preview_step`) |
| `ReportImagen` / `TransPainter` | verificar la ubicación (áreas `NOTE`, `STEP_ICON`) y la imagen de la guía |
| Motor de Informes (0.5.0: `build_report`, modelo `Seccion`/`Bloque`) | la guía como sección `lesson`, con sus exportaciones (§9) |
| `McpResources` + `build.ps1` | el glosario viaja dentro del jar igual que el catálogo |

## 3. Qué está probado y qué no

Prototipo descartable (`ProtoModoClase.java`, `ProtoLote.java`) compilado contra las clases del
plugin, con `KettleEnvironment.init` + un `Display` suelto + `PropsUI.init`, sin Spoon.

**Probado:**

| Qué | Resultado |
|---|---|
| Campos de `NotePadMeta` 9.4 (`javap -p`) | `note`, `fontname`, `fontsize`, `fontbold`, `fontitalic`, `fontcolor{red,green,blue}`, `backgroundcolor{…}`, `bordercolor{…}`, `drawshadow`, `location`, `width`, `height`, `selected`, `changed`, `id`. **Sin id propio en el `.ktr`** (`id` es el `ObjectId` de repositorio, no va al XML) **ni etiqueta** |
| `BasePainter.drawNote` (bytecode) | ancho = máx(`width`, texto + 2·5 px); escribe de vuelta `width`/`height` y registra un `AreaOwner` `NOTE` |
| Copia de `04_ventas_con_cliente.ktr` + 7 notas (título, 4 de paso, 2 «¡Ojo!») + 1 nota «del usuario» | 0 solapamientos medidos con las áreas del `TransPainter` real (íconos, nombres vía `getNamePosition`, hops, notas). Imagen: `modo-clase.png` |
| Pan sobre el `.ktr` con notas | exit 0, `E=0` en los 4 steps, 10 filas + encabezado, venta 108 con `DESCONOCIDO`. Repetido en la revisión, también sobre la copia con las notas ocultas en el atributo `hidden`: igual |
| Guardar y releer | las 8 notas y los 11 atributos del grupo `pentaho_live_lesson` vuelven (`id`, `level`, `language`, `notes` y 7 `note.N`; `schema` y `title` no se escribieron); 7/7 notas reconocidas por huella |
| Undo al agregar | 1 sola acción, tipo 8 (`NEW_NOTE`), 7 objetos |
| Limpiar | borra las 7 de la lección y deja la del usuario; 1 acción tipo 12 (`DELETE_NOTE`) con 7 objetos |
| Ocultar / mostrar | el XML de las notas guardado en un atributo, guardado en `.ktr`, releído y reconstruido con `new NotePadMeta(Node)`: 7/7 |
| Undo de Spoon con varias notas (bytecode de `SpoonTransformationDelegate` y `SpoonJobDelegate`) | deshacer `NEW_NOTE` las saca de atrás para adelante; deshacer `DELETE_NOTE` las repone en orden ascendente por índice: una acción = un Ctrl+Z, también en jobs |
| Lote: 189 samples de PDI + 7 ejemplos de la skill | 196 `.ktr`, 1427 notas, **0 solapamientos**, 0 fallas de carga. Ojo: los 0 choques salen casi por construcción (las bandas quedan fuera del rectángulo de los steps y una nota de 220 entra en una columna de 240); el lote prueba sobre todo que carga, ubica y pinta en 196 casos reales |
| Undo de `CHANGE_NOTE` (bytecode) | repone un **clon** de la nota anterior (`removeNote` + `addNote(i, prev.clone())`): la identidad del objeto no sobrevive a un Ctrl+Z (§4.5) |
| API para la pestaña (`javap`) | `TransGraph.extraViewTabFolder` (`CTabFolder`) y `addExtraView()` son públicos; `TransPreviewDelegate.addPreviewData(...)` también. `showExecutionResults()` es un **toggle**: con el panel visible lo cierra (`disposeExtraView` desecha el composite entero, con nuestra pestaña). `addAllTabs()` llama a los `SpoonUiExtenderPluginInterface` registrados para `TransGraph` con el evento `loadTab` (`TransGraph.LOAD_TAB`): gancho oficial para volver a poner la pestaña (§6) |
| Pregunta de L2 (Pan, revisión) | deshabilitar un hop del Sort rows **no** da 10 grupos: Group by falla («Error desconocido», exit 1). Saltear el Sort (hop Calcular importe → Totales por producto, copiar) da 10 (§11) |

**Hallazgos del lote:** (1) cero solapamientos no es lo mismo que legible. En el sample más grande
(36 steps, 21 en una sola columna) las notas de la banda de abajo quedan a más de 1000 px de sus
steps. De ahí la regla de densidad (§4.4) y el tope de pasos por lección. (2) Ese sample tenía notas
del autor que rotulaban cada validador; `stack_top` las apila arriba a la izquierda y pierden a qué
step se referían (§4.3.5).

**No probado** (no se lanzó Spoon): la pestaña y el botón en Spoon real, el gancho `loadTab` con
nuestra anotación, el redibujo, el Ctrl+Z del usuario a mitad de clase, cuánto espera un cliente MCP,
fuentes en Linux/macOS u otra escala, zoom para proyector, la nota compacta (§4.4), `existing_notes:
keep`, el plan con steps que todavía no existen (el prototipo anotó steps ya creados) y la vista
previa paso a paso.

## 4. Las notas en el lienzo

### 4.1 Tipos y estilo

Las notas no admiten estilo por línea ni enlaces; sí colores, fuente y sombra por nota. El tipo se
dice **también en el texto** (no solo con color) para quien no distingue colores o imprime en gris.

| Tipo | Texto | Fondo | Borde | Letra |
|---|---|---|---|---|
| título | «Lección · …» + nivel, pasos, concepto (negrita) | 221,235,247 | 70,110,160 | 20,40,80 |
| paso | «N · Nombre (Tipo)» / «Qué: …» / «Por qué: …» | 255,248,214 | 190,160,70 | 40,35,20 |
| ojo | «¡Ojo! (paso N) …» | 253,226,222 | 190,70,60 | 110,20,10 |
| tip (intermedio) | «Tip (paso N): …» | 228,243,228 | 80,140,80 | 20,60,20 |
| pregunta | «¿Pregunta? (paso N) …» | 240,232,250 | 120,90,170 | 50,30,90 |

Sin sombra (`drawshadow=N`): más limpio proyectado. Fuente: la de notas de Spoon (`PropsUI.getNoteFont`,
Segoe UI 9 en Windows) o `font_size` para proyector (todas las notas de la lección, una acción de undo).

### 4.2 Texto

- Ancho fijo de 220 px; el texto se corta por palabras **midiendo con el mismo GC y la misma fuente
  que usa el painter** (nunca por cantidad de letras). Una palabra más ancha (una ruta,
  `nombre_con_guiones`) se corta después de `/`, `_`, `.` o `\`; si no hay, por letra.
- Máximo ~6 líneas por nota de paso. Lo que no entra va a la pestaña «Lección» y a la guía, no al lienzo.
- Las etiquetas fijas («Qué», «Por qué», «¡Ojo!», «Lección», «paso») salen de un mapa en/es/pt como
  el de `ReportMotor`; el resto lo escribe Claude en el idioma pedido.

### 4.3 Regla de ubicación (la del prototipo)

1. **Layout de clase:** `TransLayout.calcular` tal cual (filas de 110 px) y, con el `col[]` que ya
   devuelve, `x = 100 + col · 240` (hoy 170) para que entre una nota de 220 por columna con aire.
2. **Columnas:** las de `col[]`; dentro de la columna, ordenados por `y`.
3. **Bandas:** la nota del step de más arriba de la columna va **arriba** del flujo; la de los demás,
   **abajo**. Un «¡Ojo!» o «Tip» va en la misma banda que la nota de su paso, del lado de afuera (la
   del paso queda pegada al flujo).
4. **x de la nota** = centro del ícono − 110 (mínimo 10).
5. **Notas que ya había** (del usuario): se apilan arriba a la izquierda, en filas de hasta 4 columnas,
   en su orden; el título va debajo. `lesson_start` lo avisa en `warnings`: pierden junto a qué step
   estaban (visto en el lote). `existing_notes: keep` las deja donde están (no probado).
6. **Alturas:** título, banda de arriba (alto de la columna más alta), 20 px, flujo (se baja entero si
   hace falta); la banda de abajo empieza en `y máx + ícono + 5 + alto del nombre + 25`, con
   `PropsUI.getIconSize()` (Spoon deja elegir otro tamaño) y el nombre medido; el prototipo usa 32 y
   16 fijos.
7. **Verificación:** pintando aparte con `TransPainter` (como el prototipo), ninguna área `NOTE` (+4 px)
   puede cortar un `STEP_ICON`, el rectángulo del nombre, un hop (centro a centro) u otra nota. Si
   corta (no pasó en el lote), esa nota se manda a una columna lateral a la derecha del flujo.

Como el guion completo llega en `lesson_start`, el plan (steps + notas) se calcula **una vez al
principio**: cada step aparece directamente en su lugar final. `build_steps` reacomoda al final; la
lección no. Por eso un paso del guion **no acepta `x`/`y`** (la posición la decide el plan). El
paso 0 (título, intro y reacomodar los steps que ya estaban) se aplica en `lesson_start` y es su
propio Ctrl+Z.

### 4.4 Densidad

- Columna con 1–2 steps: nota completa por step (regla de arriba).
- Columna con 3 o más: la idea es una nota **compacta** (solo el número «N») pegada arriba a la
  derecha del ícono, y el qué/por qué completo en la pestaña y la guía. **No probado:** a la derecha
  del ícono salen los hops en diagonal. WP8 lo mide en el lote con una métrica de legibilidad
  (distancia nota ↔ step ≤ 300 px, además de 0 choques). Si no pasa, en esas columnas no va nota en el
  lienzo (solo pestaña y guía, con el step resaltado), que no necesita ubicación.
- Lección de más de 12 pasos: `lesson_start` avisa y propone partirla en etapas (una nota por etapa,
  «Pasos 1–21: generar los datos de prueba»).

### 4.5 Cómo se reconocen las notas de la lección

`NotePadMeta` no tiene id en el `.ktr`. La fuente de verdad es un **registro en los atributos**
(`AbstractMeta.setAttribute`, van a `<attributes>`), grupo `pentaho_live_lesson`: `schema=1`, `id`,
`title`, `level`, `language`, `notes` y por nota `note.N = huella|tipo|step` (se parte con límite 3:
un nombre de step puede tener `|`). La **huella** es el CRC32 del texto **sin espacios ni saltos de
línea**: no cambia al volver a cortar líneas (`restyle`, `relayout`, `\r\n`). Un `IdentityHashMap`
es solo caché: un Ctrl+Z de `CHANGE_NOTE` repone un clon (§3).

**Los atributos no entran en el undo de Spoon**: si `remove` borrara el registro, un Ctrl+Z
devolvería notas que nadie reconoce. Por eso el registro **solo crece** y se escribe con cada paso
(si el docente guarda a mitad de clase, el `.ktr` dice lo que hay). `remove` borra notas, no el
registro (`purge: true` lo borra a propósito: después un Ctrl+Z devuelve notas «del usuario»).
`hide` guarda el XML en `hidden`; `show` repone solo las que no estén ya (si un Ctrl+Z deshizo el
`hide`, no se duplican; se comparan por la entrada del registro, no por la huella, así un restyle no las
duplica) y deja la copia en `hidden` (un Ctrl+Z del `show` no las pierde); `remove` vacía `hidden`. `list` da
cada entrada como `present`, `hidden`, `missing` (también la que el usuario editó) o `step_missing` (step
renombrado o borrado).

Si el usuario **edita** el texto de una nota de la lección, deja de reconocerse: pasa a ser suya y la
limpieza no la toca (a propósito). Una copia exacta (Ctrl+C/Ctrl+V) sí cuenta como de la lección.

## 5. Ritmo: ¿puede MCP esperar al humano?

MCP es pedido/respuesta: el servidor solo contesta cuando la IA llama. Opciones:

| Opción | Cómo | Veredicto |
|---|---|---|
| A. La IA va paso a paso | `lesson_next` por cada paso; el docente le dice «seguí» en el chat | sirve sin UI, pero obliga a ir y venir entre Spoon y el chat: queda como modo `manual` |
| B. La IA espera un clic | `lesson_status wait_s=60` bloquea hasta que alguien aprieta «Siguiente ▶» (el servidor ya usa un pool de hilos y esperas de hasta 600 s en `run`) | sirve para que Claude acompañe; cada vuelta cuesta tokens: complemento, no base |
| C. El plugin reproduce el guion | `lesson_start` valida y planifica todo y devuelve enseguida; un hilo del plugin avanza con cada clic (o cada `delay_ms`) | **elegida**: cero tokens mientras se da la clase, funciona aunque Claude no mire |
| D. Elicitation de MCP | el servidor le pide al cliente que pregunte al usuario | descartada: el servidor es HTTP sin streaming, y la pregunta aparecería en la terminal, no en Spoon |

**Ritmos** (`pace`): `guided` (por defecto: espera «Siguiente ▶»), `auto` (`delay_ms` 1500–60000,
defecto 6000, con pausa), `manual` (solo avanza con `lesson_next`).

**Un paso de la lección** = un Ctrl+Z (un `UndoGrupo` por paso; `maxUndo` se agranda como en
`TransLote.empezar`): el step aparece con su hop `after` y los hops extra cuyos dos extremos ya
existen, se resuelven las referencias (`<from>`, `send_true_to`) y los cambios de los steps previos;
400 ms después, su nota (y su «¡Ojo!»); y si se pidió, la vista previa. Un step que nombra destinos
que todavía no existen (Filter rows, Switch/case) pospone su vista previa al paso del último
destino. «◀ Atrás» hace un `undoAction` si lo último del historial sigue siendo ese paso; si no,
avisa.

**Hilos:** un hilo `pentaho-live-leccion` por lección; solo los cambios al documento van con
`Docs.ui`, la vista previa corre afuera como en `preview_step`. Espera la señal del botón, de
`lesson_next` o del temporizador (en `auto`, contado desde el fin del paso anterior). Mientras corre
un paso, «Siguiente ▶» se deshabilita y los clics de más se descartan (un `Semaphore` que acumula
permisos avanzaría tres de golpe). `lesson_status wait_s` espera en un monitor que se notifica en
cada cambio de estado.

**Desincronización:** antes de cada paso se verifica que los steps y notas anteriores sigan en el
documento (Ctrl+Z, borrar, mover). Si no: `out_of_sync`, con «Retomar» (re-planifica desde lo que
hay) o «Terminar». `restart` y `goto` hacia atrás solo deshacen si el tope del historial son pasos de
la lección. Transformación cerrada: `stopped`, motivo `document_closed` (como `seguirAbierta`). Cada
paso trae la transformación al frente (`Docs.activar`, también desde la perspectiva Informes).

## 6. Cómo se ve en Spoon

Una pestaña **«Lección»** en el panel de resultados de la transformación (`extraViewTabFolder`, junto a
Logging, Métricas y Preview data), que se abre sola con `lesson_start`. Se elige ese lugar y no una
perspectiva porque el lienzo tiene que seguir a la vista.

```
+ Logging | Historial | Métricas | Preview data | Lección -------------------------------+
| Cada venta con su cliente · principiante · paso 3 de 4                    [A-] [A+]   |
| +- Pasos -----------------+ +- Ahora ---------------------------+ +- Filas (5) -----+ |
| | v 1 Leer ventas         | | 3 · Buscar cliente (Stream lookup) | | venta_id cliente | |
| | v 2 Leer clientes       | | Qué: … / Por qué: …                | | 101      Ana P.  | |
| | > 3 Buscar cliente      | | ¡Ojo! <from> = nombre exacto …     | | 108  DESCONOCIDO | |
| | o 4 Escribir ventas …   | | Glosario: un BUSCARV en memoria    | |                  | |
| +-------------------------+ +------------------------------------+ +------------------+ |
| [◀ Atrás] [Pausa] [Siguiente ▶]          [Ocultar notas] [Guía…] [Terminar]          |
+--------------------------------------------------------------------------------------+
```

- «A-/A+» cambia la fuente de la pestaña (proyector). **Implementado así:** solo la pestaña; las notas del
  lienzo crecen con `font_size` de `lesson_start` o `lesson_notes restyle` (un restyle a mitad de clase
  re-ubicaría notas de pasos que aún no existen y dejaría un Ctrl+Z encima que «◀ Atrás» rechaza).
- Las filas se muestran en una tabla propia de la pestaña (no se toca la de Preview data, que
  pisan las ejecuciones). La API pública `addPreviewData` queda como alternativa.
- **Abrir:** `if (!isExecutionResultsPaneVisible()) showExecutionResults()` (es un toggle: con el
  panel visible lo cierra).
- **Si el usuario oculta el panel**, `disposeExtraView` desecha la pestaña y el ritmo `guided` queda
  sin botón. Un `DisposeListener` pone la lección en `paused` (motivo `panel_hidden`) y, al volver a
  abrir el panel, un `@SpoonUiExtenderPlugin` para `TransGraph` + `loadTab` repone la pestaña (lo
  llama `addAllTabs`; idempotente). Mismo jar, como el `@SpoonPlugin` de Informes; si el escaneo no
  lo levanta, registro en `onStart`. A probar en el esqueleto de WP4.
  **Resultado de WP4:** Spoon 9.4 nunca registra `SpoonUiExtenderPluginType`, así que `loadTab` no llega.
  `LeccionPanel.vigilar` comprueba cada 500 ms, solo mientras el panel está oculto, si se volvió a abrir y
  repone la pestaña; la lección se reanuda si la pausa era solo `panel_hidden`. El extensor queda por si
  otro Spoon sí registra el tipo (añadir la pestaña dos veces no hace nada).
- **Perspectiva «Reports» (0.5.0, de otro equipo):** no se toca su código en este diseño. Contrato
  `LessonHandout` (JSON: título, nivel, idioma, pasos `{n, step, type, what, why, watch_out[],
  glossary, preview{fields, rows}}`, PNG del lienzo con notas) que un recolector `SecLeccion` pasa a
  una `Seccion` `lesson` de su modelo (`Parrafo`, `Tabla`, `Imagen`), exportable con `build_report`
  (§9). Dónde se registra la sección (su lista de `SeccionId`) se acuerda con ellos.
- Sin menú propio en la 0.6.0. Reproducir una lección guardada sin IA (guion en un atributo) queda
  para una fase 2.

## 7. Contenido: niveles, idiomas, glosario y errores

| | Principiante | Intermedio |
|---|---|---|
| Nota de paso | qué + por qué en palabras simples, una idea | qué + por qué + cómo (los tags clave: `<from>`, orden, claves) |
| Analogías | Excel/SQL («un BUSCARV», «un GROUP BY») | alternativas (Stream lookup vs Merge join vs Database lookup) |
| ¡Ojo! con `watch_out: "auto"` | solo errores silenciosos (sección D del manual); los C que el paso provoca típicamente van escritos a mano en el guion (L1) | C y D; rendimiento y memoria como «Tip» |
| Vista previa | después de cada step (5 filas) | solo en los steps clave |
| Preguntas | — | nota «¿Pregunta?» antes del paso siguiente (opcional) |

**Idiomas:** `en`, `es`, `pt`. Claude escribe el qué/por qué en el idioma pedido; las etiquetas y el
glosario salen de archivos.

**Glosario** (`claude/skills/pentaho-etl/catalogo/glosario-clase.json`, se empaqueta en el jar como el
catálogo): por id de step `{what, analogy, watch_out: ["D3", …]}` en en/es/pt, para los ~45 steps de
`steps-comunes.md` (la primera frase da el `what`, el párrafo «Pitfalls» los `watch_out`). Se genera
una vez (traducción revisada) con un script nuevo en `tools/generador-catalogo/` que además valida
que cada id exista en `steps.json` y cada código en `03-errores-comunes.md`. Se expone como recurso
`pentaho://glossary/{lang}/{id}` para clientes sin la skill, y lo usa `watch_out: "auto"`.

**Errores por tipo de step** (de `03-errores-comunes.md`, todos provocados y verificados):

| Step | ¡Ojo! |
|---|---|
| `CsvInput`, `TextFileInput` | C13 archivo inexistente, C14 variable inexistente en la ruta, C15 ruta relativa sin variable, C16 formato de fecha, C17 / D3 decimal |
| `TextFileOutput` | C6 campo inexistente, C21 carpeta inexistente, D8 / C23 campos sin declarar |
| `FilterRows` | C5 campo inexistente, C20 `send_true_to` sin hop, D9 variables en valores |
| `SelectValues` / `SortRows` | C4 / C7 campo inexistente |
| `StreamLookup` | C18 `<from>` que no coincide |
| `MergeJoin` | C19 `step2` sin hop, D6 entradas sin ordenar |
| `GroupBy` | D5 sin ordenar antes |
| `Calculator` | D4 Integer × Number redondea |
| `RowGenerator`, `Constant` | C12 valor inválido, D9 variables |
| cualquiera | D1 nombres repetidos, D2 hop circular, D7 hop deshabilitado; con 2 entradas, C22 estructuras distintas |

Punto de contacto con el **linter** (0.6.0): si está, `watch_out: "auto"` suma sus avisos sobre el step
recién creado.

## 8. Herramientas MCP

Todas toman `document` opcional (id o nombre), como las demás. Solo transformaciones (las cinco).

**`lesson_start`** — valida y planifica todo; si algo está mal no toca nada (como `build_steps`).

```json
{ "title": "Cada venta con su cliente", "level": "beginner", "language": "es",
  "pace": "guided", "delay_ms": 6000, "font_size": -1, "existing_notes": "stack_top",
  "allow_write": false,
  "intro": "Vamos a unir dos CSV sin base de datos.",
  "steps": [
    { "type": "CsvInput", "name": "Leer ventas", "config_xml": "…",
      "what": "lee datos/ventas.csv, una fila por venta", "why": "es el flujo principal",
      "watch_out": "auto", "preview_rows": 5 },
    { "step": "Leer clientes", "what": "…", "why": "…" },
    { "type": "StreamLookup", "name": "Buscar cliente", "after": "Leer ventas", "config_xml": "…",
      "what": "…", "why": "…", "tip": "…" } ],
  "hops": [ { "from": "Leer clientes", "to": "Buscar cliente" } ],
  "outro": "Ejercicio: cambiá el valor por defecto y volvé a correr." }
```

Por paso valen los campos de `build_steps` (`type`, `name`, `config_xml`, `after`, `output_mode`;
**no** `x`/`y`, §4.3) **o** `step` (un step que ya existe: solo se anota). Cada hop de `hops` se crea
en el paso en que aparece su último extremo. `watch_out`: texto, `"auto"` (glosario) o nada.
`preview_rows` 0–20; en un step de salida (o con una salida antes) exige `allow_write: true` (de
toda la lección), porque la vista previa escribe. Si algún paso pide vista previa y una ruta usa
`${Internal.Entry.Current.Directory}`, el documento **tiene que estar guardado** en una carpeta
donde existan esos archivos (las lecciones listas leen `datos/*.csv`): si no, error sin tocar nada.
Sin archivo, esa variable no apunta a ningún lado (lo mismo que ya avisa `run` con `file_warning`).

```json
{ "lesson": "L1", "document": "T1", "state": "waiting_next", "position": 0, "beats": 5,
  "plan": [ { "n": 1, "step": "Leer ventas", "action": "build", "note_band": "above", "column": 0 } ],
  "warnings": [], "next": "The user clicks 'Next' in the Lesson tab; lesson_status wait_s=60 follows it." }
```

**`lesson_next`** `{lesson?}` — avanza un paso (en cualquier ritmo). Devuelve
`{position, state, done: ["step 'Buscar cliente' added", "note 3 added", "watch-out added"],
preview: {fields, rows}}` para que Claude comente las filas.

**`lesson_status`** `{lesson?, wait_s: 0–120, since?: position}` — con `wait_s` espera hasta que cambie
algo. `{state, reason?, position, beats, current: {n, step, note}, clicks: {count, last_at}, changed}`.
Estados: `ready`, `waiting_next`, `playing`, `paused`, `out_of_sync`, `done`, `stopped`; `reason`:
`panel_hidden`, `document_closed`, `user_undo`…

**`lesson_control`** `{lesson?, action: pause|resume|back|goto|restart|stop, to?: n}` — `back` es un
Ctrl+Z del último paso; `restart` deshace todo lo de la lección y vuelve al paso 0. Devuelve lo mismo
que `lesson_status`.

**`lesson_notes`** `{document?, action, …}` — sobre las notas de la lección (reconocidas por §4.5):

| action | Hace | Undo |
|---|---|---|
| `list` | entradas del registro: n, tipo, step, `present`/`hidden`/`missing`/`step_missing` (una nota editada por el usuario figura como `missing`) | — |
| `add` | `{step, kind, text}` (kind: `watch_out`, `tip` o `question`): una nota suelta ubicada con la regla (a diferencia de `add_note`: estilo, regla y registro) | 1 Ctrl+Z |
| `hide` | las saca y guarda su XML en el atributo `hidden` (viaja en el `.ktr`; no es secreto) | 1 Ctrl+Z |
| `show` | repone desde `hidden` las que no estén ya; vacía `hidden` | 1 Ctrl+Z |
| `remove` | las borra; el registro queda (`purge: true` lo borra, §4.5) | 1 Ctrl+Z |
| `restyle` | `{font_size}` para proyector: vuelve a cortar las líneas y a ubicar (la huella no cambia) | 1 Ctrl+Z |
| `relayout` | vuelve a aplicar la regla (otro equipo, otras fuentes) | 1 Ctrl+Z |

Respuesta: `{notes: 7, kept_user_notes: 1, undo: "One Ctrl+Z in Spoon restores them"}`.

**Con las herramientas que ya existen:** `arrange_canvas` y `build_steps` (`arrange` es el default)
mueven steps a columnas de 170 y **no mueven notas**: tras una lección dejarían las notas lejos o
encima de los steps. Con notas de lección en el documento, reacomodan con la regla de clase (steps y
notas, mismo Ctrl+Z) y lo dicen. `get_document` marca las notas de la lección (`"lesson": n`) en vez
de devolver todo su texto; `generate_report` las sigue listando como cualquier nota. La skill suma
`manual/08-modo-clase.md` (buen qué/por qué, largos, qué ritmo usar) y una línea en `SKILL.md`.

## 9. Limpieza y guía para entregar

- **Limpiar:** `lesson_notes remove` (o «Terminar» → «Quitar notas» en la pestaña). Una sola acción
  `DELETE_NOTE` con todas las notas, índices ascendentes, borradas de atrás para adelante (así lo
  hace el prototipo y así las repone Spoon).
- **Ocultar** para el ejercicio y **mostrar** para corregir: §8.
- **Guía:** `build_report sections=["lesson"] formats=["md","pdf"] language=es` (Informes, 0.5.0) arma,
  en orden de la lección: título y objetivo, imagen del lienzo con las notas (`ReportImagen`), por
  paso «N · Nombre (Tipo)», qué, por qué, ¡Ojo! con el código del manual, glosario del tipo y las
  filas de la vista previa capturadas en clase; al final el ejercicio (`outro`) y la tabla de errores
  comunes de los tipos usados. Las filas viven en memoria: tras reiniciar Spoon la guía sale de las
  notas y el registro, sin filas. **No** se agrega `lesson` a `generate_report`: en la 0.6 pasa al
  motor de Informes (su diseño, §1.5) y tocar `ReportMotor` chocaría con esa migración. Si Informes
  se atrasa, plan B: `lesson_notes action=handout path=…md` con un escritor Markdown propio.

## 10. Modelo de datos

```
Leccion                    (memoria; una activa por documento)
  id "L1", docId, titulo, nivel, idioma, ritmo GUIADO|AUTO|MANUAL, delayMs, fuente
  pasos: List<Paso>, posicion, estado
  plan: Map<String, Point> steps; Map<Paso, Rect> notas        (calculado en lesson_start)
  notas: IdentityHashMap<NotePadMeta, NotaLeccion>   (solo caché; se rehace desde las huellas)
  filas: Map<String, Vista>   (campos + filas por step, para la pestaña y la guía; no van al .ktr)
  clics: contador + último instante; motivoPausa (panel_hidden…)
Paso: n, accion CREAR|ANOTAR|INTRO|CIERRE, TransLote.PasoSpec (sin x/y) o nombre existente,
      hopsExtra (los de `hops` que cierran en este paso), textos {que, porque, ojo[], tip, pregunta},
      filasPreview, ultimaAccionUndo (TransAction)
NotaLeccion: n, tipo TITULO|PASO|OJO|TIP|PREGUNTA, step, huella (CRC32 del texto sin espacios)

.ktr  <attributes><group><name>pentaho_live_lesson</name>
        schema=1 · id · title · level · language · notes=N · note.K=huella|tipo|step (solo crece)
        · hidden=<notepads>… (vacío después de show)
```

## 11. Tres lecciones listas (Big Data 1)

Guiones en `claude/skills/pentaho-etl/lecciones/*.json` (el formato de `lesson_start`, en es/en/pt),
sobre los ejemplos verificados de `manual/ejemplos/` y sus datos. Cada guion construye la
transformación desde cero en un `.ktr` **guardado** en una carpeta con `datos/` (la skill copia
`manual/ejemplos/datos/` ahí antes de `lesson_start`, §8); el ejemplo «base» es la solución.
Números verificados contra los datos en la revisión.

**L1 · Primer ETL: filtrar clientes** — principiante, ~20 min, base `02_filtrar_clientes.ktr`.
0. Intro: qué es una transformación, filas, hops. 1. *Leer clientes* (CSV file input): campos por
posición y tipos; filas (8). ¡Ojo! C15 ruta relativa. 2. *Activos con saldo* (Filter rows):
`saldo >= …` y `activo = …`; ramas verdadera/falsa. ¡Ojo! C20 `send_true_to` + hop. 3. *Elegir
columnas* (Select values): renombrar y formato. ¡Ojo! C4. 4. *Escribir filtrados* (Text file
output): filas (ids 1, 5, 7, 8). ¡Ojo! D8. 5. *Descartados* (Dummy): la rama falsa. Cierre: `run
mode=visible` y leer I/O/R/W/E. Ejercicio: cambiar el saldo mínimo y predecir cuántas filas salen.

**L2 · Agregar sin mentir: Sort rows + Group by** — intermedio, ~25 min, base `03_ventas_por_producto.ktr`.
1. *Leer ventas*. ¡Ojo! D3 decimal con Windows en español. 2. *Calcular importe* (Calculator):
cantidad × precio. ¡Ojo! D4: el campo decimal va en `field_a`. 3. *Ordenar por producto* (Sort
rows). Tip: memoria y archivos temporales. 4. *Totales por producto* (Group by: SUM, COUNT_ALL,
AVERAGE, MAX); filas: Café 20.00, Medialuna 21.60, Tostado 9.50, Té 14.40. ¡Ojo! D5. 5. *Escribir
totales*. Pregunta: «¿qué pasa si el Group by no recibe las filas ordenadas?» → el docente saltea el
Sort: `connect_steps` Calcular importe → Totales por producto (`output_mode=copy`) +
`set_hop_enabled` Ordenar por producto → Totales `false` + `preview_step` Totales: 10 «grupos» (Pan,
revisión). Vuelve atrás con dos Ctrl+Z. **No** sirve solo deshabilitar el hop del Sort: Group by se
queda sin entrada y falla con «Error desconocido» (exit 1). Alternativa: Memory group by.

**L3 · Unir fuentes: Stream lookup vs Merge join** — intermedio, ~30 min, bases
`04_ventas_con_cliente.ktr` y `05_clientes_con_ventas.ktr`. Parte A (lookup, la del prototipo): leer
ventas y clientes, *Buscar cliente* con `DESCONOCIDO` por defecto (venta 108). ¡Ojo! C18. Tip: el
flujo de búsqueda va entero a memoria; no multiplica filas. Parte B (join, nueva transformación):
*Ordenar clientes* y *Ordenar ventas*, *Unir clientes y ventas* (LEFT OUTER), *Quitar clave
duplicada* (`cliente_id_1`), *Escribir*: 10 filas, Fabián sin ventas. ¡Ojo! C19 y D6. Cierre: tabla
comparativa (memoria, orden, cardinalidad, base de datos → Database lookup).

Siguiente candidata: jobs y parámetros (`06`, `10`, `11`), cuando haya lecciones de jobs.

## 12. Riesgos

| Riesgo | Mitigación |
|---|---|
| Lienzo denso ilegible aunque no haya choques (visto en el lote) | nota compacta (no probada) o sin nota en el lienzo desde 3 steps por columna; tope de 12 pasos; etapas |
| Fuentes, escala o tamaño de ícono distintos en otra máquina: las notas crecen y se pisan | medir siempre con el GC real y `getIconSize()`; 12 px entre notas; `lesson_notes relayout` |
| Notas sin id: una editada deja de reconocerse | por diseño (pasa a ser del usuario); `list` lo muestra |
| Los atributos no entran en el undo: registro y lienzo se desfasan tras un Ctrl+Z | registro que solo crece, `show` sin duplicar, huella que no depende del corte de líneas (§4.5) |
| Ctrl+Z del usuario a mitad de clase | verificación antes de cada paso; estado `out_of_sync` y «Retomar» |
| El usuario oculta el panel de resultados y el botón desaparece | `paused` + gancho `loadTab` que repone la pestaña; probar en Spoon real primero (WP4) |
| `arrange_canvas` / `build_steps` después de la lección dejan las notas lejos de sus steps | reacomodan con la regla de clase en documentos con lección (§8) |
| Esperas largas y timeouts del cliente MCP | ritmo `guided` no necesita a la IA; `wait_s` ≤ 120 y se repite |
| Vista previa de una salida escribe archivos o tablas | `allow_write` explícito, como advierte `preview_step` |
| Reacomodar mueve steps y notas del usuario (las que rotulan un step pierden la referencia) | un Ctrl+Z; aviso en `warnings`; `existing_notes: keep`; anotar sin reacomodar (no probado) |
| «Ocultar» se toma como secreto | la descripción de `hide` y el manual lo dicen; para repartir, `remove` en una copia |
| Traducciones del glosario | se generan una vez y se revisan; el texto en clase lo escribe Claude |
| Más herramientas (+5, 62 con Informes) = más contexto | descripciones cortas; el detalle va a `08-modo-clase.md` |

## 13. Paquetes de trabajo

| WP | Qué | Archivos | Depende de |
|---|---|---|---|
| 1 | Regla de ubicación, lógica pura: `col[]` de `TransLayout.calcular` → x de 240, bandas, densidad, notas previas, corte de texto con un `Medidor` (interfaz; en Spoon un GC, en pruebas el SWTGC), tamaño de ícono de `PropsUI` | nuevo `LeccionLayout.java` (`TransLayout.java` no cambia) | — |
| 2 | Notas de lección: estilos, registro y huellas (§4.5), add/list/hide/show/remove/restyle/relayout con una acción de undo; `lesson_notes`; `arrange_canvas`/`build_steps` reacomodan con la regla de clase si hay lección; `get_document` marca las notas | nuevos `LeccionNotas.java`, `LessonTools.java`; `McpLifecycleListener.java` (registrar e `INSTRUCCIONES`); `TransTools.java` (`arrange_canvas`); `TransLote.java` (`cerrar`); `DocumentTools.java` (`get_document`) | 1 |
| 3 | Motor: guion, validación con `TransLote`, plan previo, hilo, ritmos, clics sin acumular, desincronización, documento cerrado; `lesson_start/next/status/control` | nuevos `Leccion.java`, `LeccionMotor.java`; `LessonTools.java`; `TransLote.java` (un paso con su propio `UndoGrupo`: hops extra, `resolverReferencias` y cambios de los previos **por paso**, hoy solo en `cerrar`) | 1, 2 |
| 4 | Pestaña «Lección» en `extraViewTabFolder`: lista, texto actual, tabla de filas, botones, A-/A+, textos en/es/pt (si ya está `Rotulos` de Informes, un área `leccion` de ese mecanismo); `DisposeListener` → pausa; extensor `loadTab`. **Probar primero en Spoon real** con un esqueleto | nuevos `LeccionPanel.java`, `LeccionTextos.java`, `LeccionTabExtender.java` (`@SpoonUiExtenderPlugin`) | 3 |
| 5 | Glosario y errores por step en en/es/pt, generador y recurso MCP | `claude/skills/pentaho-etl/catalogo/glosario-clase.json`, `tools/generador-catalogo/glosario.py`, `McpResources.java`, `spoon-plugin/build.ps1` (hace falta: copia una lista fija de archivos) | — |
| 6 | Guía: recolector `SecLeccion` → `Seccion` `lesson` del modelo de Informes + contrato `LessonHandout`; plan B `lesson_notes action=handout` en Markdown. `ReportTools`/`ReportMotor` no se tocan | nuevos `LeccionGuia.java`, `SecLeccion.java`; registro de la sección, a acordar con Informes | 2, 3, Informes 0.5.0 |
| 7 | Skill y lecciones listas: manual, guiones L1–L3 probados con Pan y con la prueba de solapamientos; la skill copia `datos/` junto al `.ktr` | `claude/skills/pentaho-etl/manual/08-modo-clase.md`, `SKILL.md`, `lecciones/*.json`. La versión **no** se sube acá: `VERSION` manda y `build.ps1` falla si `plugin.json` no coincide; lo hace el release | 3, 5 |
| 8 | Pruebas headless (no van al jar): ubicación sobre los samples de PDI y los ejemplos (0 choques **y** distancia nota ↔ step), nota compacta, íconos de 48 px, undo de notas y desfase con el registro, ocultar/mostrar, Pan sobre los `.ktr` anotados | `tools/pruebas-clase/` (a partir de `ProtoModoClase` y `ProtoLote`) | 1, 2 |

Archivos compartidos con otros equipos: `McpLifecycleListener.java` y `DocumentTools.java` también
los toca WP10 de Informes, `build.ps1` el instalador y `SKILL.md` varios: cambios chicos y después de
que ellos mergeen.

Orden sugerido: 1 → 2 → 8 (ya se puede anotar a mano) → 4 (esqueleto en Spoon real, para confirmar la
pestaña, el botón y `loadTab`) → 3 → 5 → 6 → 7.

## 14. El prototipo

En el scratchpad de la sesión, `v06/modo-clase/`: `src/ProtoModoClase.java` (anota, guarda, relee,
verifica solapamientos, limpia, oculta/muestra), `src/ProtoLote.java` (los 196 `.ktr`), `ktr/`, `out/`
(`modo-clase.png`, `lote-mas-grande.png`, `proto.log`, `pan.log`, `lote.log`). Classpath de PDI más
las clases del plugin (`javac --release 8`), `--add-opens` de Java 21 y `KETTLE_HOME` propio. La
revisión dejó en `v06/clase-revision/` Pan sobre el anotado y el oculto, las variantes de L2
(`ktr/03a…`, `03b…`, `03c_sin_sort.ktr`) y los `javap -c` de `TransGraph` y
`SpoonTransformationDelegate`.
