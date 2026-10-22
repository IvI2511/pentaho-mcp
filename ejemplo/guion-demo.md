# Guion de la demo · Pentaho Live con «Grano de Oro»

Unos 15 minutos en tres partes: **Claude construye la ETL en Spoon a la vista de todos**, la ejecuta y
comprueba el resultado al número, y **la documenta sola** con la pestaña Informes. Los mensajes para
Claude están listos para copiar.

## Antes de la clase (10 minutos)

1. **Pentaho Live instalado**, con el instalador de Windows (completo o «solo el plugin») o con los zips
   del [último release](https://github.com/IvI2511/pentaho-mcp/releases/latest). Ver
   [INSTALL.md](../INSTALL.md).
2. **Spoon abierto** desde el acceso «Pentaho Live». Comprobar que el MCP contesta:
   `curl http://127.0.0.1:8765/ping` → `{"ok":true,"spoon":true,...}`.
3. **Claude con el plugin** `pentaho-live` (Claude Code o la app de escritorio). En Claude Code, `/mcp`
   tiene que mostrar el servidor `pentaho` conectado.
4. **La carpeta `ejemplo/`** del repositorio en el disco. Borrar `ejemplo/salida/` si existe, para que
   la carga se vea desde cero.
5. **Pantalla**: Spoon y Claude lado a lado. Si se proyecta, Spoon a la izquierda y más grande.

## Parte 1 · Claude lee el enunciado y construye (6-7 min)

Primero se presenta el problema: abrir [enunciado.md](enunciado.md) y enseñar los datos sucios de
[datos/ventas_2026_09.csv](datos/ventas_2026_09.csv) (fechas en dos formatos, `2,50`, duplicados...).

> Lee `ejemplo/enunciado.md` y mira los ficheros de `ejemplo/datos`. Constrúyeme en Spoon la
> transformación de la dimensión de tiendas (`01_dim_tienda`) que pide el enunciado y guárdala en
> `ejemplo/mi-solucion/`.

Lo que se ve: Claude consulta el catálogo de steps y **los nodos van apareciendo en el lienzo uno a
uno**, ya conectados y colocados. Comentar que cada cambio de Claude se deshace con **Ctrl+Z**, como
si lo hubiera hecho uno mismo.

> Ahora la transformación de hechos, `04_hechos_ventas`: limpieza, duplicados con su motivo, búsqueda
> de claves subrogadas, validación y rechazos a `salida/rechazos_ventas.csv`.

Es la más vistosa: 16 steps en tres filas, con un hop de error y un filtro con dos salidas. Si va
justa de tiempo, pedir las demás de una vez:

> Construye las que faltan (`02`, `03` y `05`) y el job `job_carga_dw` con su camino de error.

**Plan B** si algo falla en directo: abrir los ficheros de [construido-en-vivo/](construido-en-vivo/),
que son los que construyó el MCP en Spoon para preparar este ejemplo. También se puede repetir la
construcción completa en unos 40 segundos con
`python herramientas/construir_en_vivo.py <carpeta ejemplo> <carpeta temporal>`; es lo que se ve en
[capturas/construccion-en-vivo.gif](capturas/construccion-en-vivo.gif).

## Parte 2 · Ejecutar y comprobar (3 min)

> Ejecuta el job, enséñame el estado de cada entrada y después valida las cinco transformaciones.

El job crea la base SQLite y la carga entera en pocos segundos. Las transformaciones se validan
**después** de la primera ejecución, porque hasta entonces la base no existe y las búsquedas no
tienen contra qué comprobar.

> Comprueba el resultado con `python ejemplo/comprobar_resultados.py --salida ejemplo/salida`.

Tiene que decir **«Las 29 comprobaciones cuadran»**. Los errores de los datos se inyectaron en
cantidades conocidas: 60 duplicados, 15 tiendas vacías, 18 cantidades no válidas y 22 productos
desconocidos dan **2.885 hechos y 115 rechazos**. Si el profesor quiere ver los rechazos, abrir
`salida/rechazos_ventas.csv`: cada línea lleva su motivo.

## Parte 3 · Documentar sin escribir una línea (4 min)

> Hazme el informe de `04_hechos_ventas` en PDF y PowerPoint, con un perfil de los datos después de
> convertir el precio y unas conclusiones basadas solo en los datos de la ejecución.

Claude lee los datos del informe (`get_report_data`), escribe la narrativa y genera los ficheros.
El texto escrito por Claude sale marcado como asistido por IA. Abrir el PDF y enseñar:

- **Diagrama de flujo**: la imagen real del lienzo y el mismo flujo dibujado en filas, que llena la página.
- **Linaje de campos**: de qué columna del CSV sale cada columna de `hechos_ventas` y qué le pasa por
  el camino.
- **Perfil de datos**, **métricas** (filas y tiempo por step) y **validación**.

> Enséñamelo en Spoon.

Se abre la pestaña **Informes** de Spoon, con la vista previa. Desde ahí se cambia la identidad
(autor, institución, curso, logo, colores) y se exporta a HTML, PDF, Markdown, PowerPoint, Word o
Excel sin Claude. Con **Añadir transformaciones del job** y **Todo en un archivo**, el job y sus cinco
transformaciones salen en un solo PDF o Word con portada, índice y un capítulo por documento
([`carga_completa.pdf`](informes/carga_completa.pdf)).

## Si el profesor pide cambios en directo

Es la mejor parte: los cambios se ven al momento en el lienzo. Por ejemplo:

> Añade a `05_kpi_ventas` un KPI de ventas por método de pago, con su tabla y su CSV.

> En `04`, rechaza también las líneas con precio unitario mayor que 20 € con el motivo PRECIO_ANOMALO.

Después, volver a ejecutar el job y regenerar el informe.

## Preguntas que suelen salir

- **¿Claude escribe el XML a mano?** En directo no: usa las herramientas del MCP, que crean los steps
  con la API de Pentaho dentro de Spoon. Por eso todo aparece en el lienzo, se valida con las reglas de
  Spoon y se deshace con Ctrl+Z. Sin Spoon, la skill también sabe escribir `.ktr` y comprobarlos con
  Pan y Kitchen; así se hizo [solucion-referencia/](solucion-referencia/).
- **¿Y si se equivoca?** `build_steps` comprueba todo antes de tocar el lienzo y devuelve la lista de
  problemas. Después están `validate` y `preview_step`, que enseña filas reales sin ejecutar la
  transformación entera.
- **¿Funciona con MySQL o PostgreSQL?** Sí: se crea la conexión con `add_connection`, se prueba con
  `test_connection` y se exploran las tablas con `list_tables` y `list_columns`. MySQL necesita su
  driver en `data-integration/lib`.
- **¿Hace falta internet?** Claude sí. Pentaho Live y los informes funcionan sin conexión.
- **¿Se puede usar para corregir prácticas?** El paso siguiente en la hoja de ruta es un corrector:
  el profesor deja la salida esperada (como `datos/resultado_esperado.json`) y el plugin ejecuta la
  ETL del alumno y la compara.

## Parte 4 · Modo clase (5 min, opcional)

Para enseñar que Pentaho Live también sirve para dar clase. Antes: crea una transformación vacía y
guárdala como `leccion/duplicados_y_rechazos.ktr` (así lee `../datos/ventas_2026_09.csv`).

> Da la lección de `leccion/leccion-duplicados-y-rechazos.es.json` sobre la transformación abierta.

Claude llama a `lesson_start` y en el panel de resultados aparece la pestaña **Lección**. Pulsa
**«Siguiente ▶»** al ritmo de la explicación: en 8 pasos se lee el CSV, se estandarizan textos, fechas
y decimales, se ordena, «Eliminar duplicados» manda las repetidas por un **hop de error** a «Marcar
duplicado» (motivo `DUPLICADO`) y «Escribir rechazos» deja **60** líneas en
`salida/rechazos_duplicados.csv`; a «Líneas únicas» llegan **2940**. Cada paso trae su nota numerada en el
lienzo y, en algunos, las primeras filas.

Para cerrar: «◀ Atrás» deshace un paso de verdad, `lesson_notes hide` deja el lienzo limpio para el
ejercicio y `build_report sections=["lesson"]` genera la guía en PDF o Word. Detalle en
[leccion/README.md](leccion/README.md).

## Parte 5 · Corregir entregas (3 min, opcional)

Para un público de docentes. Las cuatro entregas de `entregas-ejemplo/` tienen los errores típicos de la
práctica.

> Corrige las entregas de `ejemplo/entregas-ejemplo/` con el pack `ejemplo/pack-correccion/` y deja la
> planilla en `salida/correccion/`.

Claude llama a `grade_submissions` y sigue el avance con `get_grading_status`: cada entrega corre en una JVM
aparte, nunca dentro de Spoon. Las notas salen 9,5 (rutas absolutas), 5,3 (sin quitar duplicados), 6,0
(símbolo decimal vacío) y 5,0 (Group by sin ordenar), cada una con su pista. Abre la perspectiva
**Corrector** de Spoon para enseñar la tabla, la devolución tal como la ve el alumno (sin los valores del
caso oculto) y la planilla `notas.xlsx`. Lo que escribió el corrector está en `informes/correccion/`.

## Parte 6 · Que la carga aguante datos sucios (4 min, opcional)

Con `solucion-referencia/04_hechos_ventas.ktr` abierta:

> ¿Qué pasa si mañana llega un CSV roto? Revisa la robustez de esta transformación y propónme cómo
> arreglarla.

Claude corre `lint_etl` con `robustness=true` (una nota de robustez aparte, que no cambia la de calidad) y
`harden_etl` en simulación: el plan con cada receta, su diff y la nota antes y después. Aplica solo las
`auto` y pregunta por las `confirm` (las que apartan filas de la carga). Después `stress_test` prueba copias
rotas del CSV (campos que faltan, fechas y números mal escritos, líneas cortadas, archivo vacío) sobre una
copia de la transformación y de la base SQLite: el balance dice, para cada variante, que toda fila leída
quedó cargada o rechazada con su motivo y que volver a correr la carga no duplica nada. Todo se deshace
con un Ctrl+Z. La guía para hacerlo a mano es `manual/09-robustez.md` de la skill.
