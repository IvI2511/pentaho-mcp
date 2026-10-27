# Grano de Oro en Python

La solución de referencia (`../solucion-referencia/`) traducida a Python con **IDC › Traducir a Python…**
(o la herramienta `translate_to_python`) de Pentaho Live 0.8.0. Nada de esto está escrito a mano.

| Archivo | Qué es |
|---|---|
| `job_carga_dw.py` | El job completo en pandas: una función por entrada, las transformaciones dentro y un `main()` que sigue los hops de éxito y fallo. Sale con código 0 o 1, como Kitchen. |
| `job_carga_dw.ipynb` | El mismo job como notebook, ya ejecutado. |
| `job_carga_dw_pyspark.ipynb` | El job en PySpark (sin ejecutar: escribir en SQLite desde Spark necesita el .jar del driver JDBC). |
| `06_kpi_desde_ficheros_pyspark.ipynb` | El KPI desde los tres ficheros en **PySpark**, ejecutado, con sus gráficos. |
| `01_…` a `06_….ipynb` / `.py` | Cada transformación como notebook (con explicación de cada step y análisis exploratorio) y como script. `05` y `06` están ejecutados, con sus gráficos. |

Las seis transformaciones se traducen enteras: todos sus steps quedan **exactos** (ninguno aproximado ni
a completar a mano).

## Comprobado

El job traducido, ejecutado sobre una copia de `../datos/`, deja el mismo almacén SQLite que Kitchen:
`python ../comprobar_resultados.py` cuadra **las 29 comprobaciones** (las mismas con las que se validó la
solución de referencia en PDI). Lo repite la prueba `PruebaTraductorGranoDeOro` del plugin.

En PySpark, `06_kpi_desde_ficheros_pyspark.ipynb` deja el mismo CSV que pandas y sus 20 filas cuadran con
`kpi_ventas_tienda_categoria` de `../datos/resultado_esperado.json`.

## Guion para mostrarlo (5 minutos)

1. En Spoon, abre `../solucion-referencia/06_kpi_desde_ficheros.ktr` y elige **IDC › Traducir a Python…**:
   notebook, pandas, español, con explicaciones y gráficos EDA, y *Verificar con los datos*.
2. Abre el notebook que deja al lado del `.ktr`: cada step del lienzo es una celda con su explicación, su
   código y una vista previa; al final, el análisis exploratorio y la tabla de estados.
3. Repite con PySpark: la misma lógica en Spark (`06_kpi_desde_ficheros_pyspark.ipynb`).
4. Con el job: `python job_carga_dw.py` y `python ../comprobar_resultados.py` (29 de 29, como Kitchen).

## Cómo correrlo

```bash
pip install pandas numpy sqlalchemy matplotlib
python job_carga_dw.py
python ../comprobar_resultados.py
```

Los scripts buscan los datos en `../datos/` y escriben en `../salida/`, igual que los `.ktr` (la carpeta del
script hace de `${Internal.Entry.Current.Directory}`). Para PySpark hace falta Java 17 o posterior.
