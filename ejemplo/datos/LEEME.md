# Datos de entrada · Grano de Oro, septiembre de 2026

> Fichero generado por `generar_datos.py` (semilla 20260901). No lo edites a mano: si cambias el
> generador, vuelve a ejecutarlo y este documento se reescribe con los números nuevos.

Todos los datos son **ficticios**. Los errores se inyectan en cantidades exactas para que el
resultado de la ETL se pueda comprobar al número.

## Ficheros

| Fichero | Formato | Contenido |
|---|---|---|
| `ventas_2026_09.csv` | CSV, UTF-8, separador `;`, con cabecera | 3000 líneas de ticket (1507 tickets) de las 4 tiendas |
| `productos.xlsx` | Excel 2007+ (hoja `Productos`), con cabecera | catálogo de 28 productos en 5 categorías |
| `tiendas.json` | JSON anidado, UTF-8 | las 4 tiendas (`$.tiendas[*]`, ubicación en un objeto anidado) |
| `resultado_esperado.json` | JSON | lo mismo que la sección «Resultado esperado», para `comprobar_resultados.py` |

### `ventas_2026_09.csv`

| Columna | Descripción |
|---|---|
| `id_ticket` | identificador del ticket (`TK000001`…); un ticket tiene de 1 a 4 líneas |
| `linea` | número de línea dentro del ticket; `(id_ticket, linea)` identifica la línea |
| `fecha` | fecha de la venta, en **dos formatos**: `aaaa-MM-dd` y `dd/MM/aaaa` |
| `id_tienda` | `T01`…`T04` (a veces vacío) |
| `codigo_producto` | código del catálogo (`CAF-003`…; a veces no existe) |
| `cantidad` | unidades vendidas (a veces 0 o negativa) |
| `precio_unitario` | precio de venta en euros, con **punto o coma decimal** (`2.50` / `2,50`) |
| `metodo_pago` | `TARJETA`, `EFECTIVO` o `BIZUM`, escritos de varias maneras |

### `productos.xlsx`

Columnas `codigo_producto`, `nombre`, `categoria` y `precio_venta` (numérico). 8 nombres llevan
espacios sobrantes al principio o al final y 17 de las 28 categorías están escritas con mayúsculas
mezcladas o espacios (`café`, `CAFÉ`, ` salado `…). Las categorías canónicas son: `Bebidas`, `Bollería`, `Café`, `Infusiones`, `Salado`.

### `tiendas.json`

Un objeto con la cadena y la lista `tiendas`; cada tienda trae `id_tienda`, `nombre`,
`ubicacion.barrio`, `ubicacion.distrito`, `ubicacion.codigo_postal`, `fecha_apertura`,
`superficie_m2` y `plazas`.

## Errores inyectados en las ventas

Cada línea tiene como mucho un error, y los duplicados son copias exactas (byte a byte) de
líneas sin error, colocadas más abajo en el fichero, como un lote que el TPV reenvió.

| Error | Líneas | Motivo de rechazo esperado |
|---|---:|---|
| Duplicado exacto de otra línea | 60 | `DUPLICADO` |
| `id_tienda` vacío | 15 | `TIENDA_VACIA` |
| Cantidad negativa | 11 | `CANTIDAD_NO_VALIDA` |
| Cantidad cero | 7 | `CANTIDAD_NO_VALIDA` |
| Código de producto inexistente | 22 | `PRODUCTO_DESCONOCIDO` |
| **Total** | **115** | |

## Rarezas de formato (no son errores: hay que corregirlas)

Contadas sobre las 3000 líneas del fichero, duplicados incluidos.

| Rareza | Líneas |
|---|---:|
| Fecha `dd/MM/aaaa` | 838 |
| Fecha `aaaa-MM-dd` | 2162 |
| Precio con coma decimal (`2,50`) | 575 |
| Precio con punto decimal (`2.50`) | 2425 |

`metodo_pago` tal y como aparece (las comillas muestran los espacios):

| Valor en el fichero | Líneas |
|---|---:|
| `"TARJETA"` | 960 |
| `"EFECTIVO"` | 352 |
| `"tarjeta"` | 343 |
| `"BIZUM"` | 334 |
| `"Tarjeta "` | 240 |
| `" tarjeta"` | 183 |
| `"Bizum"` | 155 |
| `"efectivo"` | 138 |
| `"Efectivo "` | 122 |
| `"bizum "` | 116 |
| `"  EFECTIVO"` | 57 |

## Resultado esperado

Es lo que debe dar una ETL correcta (y lo que da la solución de referencia). Los importes van
con punto decimal, igual que en los ficheros de salida.

### Recuento de filas

| Tabla o fichero | Filas |
|---|---:|
| `dim_tienda` | 4 |
| `dim_producto` | 28 |
| `dim_fecha` | 30 |
| `hechos_ventas` | 2885 |
| `rechazos_ventas.csv` | 115 |
| **Comprobación:** hechos + rechazos = líneas del fichero | 2885 + 115 = 3000 |

### Rechazos por motivo

Si una línea tuviera varios problemas, cuenta el primero de esta lista (en estos datos no pasa).

| Orden | Motivo | Filas |
|---:|---|---:|
| 1 | `DUPLICADO` | 60 |
| 2 | `TIENDA_VACIA` | 15 |
| 3 | `CANTIDAD_NO_VALIDA` | 18 |
| 4 | `TIENDA_DESCONOCIDA` | 0 |
| 5 | `PRODUCTO_DESCONOCIDO` | 22 |
| 6 | `FECHA_NO_VALIDA` | 0 |

### Método de pago en `hechos_ventas` (ya estandarizado)

| metodo_pago | Filas |
|---|---:|
| `BIZUM` | 579 |
| `EFECTIVO` | 645 |
| `TARJETA` | 1661 |

### KPI: ticket medio por tienda

`ticket_medio = importe_total / num_tickets`, donde `num_tickets` son los tickets distintos con al
menos una línea válida. Redondeo a 2 decimales (mitad hacia arriba).

| tienda | importe_total | num_tickets | ticket_medio |
|---|---:|---:|---:|
| Chamberí | 2089.80 | 354 | 5.90 |
| Malasaña | 2822.50 | 460 | 6.14 |
| Retiro | 1696.10 | 287 | 5.91 |
| Salamanca | 2348.90 | 392 | 5.99 |
| **Cadena** | **8957.30** | **1493** | **6.00** |

### KPI: ventas por tienda y categoría

| tienda | categoria | unidades | importe |
|---|---|---:|---:|
| Chamberí | Bebidas | 91 | 248.70 |
| Chamberí | Bollería | 166 | 388.40 |
| Chamberí | Café | 359 | 787.10 |
| Chamberí | Infusiones | 75 | 201.70 |
| Chamberí | Salado | 139 | 463.90 |
| Malasaña | Bebidas | 117 | 317.70 |
| Malasaña | Bollería | 247 | 577.70 |
| Malasaña | Café | 495 | 1069.40 |
| Malasaña | Infusiones | 115 | 295.90 |
| Malasaña | Salado | 155 | 561.80 |
| Retiro | Bebidas | 59 | 149.80 |
| Retiro | Bollería | 152 | 346.00 |
| Retiro | Café | 316 | 683.80 |
| Retiro | Infusiones | 72 | 194.70 |
| Retiro | Salado | 93 | 321.80 |
| Salamanca | Bebidas | 109 | 296.10 |
| Salamanca | Bollería | 197 | 445.50 |
| Salamanca | Café | 410 | 891.90 |
| Salamanca | Infusiones | 90 | 246.40 |
| Salamanca | Salado | 138 | 469.00 |
