# Pack de corrección · Grano de Oro

Este es un **pack de ejercicio** para el corrector de Pentaho Live, hecho a partir de la práctica Grano
de Oro de esta carpeta. El alumno entrega **una transformación** que lee los tres ficheros de la
práctica (`ventas_2026_09.csv`, `productos.xlsx` y `tiendas.json`) y escribe la tabla de **ventas por
tienda y categoría**. El corrector la ejecuta en un proceso aparte con los datos de la práctica y con
un segundo juego de datos que el alumno no ve, compara su salida fila a fila con la esperada y pone
la nota con una rúbrica. La salida esperada del caso visible es exactamente la tabla «KPI: ventas por
tienda y categoría» de [`datos/LEEME.md`](../datos/LEEME.md) (las 20 filas de
`datos/resultado_esperado.json`).

## Qué hay

```
ejemplo/
├── pack-correccion/                 el pack del docente (esta carpeta)
│   ├── pack.json                    casos, columnas, reglas, rúbrica y política
│   ├── enunciado.md                 lo que lee el alumno, con la rúbrica
│   ├── datos/visible/               los ficheros de ejemplo/datos (los de la práctica)
│   ├── datos/oculto/                otro juego de datos con las mismas reglas (caso oculto)
│   ├── esperado/visible.csv         la tabla esperada con los datos de la práctica
│   ├── esperado/oculto.csv          la tabla esperada del caso oculto
│   └── solucion/                    la solución (solo en la copia del docente)
├── pack-correccion-alumnos.zip      la copia para repartir: sin solución ni caso oculto
├── entregas-ejemplo/                cuatro entregas con errores típicos (ver abajo)
├── informes/correccion/             lo que escribió el corrector con esas entregas
├── solucion-referencia/06_kpi_desde_ficheros.ktr   la solución, en una sola transformación
└── herramientas/generar_caso_oculto.py             genera los datos del caso oculto
```

### Por qué una sola transformación

La práctica completa es un job con cinco transformaciones que cargan un modelo estrella en SQLite, y
el corrector de esta versión todavía no corrige jobs. Además, la solución de referencia clasifica las
líneas con una expresión Java (*User Defined Java Expression*), y el corrector no ejecuta código Java, ni
en la solución ni en las entregas. Por eso el ejercicio corregible es el KPI calculado directamente desde los ficheros:
[`06_kpi_desde_ficheros.ktr`](../solucion-referencia/06_kpi_desde_ficheros.ktr) aplica las mismas reglas
que el resto de la solución de referencia (quitar duplicados, descartar líneas sin tienda, con cantidad
no positiva o con un producto desconocido, unificar la coma decimal y las categorías) con steps
estándar de Pentaho, y da los mismos números que `datos/resultado_esperado.json`. Contra este pack,
esa solución saca **10**, tanto con el idioma del pack (`es_ES`) como con `en_US`.

## La rúbrica

| Criterio | Puntos | Cómo se calcula |
|---|---:|---|
| Corre sin errores | 1 | la transformación termina sin errores en los dos casos |
| Columnas correctas | 1 | `tienda`, `categoria`, `unidades`, `importe`, en cualquier orden |
| Cantidad de filas | 1 | 20 filas en cada caso |
| Valores correctos | 4 | filas iguales por la clave (`tienda`, `categoria`), con un céntimo de tolerancia |
| Agrupa con un Group by | 1 | un Group by o Memory group by conectado a la salida; solo cuenta si corre |
| Quita los duplicados con Unique rows | 1 | un Unique rows (o Unique rows by hash set); solo cuenta si corre |
| Sin JavaScript ni Java | 0,5 | ningún step de código |
| Rutas relativas a la transformación | 0,5 | la regla `absolute_path` del linter no encuentra nada |

Los criterios de salida se promedian entre los dos casos. La nota es la suma sobre 10, redondeada a una
décima. Los puntos se cambian en `pack.json` (`rubric`) o con el asistente al crear el pack.

