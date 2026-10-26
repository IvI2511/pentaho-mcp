# Pruebas de la solución de referencia

> **In English.** Two regression tests with golden datasets for the Grano de Oro reference solution
> (Pentaho Live 0.7.0): each one freezes the input steps as CSV datasets and compares the output of one step
> with a golden CSV, row by row. Run them with `test_run_all` or from Spoon's **IDC › Tests…**.

Dos pruebas de regresión con datasets dorados (Pentaho Live 0.7.0, guía `manual/12-pruebas.md` de la skill).
Cada una cambia los steps de entrada por datasets fijos (`datasets/`), corre la transformación en una JVM
aparte sin escribir nada y compara la salida de un step con su CSV dorado (`golden/`), fila a fila.

| Prueba | Transformación | Entradas congeladas | Salida comparada (clave) |
|---|---|---|---|
| `kpi_tienda_categoria` | `06_kpi_desde_ficheros.ktr` | Leer ventas (CSV), Leer tiendas (JSON), Leer productos (Excel) | Redondear importe: 20 filas (tienda, categoría) |
| `dim_producto` | `02_dim_producto.ktr` | Leer productos (Excel) | Asignar clave subrogada: 28 filas (código de producto); la tabla no se carga |

Se crearon con `test_create` (modo snapshot) con locale `es_ES` y zona `Europe/Madrid`, sobre la solución
tal como está. Para correrlas:

- Con Claude y Spoon abierto: *«Corre las pruebas de la solución de referencia»* (`test_run_all` con la
  carpeta `ejemplo/pruebas` o con la `.ktr`).
- En Spoon: abre `06_kpi_desde_ficheros.ktr` o `02_dim_producto.ktr` y **IDC › Pruebas…**. Spoon las
  encuentra porque están en una carpeta `pruebas/` junto a la de la transformación.

Para verlas fallar, cambia algo en la transformación (por ejemplo, el redondeo de «Redondear importe» a 0
decimales) y vuelve a correrlas: la ventana muestra cada fila que cambia con su clave, lo esperado y lo
obtenido. Si el cambio era a propósito, vuelve a crear la prueba (`test_create` con `replace: true`).
