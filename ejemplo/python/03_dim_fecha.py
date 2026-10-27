"""
Traducción a Python de la transformación «03_dim_fecha» (pandas).

Origen: 03_dim_fecha.ktr
Fecha: 2026-10-27
Generado por Pentaho Live. Steps: 6 (6 exactos, 0 aproximados, 0 a completar).

Cómo correrlo:
  1. Instala las bibliotecas: pip install pandas numpy sqlalchemy
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
    "Internal.Transformation.Name": "03_dim_fecha",
}

engine_dw_grano_de_oro = create_engine("sqlite:///" + f"{VARS['Internal.Entry.Current.Directory']}/../salida/grano_de_oro_dw.sqlite")

# --- 1. Generar 30 días (RowGenerator)
# Genera 30 filas
df_generar_30_dias = pd.DataFrame({"fecha_inicio": "2026-09-01"}, index=range(30), dtype=object)
df_generar_30_dias["fecha_inicio"] = pd.to_datetime(df_generar_30_dias["fecha_inicio"], format="%Y-%m-%d")

# --- 2. Numerar días (Sequence)
# Step de tipo Sequence
df_numerar_dias = df_generar_30_dias.copy()
df_numerar_dias["desplazamiento"] = pd.Series(0 + np.arange(len(df_numerar_dias)) * 1, index=df_numerar_dias.index, dtype="Int64")

# --- 3. Calcular fecha (Calculator)
# Calcula fecha, anio, mes, dia
df_calcular_fecha = df_numerar_dias.copy()
df_calcular_fecha["fecha"] = (df_calcular_fecha["fecha_inicio"] + pd.to_timedelta(df_calcular_fecha["desplazamiento"], unit="D"))
df_calcular_fecha["anio"] = df_calcular_fecha["fecha"].dt.year.astype("Int64")
df_calcular_fecha["mes"] = df_calcular_fecha["fecha"].dt.month.astype("Int64")
df_calcular_fecha["dia"] = df_calcular_fecha["fecha"].dt.day.astype("Int64")

# --- 4. Atributos del día (Formula)
# Calcula sk_fecha, dia_semana, nombre_dia, es_fin_de_semana
df_atributos_del_dia = df_calcular_fecha.copy()
df_atributos_del_dia["sk_fecha"] = (((df_atributos_del_dia["anio"] * 10000) + (df_atributos_del_dia["mes"] * 100)) + df_atributos_del_dia["dia"])
df_atributos_del_dia["dia_semana"] = (df_atributos_del_dia["fecha"].dt.dayofweek + 1)
df_atributos_del_dia["nombre_dia"] = (df_atributos_del_dia["fecha"].dt.dayofweek + 1).map({1: "lunes", 2: "martes", 3: "miércoles", 4: "jueves", 5: "viernes", 6: "sábado", 7: "domingo"})
df_atributos_del_dia["es_fin_de_semana"] = pd.Series(np.where(((df_atributos_del_dia["fecha"].dt.dayofweek + 1) > 5), "S", "N"), index=df_atributos_del_dia.index)

# --- 5. Fecha como texto (SelectValues)
# Quita 2 campos
df_fecha_como_texto = df_atributos_del_dia.drop(columns=["fecha_inicio", "desplazamiento"])
df_fecha_como_texto["fecha"] = df_fecha_como_texto["fecha"].dt.strftime("%Y-%m-%d")

# --- 6. Cargar dim_fecha (TableOutput)
# Escribe en la tabla dim_fecha (dw_grano_de_oro)
df_cargar_dim_fecha = df_fecha_como_texto
with engine_dw_grano_de_oro.begin() as _con:
    _con.execute(text("DELETE FROM dim_fecha"))  # vaciar la tabla (truncate)
df_fecha_como_texto[["sk_fecha", "fecha", "anio", "mes", "dia", "dia_semana", "nombre_dia", "es_fin_de_semana"]].to_sql("dim_fecha", engine_dw_grano_de_oro, if_exists="append", index=False, chunksize=1000)

# Resumen: Steps: 6 (6 exactos, 0 aproximados, 0 a completar)
#   1. Generar 30 días (RowGenerator): exacto
#   2. Numerar días (Sequence): exacto
#   3. Calcular fecha (Calculator): exacto
#   4. Atributos del día (Formula): exacto
#   5. Fecha como texto (SelectValues): exacto
#   6. Cargar dim_fecha (TableOutput): exacto
