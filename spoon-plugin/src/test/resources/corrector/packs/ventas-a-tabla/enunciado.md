# Ejercicio 3b · Totales por producto a una tabla

Leé `ventas.csv` (separador `;`, decimal `.`, fechas `yyyy-MM-dd`) y cargá en la tabla `totales` de la
base SQLite `dw.sqlite` (conexión `dw`) **una fila por producto** con estas columnas:

| Columna | Qué es |
|---|---|
| `producto` | el producto |
| `unidades` | suma de `cantidad` |
| `importe_total` | suma de `cantidad × precio_unitario` |
| `num_ventas` | cuántas ventas tuvo |
| `importe_medio` | promedio del importe por venta |
| `ultima_venta` | la fecha de la última venta |

La tabla ya existe y está vacía. Podés usar Execute SQL (por ejemplo, para vaciarla antes de cargar),
pero solo contra la conexión `dw`.
