# Pruebas del modo clase (WP8)

Pruebas headless de la regla de ubicación y de las notas de la lección (`docs/modo-clase-diseno.md`, §4.3–§4.5
y paquete 8 de §13). **No van al jar**: se compilan contra un jar con las clases del plugin y corren con
`KettleEnvironment.init` + un `Display` suelto + `PropsUI.init`, sin Spoon, igual que el prototipo
`ProtoModoClase` / `ProtoLote` del que salen.

## Cómo se corre

```powershell
powershell -ExecutionPolicy Bypass -File tools\pruebas-clase\correr.ps1 -PdiHome "<PDI 9.4>\data-integration" `
    -Salida "<carpeta temporal>" [-Jar <jar del plugin>] [-ArbolDeTrabajo] [-SinPan]
```

- Sin `-Jar`, compila `spoon-plugin/src/main` del **último commit** (`git archive HEAD`, para que no lo rompa trabajo a
  medias en el mismo árbol) y arma `<Salida>\pentaho-mcp-clases.jar`. No corre `build.ps1`. `-ArbolDeTrabajo`
  compila los archivos tal como están.
- Entorno aislado: `KETTLE_HOME`, `user.home` y metastore propios dentro de `-Salida`. Lee
  `samples\transformations` de PDI y `claude/skills/pentaho-etl/manual/ejemplos` sin escribir en ellos: los ejemplos
  se copian (con `datos/`) a `<Salida>\ejemplos`, y ahí quedan `*_clase.ktr` (anotada) y `*_oculta.ktr` (notas
  ocultas) y la `salida/` de Pan.
- Espera a tener 1,5 GB de RAM libres antes de cada JVM (javac, el runner y cada Pan) y nunca lanza dos a la vez.
- Código de salida: 0 si no hay choques, fallas funcionales ni diferencias en Pan; 1 si las hay; 3 si no compila.
  La métrica de densidad (distancia nota–step) se informa aparte y no cambia el código (ver «Resultados»).
- Deja en `-Salida`: `runner.log`, `lote.tsv` (una fila por archivo y variante), `pan.tsv`, `pan.log` y `png\`
  (los ejemplos anotados, el sample `Stream Lookup` y el más grande de cada variante).

## Qué mide

**A. Lote** (`PruebasClase.lote`). A cada `.ktr` le arma una lección como lo haría `lesson_start`: título, una nota de
paso por step con el qué/por qué del glosario de clase (`pentaho://glossary`, es) y algunos «¡Ojo!» y «¿Pregunta?».
La ubica con `LeccionLayout.calcular` (notas previas apiladas arriba a la izquierda, `stack_top`), la aplica (steps,
previas y notas nuevas con `LeccionNotas.estilizar` y el registro) y **pinta con el `TransPainter` real**. Sobre las
áreas que devuelve el painter (no sobre las que calcula la regla) cuenta:

- **choques**: nota de la lección (+4 px) contra iconos (`STEP_ICON`), nombres (`getNamePosition` + fuente `GRAPH`),
  hops (de centro a centro) y cualquier otra nota; y nota previa contra iconos, nombres y hops;
- **distancia** nota–icono de su step (legible si ≤ 300 px, §4.4);
- **dibujado fuera de lo reservado**: el polígono que pinta `drawNote` tiene que entrar en el rectángulo de la regla;
- **relayout** (solo la variante por defecto): `LeccionNotas.reacomodar` justo después de la lección; cuántos steps y
  notas mueve y si quedan choques.

Tres variantes: iconos de 32 con nota compacta en las columnas densas (lo de hoy), iconos de **48**, y 32 **sin
nota** en las columnas densas (`Densidad.SIN_NOTA`).

**B. Funcionales** (`PruebasClase.funcionales`), sobre cada ejemplo de la skill con una nota «del usuario»:

1. la lección entera es **un Ctrl+Z** (quita todas las notas, devuelve steps y la nota del usuario, el registro queda
   y las da `missing`) y **un Ctrl+Y** (todo vuelve, `present`); el undo/redo se simula como Spoon 9.4
   (`getNextAlso`, ver `UndoGrupo`);
2. guardar y releer: mismas notas, todas reconocidas por huella, idioma en el registro, 0 choques;
3. relayout con fuente 14 (un step corrido 37 px) y su Ctrl+Z (`CHANGE_NOTE` repone clones): siguen reconocidas;
4. **desfase con el registro**: el docente edita una nota (pasa a ser suya, `missing`, el registro no cambia),
   `remove` la respeta y su Ctrl+Z devuelve las demás reconocidas; un step renombrado deja sus entradas en
   `step_missing` y `relayout` lo avisa sin romper;
