# Salidas de las entregas (C2)

Lo que escribe el step de salida de cada entrega para cada caso, tal como lo dejaría Kettle en
`salida/` del sandbox: el Text file output con **su** separador (`;`), encabezado, máscaras y
decimal. Sirven para probar la comparación (`PruebaCorrectorComparar`) sin correr nada, y a C4 para
verificar que lo que captura una corrida real es esto mismo.

```
salidas/
├── ventas-por-producto/<alumno>/{visible,oculto}.csv
└── ventas-a-tabla/<alumno>/visible.csv      lo que devuelve el SQL del pack por JDBC (REAL -> "20.0")
```

| Alumno | Qué tiene la salida |
|---|---|
| paula_correcta | Igual a `esperado/` byte a byte |
| ana_orden_distinto | Correcta, filas en orden descendente |
| bruno_agregado_mal | `importe_medio` con MAX |
| carla_falta_columna | Sin `ultima_venta` |
| elena_sin_ordenar | Un grupo por fila consecutiva (10 y 12 filas) |
| gabi_renombra | `importe_medio` se llama `promedio` |
| hugo_decimal | Importes ×100 (leyó `2.50` como 250) |
| rodri_filtra_de_mas | Sin las ventas de precio ≤ 2 (faltan Medialuna y Té) |
| quique_dibuja_salida | Las 4 filas del caso visible en los dos casos |
| sofi_fecha_dmy | `ultima_venta` con `dd/MM/yyyy` |
| t3_decimal_coma | `<decimal/>` vacío con `es_ES`: `20,00` (P16) |
| t4_encoding_vacio | `<encoding/>` vacío escrito con el charset nativo: **bytes windows-1252** (P17) |
| ines_tabla_ok, juan_tabla_max | Captura `query`: números planos de SQLite |

Las genera `generar_salidas.py` (en el scratchpad del corrector, fuera del repo) a partir de
`packs/ventas-por-producto/datos/`; si hay que cambiarlas, se editan a mano.
