# Ready lessons (class mode)

Tested lesson scripts for `lesson_start` (see `../manual/08-modo-clase.md`), written for Big Data 1 at the
Universidad Europea de Madrid but usable anywhere. Each file is the exact argument of `lesson_start`, in
Spanish (`.es`), English (`.en`) and Portuguese (`.pt`).

| Script | Level | Builds (solution in `../manual/ejemplos/`) | Steps | Checked result |
|---|---|---|---|---|
| `L1-primer-etl` | beginner | `02_filtrar_clientes.ktr` | 5 | 8 customers in, 4 out (ids 1, 5, 7, 8) |
| `L2-agregar` | intermediate | `03_ventas_por_producto.ktr` | 5 | Café 20.00, Medialuna 21.60, Té 14.40, Tostado 9.50 |
| `L3-unir-fuentes.a-lookup` | intermediate | `04_ventas_con_cliente.ktr` | 4 | 10 sales, sale 108 → `DESCONOCIDO` |
| `L3-unir-fuentes.b-join` | intermediate | `05_clientes_con_ventas.ktr` | 7 | 10 rows (LEFT OUTER), Fabián without sales |

**To give one:** create a transformation, **save** it in an empty folder, copy `../manual/ejemplos/datos/` into
that folder (the steps read `${Internal.Entry.Current.Directory}/datos/*.csv` and write `salida/`), then call
`lesson_start` with the script. Change `pace` (`guided`, `auto`, `manual`) or add `font_size` if asked.
L3 is two lessons: part A, then part B in a second transformation in the same folder.

The data columns keep their Spanish names in every language (they come from the CSV files); step names and
notes are translated.

**Do not edit the JSON by hand:** the texts live in `tools/generador-catalogo/lecciones.py`, which copies each
step's `config_xml` from the verified example and writes these files (`--check` fails if they are out of
date). The plugin test `PruebaLeccionesListas` plays every script to the end without Spoon, saves and runs the
result, and checks the numbers above.
