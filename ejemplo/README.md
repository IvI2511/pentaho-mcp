# Ejemplo · «Grano de Oro», una práctica de ETL completa

> **In English.** A university-style ETL assignment used to show what Pentaho Live does. «Grano de
> Oro» is a fictitious chain of four coffee shops in Madrid; the task is to load a month of
> point-of-sale data (CSV), a product catalogue (Excel) and the shop list (nested JSON) into a SQLite
> star schema, cleaning and validating along the way, and to compute two KPIs. The data generator
> injects known amounts of errors, so the expected result is exact. `solucion-referencia/` is a
> working solution written as `.ktr`/`.kjb` files and verified with Kitchen; the live demo has Claude
> build the same ETL on the Spoon canvas through the MCP server, and the Reports tab documents it.
> Everything in this folder is in Spanish.

Esta carpeta es un ejercicio de ETL de los que se ponen en una asignatura de Big Data o Ingeniería
de Datos, preparado para enseñar Pentaho Live con algo realista: tres fuentes en tres formatos,
datos sucios de verdad, un modelo en estrella, rechazos con motivo, KPI y un job con camino de error.

Como los errores de los datos se inyectan en cantidades conocidas, **el resultado esperado es
exacto**: cualquier solución (la de referencia, la que Claude construye en directo o la de un
alumno) se comprueba al número con un solo comando.

## Qué hay

```
ejemplo/
├── enunciado.md                 el ejercicio: contexto, datos, qué se pide, entregables y rúbrica
├── generar_datos.py             genera datos/ (solo biblioteca estándar, semilla fija)
├── comprobar_resultados.py      compara la salida de una ETL con el resultado esperado
├── datos/
│   ├── ventas_2026_09.csv       3000 líneas de ticket (CSV con errores inyectados)
│   ├── productos.xlsx           catálogo de 28 productos (Excel, texto sin estandarizar)
│   ├── tiendas.json             las 4 tiendas (JSON anidado)
│   ├── LEEME.md                 los ficheros, los errores inyectados y el RESULTADO ESPERADO
│   └── resultado_esperado.json  lo mismo, para comprobar_resultados.py
├── solucion-referencia/
│   ├── job_carga_dw.kjb         orquesta todo, con camino de éxito y de error
│   ├── 01_dim_tienda.ktr        JSON → dim_tienda
│   ├── 02_dim_producto.ktr      Excel → dim_producto
│   ├── 03_dim_fecha.ktr         genera los 30 días de septiembre → dim_fecha
│   ├── 04_hechos_ventas.ktr     CSV → limpieza, duplicados, claves, validación → hechos y rechazos
│   ├── 05_kpi_ventas.ktr        modelo estrella → KPI (tabla y CSV)
│   └── RESULTADOS.md            los números comprobados de una ejecución con Kitchen
└── salida/                      lo que genera la ETL (no se versiona)
```

| Lo que el enunciado pide | Dónde está en la solución de referencia |
|---|---|
| Fechas en dos formatos, coma decimal, mayúsculas y espacios | `04` «Estandarizar textos», «Normalizar fecha y precio», «Convertir precio a número»; `02` «Estandarizar textos» |
| Deduplicación | `04` «Ordenar por ticket y línea» + «Eliminar duplicados» (las copias salen por el hop de error) |
| Validación con rechazos y motivo | `04` «Clasificar línea» + «¿Línea válida?» → `salida/rechazos_ventas.csv` |
| Búsqueda de claves subrogadas | `04` «Buscar tienda», «Buscar producto», «Buscar fecha» |
| Modelo estrella en SQLite | job «Crear modelo estrella» y las cuatro cargas |
| KPI | `05`: ventas por tienda y categoría, ticket medio por tienda |
| Job con éxito y fallo | `job_carga_dw.kjb`: hops verdes en cadena; cualquier fallo → «Registrar el error» → «Abortar la carga» |
| Documentación | la pestaña Informes de Pentaho Live (abajo) |

## Regenerar los datos

```bash
python generar_datos.py
```

Python 3.8 o posterior, sin paquetes externos. La semilla es fija, así que vuelve a escribir
exactamente los mismos ficheros; si se cambia algún parámetro del generador, `datos/LEEME.md` y
`datos/resultado_esperado.json` se reescriben con los números nuevos.

## Ejecutar la solución de referencia

Con PDI 9.4 y PowerShell (cambia la ruta de `data-integration` por la tuya):

```powershell
Remove-Item Env:NoDefaultCurrentDirectoryInExePath -ErrorAction SilentlyContinue
$pdi = 'C:\ruta\a\data-integration'
& cmd.exe /c call "$pdi\Kitchen.bat" "/file:$PWD\solucion-referencia\job_carga_dw.kjb" /level:Basic
$LASTEXITCODE   # 0 = la carga terminó bien
python comprobar_resultados.py
```

También sirve el script de los ejemplos de la skill, que hace lo mismo y usa un `KETTLE_HOME`
aparte para no tocar tu `~/.kettle`:

```powershell
..\claude\skills\pentaho-etl\manual\ejemplos\correr.ps1 solucion-referencia\job_carga_dw.kjb -PdiHome 'C:\ruta\a\data-integration'
```

El job crea `salida/` con el almacén `grano_de_oro_dw.sqlite`, `rechazos_ventas.csv` y los dos CSV
de KPI. Se puede ejecutar las veces que haga falta: borra y vuelve a crear las tablas cada vez.
Las transformaciones también se pueden abrir y ejecutar una a una en Spoon, siempre después de
haber ejecutado el job al menos una vez (el job es quien crea las tablas).

Los números de una ejecución real están en
[`solucion-referencia/RESULTADOS.md`](solucion-referencia/RESULTADOS.md).

## Cómo se hizo la solución de referencia

La escribió Claude con la skill `pentaho-etl` **en modo archivo**, es decir, escribiendo el XML de
los `.ktr` y el `.kjb` sin Spoon abierto, y se dio por buena cuando Kitchen la ejecutó sin errores
y `comprobar_resultados.py` cuadró todas las comprobaciones. No es la única solución posible: el
enunciado evalúa el resultado, no que los pasos sean exactamente estos.

## La demo en directo

<!-- DEMO -->

## El informe que genera Pentaho Live

<!-- INFORME -->