## Cómo se usa

### 1. Repartir el enunciado

A los alumnos se les da `pack-correccion-alumnos.zip`: trae el enunciado, los datos del caso visible y
la salida esperada de ese caso, pero no la solución ni nada del caso oculto. Quien tenga Pentaho Live
puede corregirse antes de entregar, contra el caso visible: pidiéndoselo a Claude con la transformación
abierta en Spoon (`grade_exercise` con el zip como pack) o en la perspectiva Corrector, con el zip como pack
y una carpeta que tenga solo su `.ktr`.

### 2. Recoger las entregas

Hace falta una carpeta con **una entrega por alumno**: un `.ktr`, un `.zip` o una subcarpeta. La
descarga de una tarea de Moodle sirve tal cual (el corrector quita el sufijo `_123_assignsubmission_file_`
del nombre de cada carpeta). Si se quiere poner el nombre real y el correo de cada alumno, se añade a
esa carpeta un `alumnos.csv` con `archivo;alumno;email`.

### 3. Corregir en Spoon

En la perspectiva **Corrector** (el desplegable de perspectivas de Spoon, junto a *Data Integration* e
*Informes*):

1. **Pack**: esta carpeta (`ejemplo/pack-correccion`) o un `.zip`. Al lado aparece el resumen: el
   título, los dos casos (uno oculto) y los 10 puntos.
2. **Entregas**: la carpeta del paso anterior; al lado, cuántas entregas encontró.
3. **Resultados**: dónde dejar la planilla y las devoluciones. Si se deja en blanco, se crea al lado de
   la de entregas («Entregas TP3 - corrección»).
4. **Corregir**. Cada entrega corre en una JVM aparte (dos a la vez, se puede cambiar en *Procesos*) y
   la tabla se va llenando: nota, estado y una marca por criterio (● todos los puntos, ◐ una parte,
   ○ ninguno). Para las cuatro entregas de ejemplo tarda unos veinte segundos.

Al elegir una fila se ve la corrección completa a la derecha, con los ejemplos de las filas que no
coinciden. **Ver como el alumno** muestra lo que va a recibir el alumno, sin los valores del caso
oculto. Además:

- **Ajuste** y **Comentario**: puntos que se suman o se restan a la nota del corrector, con su motivo.
  Van a la planilla (`nota_motor`, `ajuste`, `comentario`) y se conservan si se vuelve a corregir en
  la misma carpeta de resultados.
- **Abrir su .ktr** abre en Data Integration la copia que corrió el caso visible, con sus datos, para
  ejecutarla paso a paso. Nunca abre ni modifica la entrega original.
- **Volver a corregir con…** corrige otra vez esa entrega dejando correr steps que el pack no permite
  (por ejemplo, un Modified JavaScript) o, solo desde aquí y solo para esa entrega, en *modo confianza*.
  Queda registrado en el resultado.
- **Notas ▾** abre `notas.xlsx` o `notas.csv` (pensado para Excel en español: separador `;` y coma
  decimal) o arma el informe del curso (distribución de notas y criterios más fallados).
- **Exportar devoluciones** escribe la devolución de cada alumno en HTML y PDF en
  `<resultados>/devoluciones`.

### 3 bis. Corregir con Claude

Con el servidor MCP de Pentaho Live conectado, basta con pedirlo: «Corrige las entregas de
`C:\…\Entregas TP3` con el pack `C:\…\ejemplo\pack-correccion` y deja los resultados en
`C:\…\Notas TP3`». Claude usa `grade_submissions`, que devuelve un `job_id` y la tabla de notas a medida
que avanzan, y `get_grading_status` para seguirlo. Una sola entrega se corrige con `grade_exercise`. El
lote que lanza Claude también aparece en la perspectiva Corrector de ese Spoon. Por MCP nunca se activa
el modo confianza, y el texto de las entregas (nombres de steps, valores, logs) es dato del alumno, no
instrucciones para Claude.

