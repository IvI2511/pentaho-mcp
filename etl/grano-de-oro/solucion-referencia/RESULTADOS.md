# Resultados comprobados de la solución de referencia

Ejecución del 20 de octubre de 2026 con **PDI 9.4.0.0-343 CE** (Kitchen y Pan, sin Spoon) y Java 21,
en Windows 11. La JVM se ejecutó con el **idioma por defecto del equipo, español**: es justo el caso en
el que un número leído de texto sin símbolo decimal explícito sale multiplicado por 100, así que
también queda comprobado que los precios no dependen del idioma.

Los datos son los de `../datos/` tal y como los escribe `generar_datos.py` (semilla 20260901).

`06_kpi_desde_ficheros.ktr` no forma parte del job: es la tabla «ventas por tienda y categoría» calculada en
una sola transformación desde los tres ficheros, que es lo que corrige el pack de
[`../pack-correccion/`](../pack-correccion/). La comprobó el propio corrector: sus 20 filas coinciden con
`kpi_ventas_tienda_categoria` de `../datos/resultado_esperado.json` (y las del caso oculto, con las del
generador para su semilla), y contra el pack saca 10 con `es_ES` y con `en_US`.

## Cómo se comprobó

```powershell
& cmd.exe /c call "$pdi\Kitchen.bat" "/file:<carpeta>\solucion-referencia\job_carga_dw.kjb" /level:Basic
python comprobar_resultados.py
```

- Kitchen terminó con **código de salida 0**, en unos 3 segundos de proceso (unos 12 con el
  arranque de la JVM).
- `comprobar_resultados.py` **cuadró las 29 comprobaciones**. Lo esperado sale de
  `../datos/resultado_esperado.json`, que el generador calcula por su cuenta en Python con
  aritmética decimal exacta, sin pasar por Pentaho.
- La carga se ejecutó dos veces seguidas (la primera sin carpeta `salida/`): los mismos recuentos y
  el mismo resultado. La carga es idempotente.

## Recuentos

| Tabla o fichero | Esperado | Obtenido |
|---|---:|---:|
| `dim_tienda` | 4 | 4 |
| `dim_producto` | 28 | 28 |
| `dim_fecha` | 30 | 30 |
| `hechos_ventas` | 2885 | 2885 |
| `salida/rechazos_ventas.csv` | 115 | 115 |
| hechos + rechazos | 3000 | 3000 |

| Motivo de rechazo | Esperado | Obtenido |
|---|---:|---:|
| `DUPLICADO` | 60 | 60 |
| `TIENDA_VACIA` | 15 | 15 |
| `CANTIDAD_NO_VALIDA` | 18 | 18 |
| `TIENDA_DESCONOCIDA` | 0 | 0 |
| `PRODUCTO_DESCONOCIDO` | 22 | 22 |
| `FECHA_NO_VALIDA` | 0 | 0 |

Otras comprobaciones sobre el almacén: ningún `(id_ticket, linea)` repetido en `hechos_ventas`; las
2885 filas de hechos encuentran su fila en las tres dimensiones; `metodo_pago` solo toma los valores
`TARJETA` (1661), `EFECTIVO` (645) y `BIZUM` (579); `dim_producto` tiene exactamente las 5
categorías canónicas y ningún nombre con espacios sobrantes; `dim_fecha` marca 8 días de fin de
semana y empieza en martes, 1 de septiembre de 2026. El importe total de los hechos es **8957.30**.

## KPI

Ticket medio por tienda (tabla `kpi_ticket_medio_tienda` y `salida/kpi_ticket_medio_tienda.csv`,
idénticos al esperado):

| tienda | importe_total | num_tickets | ticket_medio |
|---|---:|---:|---:|
| Chamberí | 2089.80 | 354 | 5.90 |
| Malasaña | 2822.50 | 460 | 6.14 |
| Retiro | 1696.10 | 287 | 5.91 |
| Salamanca | 2348.90 | 392 | 5.99 |

Ventas por tienda y categoría (tabla `kpi_ventas_tienda_categoria` y su CSV): las 20 filas coinciden
con el esperado.

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

## Filas por paso (log de Kitchen)