5. ocultar → guardar → releer (solo quedan las del usuario, `list` las da `hidden`) → mostrar (vuelven todas); y
   mostrar tras un Ctrl+Z del ocultar no duplica.

**C. Pan** (`correr.ps1`): cada ejemplo original, su `_clase.ktr` y su `_oculta.ktr` tienen que terminar igual: código
de salida 0, mismas filas escritas y errores por step (`Finished processing … W= E=`) y mismos archivos en `salida/`
(hash de los de texto; los binarios, como el `.sqlite` vacío del 07, solo que existan).

## Resultados (22-oct-2026, plugin `f8232b8` = WP1 + WP2 + WP5)

PDI 9.4.0.0-343, Java 21, Windows 11, fuente de notas Segoe UI 9 pt, icono de Spoon 32 px, glosario de 50 steps.

### A. Lote: 180 `.ktr` (173 samples de PDI + 7 ejemplos de la skill)

179 anotados, 0 que no cargan, 1 rechazado: `DistinctCount.ktr` tiene «Generate Row v1a» y «Generate Row v1a »
(solo cambia un espacio al final, PDI los acepta como distintos) y `LeccionLayout` lo rechaza con *Duplicate step
name* porque su clave recorta espacios.

| Variante | Notas en el lienzo | Compactas | Fuera del lienzo | Choques (lección / previas) | Dibujado fuera de lo reservado | Notas a más de 300 px | Distancia máx. |
|---|---|---|---|---|---|---|---|
| iconos 32, compacta (hoy) | 1478 | 160 | 236 | **0 / 0** | 0 | **14** (7 archivos) | 2030 px |
| iconos 48, compacta | 1481 | 163 | 233 | **0 / 0** | 0 | 14 (7 archivos) | 2030 px |
| iconos 32, sin nota en columnas densas | 1318 | 0 | 396 | 0 / 0 | 0 | 14 (7 archivos) | 2030 px |
| iconos 32, **regla propuesta «acercar»** | 1478 | 160 | 236 | 0 / 0 | 0 | **4** (3 archivos) | 642 px |
| iconos 48, regla propuesta «acercar» | 1481 | 163 | 233 | 0 / 0 | 0 | 4 (3 archivos) | 642 px |

Notas completas por distancia a su step (iconos 32): 1–100 px: 658 · 101–200: 395 · 201–300: 70 · más de 300: 14.
Con «acercar»: 667 · 390 · 76 · 4. Las compactas quedan a 6 px de su icono.

**Veredicto de densidad (§4.4).** Cero choques en las cinco variantes, medidos con el `TransPainter` real, y lo
dibujado siempre entra en lo reservado. La nota compacta pasa la métrica: 0 choques, pegada a su icono. Las
compactas que cortarían un hop salen del lienzo por la regla 7, como estaba previsto. **Lo que no pasa es la
distancia de las notas completas**: 14 notas a más de 300 px, iguales con compacta y sin nota. O sea, no vienen de
las columnas densas, sino de que **las bandas son globales**. La banda de arriba va sobre el step más alto de *todo*
el flujo y la de abajo bajo el más bajo, así que la nota de un step que está arriba en una columna corta, mientras
otra columna es alta, queda lejos (peor caso: `Data Validator - all usecases…`, 2030 px). Quitar la nota compacta
(`SIN_NOTA`) no lo arregla, así que la densidad por defecto puede quedar en `COMPACTA`.

**Cambio de regla propuesto** (no está en `LeccionLayout`; lo simula `PruebasClase.acercar`, variante `*_acercar`):
*si las notas completas de un step quedarían a más de 300 px de su icono, se apilan junto a su step en su columna:
abajo, bajo su nombre (icono + 5 + alto del nombre + 25); arriba, 20 px sobre el icono. Solo si ahí no cortan
iconos, nombres, hops, notas previas ni otras notas (+4 px); si cortan, se quedan en la banda con el aviso de hoy.*
Movió 17 notas y bajó de 14 a 4 las que quedan lejos, con 0 choques. Quedan lejos `Rounding.ktr` (422 px),
`Value Mapper - Same and new field.ktr` (2 notas, 642 px) y `XML Input Stream (StAX) Test 5…` (312 px); en los tres
casos el lugar junto al step lo cruza un hop. Para esas, mantener el aviso de hoy (la lateral queda aún más lejos).

**Relayout justo después de la lección** (`lesson_notes relayout`, iconos 32): no es estable. Cambian 101 de 179
archivos (569 steps y 759 notas movidos) y 7 quedan con choques (36 en total). Hay dos causas, y ninguna es de
la regla de `lesson_start`:

1. Los 89 archivos con notas previas: la lección las apila arriba (`stack_top`), pero `reacomodar` usa `keep`, y
   en ese modo `LeccionLayout` empieza el título y el flujo en y = 20 sin dejar lugar a las notas que hay ahí. El
   flujo sube y queda debajo de las notas apiladas, que pisan iconos, nombres y hops (p. ej. `VFS Configuration
   Sample.ktr`, `Java Filter - …`). Arreglo: guardar el modo en el registro (`existing_notes=stack_top`) y que el
   relayout lo repita, o que `keep` trate las notas previas como obstáculo del flujo.
2. Los 12 archivos sin notas previas que cambian: el registro guarda la nota compacta como `step`. Las compactas
   que salieron del lienzo no se registran, y la columna deja de contar como densa: el relayout vuelve a ubicar el
   «3» como nota completa de 220 px. Además, las compactas que siguen siendo densas se dejan donde estaban mientras
   los steps se mueven, y pisan nombres (`Switch-Case - contains example.ktr`, `XML Join - …`). Arreglo: marcar la
   compacta en el registro (o guardar qué steps son densos), calcular la densidad con los pasos del registro y no
   con las notas presentes, y que el relayout también ubique las compactas.

### B. Funcionales: 7 ejemplos, 0 fallas

Para cada ejemplo pasan todos los chequeos de «Qué mide» B. Un Ctrl+Z quita la lección entera y deja el historial
vacío; el Ctrl+Y la repone con las posiciones del plan. Las notas se reconocen tras guardar y releer, tras un
relayout con fuente 14 y tras su Ctrl+Z, que repone clones. La edición de una nota, el step renombrado, remove con
su Ctrl+Z, hide, guardar, releer y show, y show tras el Ctrl+Z del hide también pasan.

### C. Pan: 21 corridas

| Ejemplo | original | `_clase` | `_oculta` | Filas por step |
|---|---|---|---|---|
| 01_generar_constantes | 0 | 0 | 0 | iguales (3 steps, W=5) |
| 02_filtrar_clientes | 0 | 0 | 0 | iguales (5 steps) |
| 03_ventas_por_producto | 0 | 0 | 0 | iguales (5 steps) |
| 04_ventas_con_cliente | 0 | 0 | 0 | iguales (4 steps, W=10) |
| 05_clientes_con_ventas | 0 | 0 | 0 | iguales (7 steps) |
| 06_errores_y_parametros | 0 | 0 | 0 | iguales (`Leer clientes con errores` W=3 E=3) |
| 07_conexion_sqlite | 0 | 0 | 0 | iguales (`Consultar SQLite` W=3) |

Mismos archivos en `salida/` en las tres versiones de cada ejemplo. En la primera corrida el 07 dio exit 3 en las
tres versiones, original incluido, porque `correr.ps1` borraba `salida/` antes de cada Pan y la conexión SQLite abre
`salida/demo.sqlite`. Ahora la carpeta se crea vacía, y el 07 se volvió a correr (`-SoloPan -PanFiltro 07*`): exit 0
en las tres. En esa primera corrida la comparación de filas por step no encontraba los mensajes porque Pan los
escribe en el idioma de Windows («Procesamiento finalizado»); se comparó a mano sobre `pan.log` y la expresión ya
acepta cualquier idioma.

### Otros hallazgos para WP3/WP4

- Los títulos de los `watch_out` del glosario salen de los encabezados de `03-errores-comunes.md`, que están en
  inglés: un «¡Ojo!» automático en una lección en español quedaría «¡Ojo! (paso 4) Nonexistent field in Sort rows».
- Con iconos de 48 px el resultado es el mismo que con 32: 0 choques. El cálculo del alto del nombre y de
  `getNamePosition` coincide con el painter.

## Limitaciones

- Sin Spoon: mide con el GC de SWT y la fuente de notas de `PropsUI` (como el painter), pero no prueba la pestaña,
  el botón ni el Ctrl+Z real (lo simula como `SpoonTransformationDelegate` 9.4, igual que `PruebaLeccionNotas`).
- La lección del lote es sintética: una nota de paso por step en el orden de `getSteps()`, un «¡Ojo!» cada 3 y una
  pregunta cada 5. Una lección real de más de 12 pasos recibe el aviso de partirla en etapas.
- «acercar» es una simulación en el runner: para adoptarla hay que llevarla a `LeccionLayout` (regla 3/6) y repetir
  este lote.