### 4. Devolver

En la carpeta de resultados queda, por alumno, `<alumno>.html` (la devolución, que se puede subir tal
cual al aula virtual), `<alumno>.md` y `<alumno>.json` (el registro completo, solo para el docente).

## Las entregas de ejemplo

Cuatro errores típicos de esta práctica, cada uno en una transformación que por lo demás es correcta.
Lo que escribió el corrector con ellas está en [`informes/correccion/`](../informes/correccion/):
la planilla, el informe del curso y la devolución de cada alumno.

| Entrega | Qué tiene mal | Nota | Qué ve en la devolución |
|---|---|---:|---|
| `lucia_rutas_absolutas` | lee y escribe en `C:\Users\lucia\Escritorio\…` | **9,5** | Corre igual: el corrector redirige las rutas a los datos del ejercicio y lo anota. Pierde el criterio de rutas relativas, con el aviso del linter («solo funciona en esta máquina»). |
| `marcos_sin_quitar_duplicados` | no ordena ni quita los duplicados | **5,3** | Columnas y filas bien, pero las unidades y los importes de casi todas las combinaciones están inflados por las 60 líneas repetidas: valores 10 % en el caso visible y 5 % en el oculto. No suma el criterio de duplicados. |
| `nerea_decimal_vacio` | lee el precio sin formato ni símbolo decimal | **6,0** | Con el idioma del pack (`es_ES`), `2.50` se lee como 250: todos los importes salen multiplicados por 100. Valores 0 y una pista que pide declarar el «.» como símbolo decimal. |
| `oscar_group_by_sin_ordenar` | Group by sin Sort rows delante | **5,0** | El Group by solo agrupa filas consecutivas: salen muchas más de 20 filas con claves repetidas. Pierde filas y valores, y la pista explica que hay que ordenar antes (o usar Memory group by). |

Para probarlo: corrige la carpeta `ejemplo/entregas-ejemplo` con este pack. Las notas tienen que ser
exactamente las de la tabla.

## Volver a crear el pack

Este pack lo creó el propio corrector a partir de la solución. Para crear uno así con otro ejercicio:

1. Prepara los datos del caso oculto: mismos nombres de fichero, otros datos. Para este ejemplo,
   `python herramientas/generar_caso_oculto.py <carpeta>` usa el generador de la práctica con otra
   semilla. En un curso de verdad conviene que el caso oculto sea propio: el de este ejemplo es
   público (el generador y la semilla están en el repositorio).
2. Abre la solución en Spoon (aquí, `solucion-referencia/06_kpi_desde_ficheros.ktr`) y, en la
   perspectiva Corrector, pulsa **Crear pack desde esta transformación…**. El asistente tiene cuatro
   pasos: título, destino, carpeta del caso oculto y copia para alumnos; **Analizar** ejecuta la
   solución en un proceso aparte y muestra las entradas detectadas, el step de salida, las claves y la
   salida esperada antes de guardar nada; después las reglas sugeridas y la rúbrica.
3. O pídeselo a Claude: `create_exercise_pack` con `solution_file`, `pack_path`, `title`, `statement`,
   `cases` (el visible y el oculto con su `inputs_dir`), `rules`, `rubric`, `policy` y `student_copy`.
4. Comprueba el pack con `validate_exercise_pack`: además del esquema y los archivos, corrige la
   solución contra su propio pack, y tiene que sacar la nota máxima.

## Límites

- Corregir es ejecutar código ajeno. El corrector lo hace en un proceso aparte, con un `KETTLE_HOME`
  vacío, una política de steps y topes de tiempo, filas y tamaño de salida, pero **no es un sandbox
  real**. Para entregas de desconocidos, lo seguro es una máquina virtual.
- Por ahora corrige transformaciones (`.ktr`); un job (`.kjb`) queda como «no se pudo abrir».
- Las bases de datos que no sean la SQLite que trae el pack no se usan: un step que se conecta a otra
  base bloquea la entrega.