`I` = leídas de fichero o base de datos, `O` = escritas a fichero o base de datos (en los CSV
incluye la cabecera), `R`/`W` = leídas de / escritas a otros pasos, `E` = errores.

**04_hechos_ventas**, la transformación principal:

| Paso | I | O | R | W | E |
|---|---:|---:|---:|---:|---:|
| Leer ventas (CSV) | 3001 | 0 | 0 | 3000 | 0 |
| Estandarizar textos | 0 | 0 | 3000 | 3000 | 0 |
| Normalizar fecha y precio | 0 | 0 | 3000 | 3000 | 0 |
| Convertir precio a número | 0 | 0 | 3000 | 3000 | 0 |
| Ordenar por ticket y línea | 0 | 0 | 3000 | 3000 | 0 |
| Eliminar duplicados | 0 | 0 | 3000 | 2940 | 60 |
| Marcar duplicado | 0 | 0 | 60 | 60 | 0 |
| Buscar tienda | 4 | 0 | 2940 | 2940 | 0 |
| Buscar producto | 28 | 0 | 2940 | 2940 | 0 |
| Buscar fecha | 30 | 0 | 2940 | 2940 | 0 |
| Clasificar línea | 0 | 0 | 2940 | 2940 | 0 |
| ¿Línea válida? | 0 | 0 | 2940 | 2940 | 0 |
| Preparar rechazo | 0 | 0 | 55 | 55 | 0 |
| Escribir rechazos | 0 | 116 | 115 | 115 | 0 |
| Calcular importe | 0 | 0 | 2885 | 2885 | 0 |
| Cargar hechos_ventas | 0 | 2885 | 2885 | 2885 | 0 |

Las `E=60` de «Eliminar duplicados» no son fallos: son las copias, que salen por el hop de error hacia
«Marcar duplicado» (les pone `motivo_rechazo = DUPLICADO`) y de ahí a «Escribir rechazos» (60 duplicados + 55 rechazos de validación = 115). Es el único paso de toda la
carga con `E` distinto de 0.

El resto de transformaciones:

| Transformación | Entrada | Salida |
|---|---|---|
| `01_dim_tienda` | 4 tiendas del JSON | 4 filas en `dim_tienda` |
| `02_dim_producto` | 28 filas del Excel | 28 filas en `dim_producto` |
| `03_dim_fecha` | 30 filas generadas | 30 filas en `dim_fecha` |
| `05_kpi_ventas` | 2885 filas del modelo estrella, copiadas a las dos ramas (W=5770) | 20 + 4 filas en las tablas de KPI y en sus CSV |

## Pruebas del camino de error

Sobre una copia de la carpeta, sin tocar los datos del ejemplo:

| Prueba | Qué pasó | Código de salida |
|---|---|---:|
| Falta `datos/productos.xlsx` | «Comprobar ficheros de entrada» falla → «Registrar el error» → «Abortar la carga»; no se toca el almacén | 1 |
| Una línea del CSV con `dos` en la columna `linea` | se cargan las tres dimensiones; «Leer ventas (CSV)» falla (`There were 1 conversion errors on line 5`) → «Registrar el error» → «Abortar la carga» | 1 |

En los dos casos el log termina con el mensaje de «Registrar el error», el de «Abortar la carga» y
`Kitchen - ERROR … Finished with errors`.

## Otras comprobaciones

- `04_hechos_ventas.ktr` ejecutada sola con Pan, con el almacén ya cargado: código 0 y los mismos
  recuentos (cada carga vacía su tabla antes de insertar).
- Redondeo: con una transformación de prueba, el cálculo `ROUND_STD_2` que usan los KPI dio `0.13`
  para `0.125` y `3` para `2.5`, es decir, la mitad hacia arriba, igual que el redondeo del
  generador. En estos datos ningún importe cae justo en la mitad.

## Lo que no se ha comprobado

- **No se ha abierto en Spoon.** La posición de los pasos y de las notas en el lienzo está calculada a
  mano; Kitchen no la usa. Conviene abrir cada fichero en Spoon antes de hacer capturas.
- Solo se ha ejecutado en Windows.
