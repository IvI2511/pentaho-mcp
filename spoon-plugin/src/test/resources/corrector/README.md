# Fixtures del corrector de ejercicios (C0)

Packs, entregas y resultados para las pruebas del corrector (`docs/corrector-diseno.md`). Salen del
prototipo del 20-oct-2026 y de su revisión, más entregas nuevas para los casos del §1, §3 y §7. **Son de
solo lectura**: las pruebas trabajan sobre copias (`CorrectorRecursos.copia()`), porque varias entregas
escriben o leen fuera de su carpeta si se corren sin el sandbox.

```
corrector/
├── casos.json                 estado, motivo y nota esperados de cada entrega (y etiquetas para elegirlas)
├── packs/
│   ├── ventas-por-producto/   captura file, 2 casos (oculto con otro producto), 3 reglas, compuerta en usa_group_by
│   ├── ventas-a-tabla/        captura query sobre plantilla/dw.sqlite, allow_steps TableOutput y ExecSQL
│   ├── ventas-inline.json     variante de un solo .json (copia para alumnos): usa todas las claves del §2.2
│   └── invalidos/             un problema cada uno (ver abajo)
├── entregas/
│   ├── ventas-por-producto/   29 .ktr
│   └── ventas-a-tabla/        5 .ktr
└── resultados/                10 resultados del prototipo pasados a pentaho-live-grade-result/1
```

## Packs

- **ventas-por-producto**: el ejercicio 3 del manual (`manual/ejemplos/03_ventas_por_producto.ktr` es la
  solución). `policy`: `es_ES`, `Europe/Madrid`, `UTF-8`, `timeout_s` 15 y `max_output_mb` 5 (para que las
  pruebas de timeout y de salida desbocada sean cortas). La rúbrica suma 10 y `rule:usa_group_by` tiene
  `requires: ["runs"]`: por eso diego da 0,5 y fede 1,0 (en el prototipo, sin compuerta, 1,5 y 2,0).
- **ventas-a-tabla**: la misma lógica con un Table output a `totales`. **Cambio respecto del prototipo**: zona
  `UTC` y el SQL convierte la fecha con `date(ultima_venta / 1000, 'unixepoch')`, sin `'localtime'`. El
  `'localtime'` de SQLite usa la zona del sistema operativo, no la `-Duser.timezone` de la JVM con la que Kettle
  escribió la medianoche (P13): con el pack en Madrid y la PC en otra zona, las fechas se corrían un día.
- **ventas-inline.json**: captura `step`, `parameters`, `submission_file`, reglas `error_handling` y `lint`,
  `lint:absolute_path` en la rúbrica e `inline` con el enunciado, los datos y el esperado del caso visible.
- **invalidos/** (todos inline, iguales a `ventas-inline.json` salvo una cosa): `ruta_con_puntos`
  (`statement` con `..`), `ruta_absoluta` (`expected` absoluto), `formato_desconocido` (`/9`), `inline_fuera`
  (clave de `inline` con `..`), `rubrica_mala` (`rule:` de una regla que no existe y un check desconocido),
  `sin_casos`, `caso_repetido`.

## Entregas

`casos.json` dice, para cada una, el `status`, el `reason` si se bloquea, la nota, el estado de cada caso, las
pistas que se esperan (ids informales del §6) y si el prototipo lo confirmó (`proven`) o qué dio (`prototype`).
Las notas son las del diseño con la rúbrica de cada pack, no las del prototipo.

| Grupo | Entregas |
|---|---|
| Prototipo (§1) | ana, bruno, carla, diego, elena, fede, gabi, hugo; ines (era ivan_tabla_ok) y juan en ventas-a-tabla |
| Revisión (P14–P20) | t1 … t7. t5 apunta su entidad externa a `file:///C:/Windows/win.ini` |
| Correctas | paula (la solución), mati (rutas de macOS y salida `file:///`), mora (Execute SQL permitido) |
| Filas o valores mal | rodri (filtra de más: faltan filas), quique (salida dibujada: pasa el visible, no el oculto) |
| Formato | sofi (fecha `dd/MM/yyyy`: `date_formats`) |
| Sin salida | tomas (termina en un Dummy) |
| Escribe fuera | ulises (`fileNameInField`), vero (la salida apunta a `../../../esperado/visible`), lara (`ATTACH DATABASE`) |
| Lee fuera | walter (la salida de paula en el `_sandbox` del mismo lote), xime (`http://`), yago (nombre de archivo en un campo) |
| Steps prohibidos | diego (condicional), nico (Execute a process: peligroso), oscar (plugin que no existe) |
| D1 / D2 | pia (dos steps que difieren en mayúsculas), fede |
| Conexiones | t2 (MySQL sin usar: se saca), kevin (MySQL en el Table output: bloquea) |

**No correr sin el sandbox ni la política** las marcadas `never_run` en `casos.json`: ulises escribiría en
`C:/Users/Public`, lara crearía `C:/Users/Public/copia.sqlite`, nico ejecuta `whoami` y xime intentaría una
conexión HTTP. walter, corrida en el mismo lote después de paula, copia su salida y sacaba 7,0 en el prototipo.

Todas cargan con `new TransMeta` salvo t5 (DOCTYPE). oscar carga con el step como `MissingTrans`; pia carga con
4 steps (el duplicado se funde), así que D1 solo se ve en el XML crudo.

## Resultados

`resultados/*.json`: lo que el prototipo escribió para las 10 entregas del §1, convertido al formato del §6
(estados, motivos y claves de este contrato). Las notas y los `checks` son **los del prototipo** (sin
compuerta: diego 1,5 y fede 2,0 con estado `timeout`) y no traen pistas: sirven a C5 para recalcular nota y
pistas sin ejecutar nada, y a C9 para dibujar un resultado real (bruno). Los logs van recortados.

Los generó un script del scratchpad a partir de los archivos del prototipo; si hay que cambiarlos, se editan
a mano.
