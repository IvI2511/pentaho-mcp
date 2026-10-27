"""
Traducción a Python de la transformación «02_dim_producto» (pandas).

Origen: 02_dim_producto.ktr
Fecha: 2026-10-27
Generado por Pentaho Live. Steps: 5 (5 exactos, 0 aproximados, 0 a completar).

Cómo correrlo:
  1. Instala las bibliotecas: pip install pandas numpy sqlalchemy openpyxl
  2. Deja este archivo en la carpeta del .ktr (las rutas relativas parten de ahí).
  3. Variables de entorno: PDI_PASSWORD_DW_GRANO_DE_ORO (las contraseñas nunca van en el código).
"""
import pandas as pd
import numpy as np
import os
from sqlalchemy import create_engine, text
from sqlalchemy.engine import URL

# carpeta del script (o del notebook): la misma que la del .ktr
AQUI = os.path.dirname(os.path.abspath(__file__)) if "__file__" in globals() else os.getcwd()
VARS = {
    "Internal.Transformation.Filename.Directory": os.environ.get("Internal.Transformation.Filename.Directory", AQUI),
    "Internal.Entry.Current.Directory": os.environ.get("Internal.Entry.Current.Directory", AQUI),
    "Internal.Transformation.Name": "02_dim_producto",
}

engine_dw_grano_de_oro = create_engine("sqlite:///" + f"{VARS['Internal.Entry.Current.Directory']}/../salida/grano_de_oro_dw.sqlite")

# --- 1. Leer productos (Excel) (ExcelInput)
# Lee productos.xlsx (4 campos)
# NOTA: Necesita openpyxl (xlsx) o xlrd (xls antiguos).
archivos_leer_productos_excel = [f"{VARS['Internal.Entry.Current.Directory']}/../datos/productos.xlsx"]
partes = []
for archivo in archivos_leer_productos_excel:
    for hoja, fila0, col0 in [("Productos", 0, 0)]:
        parte = pd.read_excel(archivo, sheet_name=hoja, header=None, skiprows=fila0 + 1, dtype=object)
        parte = parte.iloc[:, col0:col0 + 4]
        parte.columns = ["codigo_producto", "nombre", "categoria", "precio_venta"][:parte.shape[1]]
        partes.append(parte)
df_leer_productos_excel = pd.concat(partes, ignore_index=True)
df_leer_productos_excel = df_leer_productos_excel.dropna(how="all", subset=["codigo_producto", "nombre", "categoria", "precio_venta"]).reset_index(drop=True)
df_leer_productos_excel["codigo_producto"] = df_leer_productos_excel["codigo_producto"].astype("string")
df_leer_productos_excel["nombre"] = df_leer_productos_excel["nombre"].astype("string")
df_leer_productos_excel["categoria"] = df_leer_productos_excel["categoria"].astype("string")
df_leer_productos_excel["precio_venta"] = pd.to_numeric(df_leer_productos_excel["precio_venta"]).astype("float64")

# --- 2. Estandarizar textos (StringOperations)
# Limpia campos de texto
df_estandarizar_textos = df_leer_productos_excel.copy()
df_estandarizar_textos["codigo_producto"] = df_estandarizar_textos["codigo_producto"].str.strip(" ").str.upper()
df_estandarizar_textos["nombre"] = df_estandarizar_textos["nombre"].str.strip(" ")
df_estandarizar_textos["categoria"] = df_estandarizar_textos["categoria"].str.strip(" ").str.title()

# --- 3. Ordenar por código (SortRows)
# Ordena por codigo_producto
df_ordenar_por_codigo = df_estandarizar_textos.sort_values(["codigo_producto"], ascending=True, na_position="first", kind="stable", key=lambda c: c.str.lower() if c.name in ["codigo_producto"] else c)
df_ordenar_por_codigo = df_ordenar_por_codigo.reset_index(drop=True)

# --- 4. Asignar clave subrogada (Sequence)
# Step de tipo Sequence
df_asignar_clave_subrogada = df_ordenar_por_codigo.copy()
df_asignar_clave_subrogada["sk_producto"] = pd.Series(1 + np.arange(len(df_asignar_clave_subrogada)) * 1, index=df_asignar_clave_subrogada.index, dtype="Int64")

# --- 5. Cargar dim_producto (TableOutput)
# Escribe en la tabla dim_producto (dw_grano_de_oro)
df_cargar_dim_producto = df_asignar_clave_subrogada
with engine_dw_grano_de_oro.begin() as _con:
    _con.execute(text("DELETE FROM dim_producto"))  # vaciar la tabla (truncate)
df_asignar_clave_subrogada[["sk_producto", "codigo_producto", "nombre", "categoria", "precio_venta"]].to_sql("dim_producto", engine_dw_grano_de_oro, if_exists="append", index=False, chunksize=1000)

# Resumen: Steps: 5 (5 exactos, 0 aproximados, 0 a completar)
#   1. Leer productos (Excel) (ExcelInput): exacto
#   2. Estandarizar textos (StringOperations): exacto
#   3. Ordenar por código (SortRows): exacto
#   4. Asignar clave subrogada (Sequence): exacto
#   5. Cargar dim_producto (TableOutput): exacto
