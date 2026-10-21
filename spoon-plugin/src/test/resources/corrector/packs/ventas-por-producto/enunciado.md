# Ejercicio 3 · Ventas por producto

Leé `ventas.csv` (separador `;`, decimal `.`, fechas `yyyy-MM-dd`) y escribí un CSV con **una fila por
producto** y estas columnas:

| Columna | Qué es |
|---|---|
| `producto` | el producto |
| `unidades` | suma de `cantidad` |
| `importe_total` | suma de `cantidad × precio_unitario` (2 decimales) |
| `num_ventas` | cuántas ventas tuvo |
| `importe_medio` | promedio del importe por venta (2 decimales) |
| `ultima_venta` | la fecha de la última venta |

Reglas: usá un **Group by** (no vale JavaScript) y no más de 8 steps. El orden de las filas no importa.
La corrección corre tu transformación con otros datos además de los de ejemplo.
