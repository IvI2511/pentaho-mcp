"""
Traducción a Python de la transformación «05_kpi_ventas» (pandas).

Origen: 05_kpi_ventas.ktr
Fecha: 2026-10-27
Generado por Pentaho Live. Steps: 11 (11 exactos, 0 aproximados, 0 a completar).

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
    "Internal.Transformation.Name": "05_kpi_ventas",
}

engine_dw_grano_de_oro = create_engine("sqlite:///" + f"{VARS['Internal.Entry.Current.Directory']}/../salida/grano_de_oro_dw.sqlite")


def _redondear(serie, decimales=0, modo="HALF_EVEN"):
    """Redondea como PDI (BigDecimal de Java): modo UP, DOWN, CEILING, FLOOR, HALF_UP, HALF_DOWN, HALF_EVEN o HALF_CEILING."""
    import decimal
    serie = pd.Series(serie)
    decs = decimales if isinstance(decimales, pd.Series) else pd.Series(decimales, index=serie.index)
    modos = modo if isinstance(modo, pd.Series) else pd.Series(modo, index=serie.index)
    def uno(x, d, m):
        if pd.isna(x) or pd.isna(d):
            return np.nan
        if m == "HALF_CEILING":
            m = "HALF_UP" if x >= 0 else "HALF_DOWN"
        q = decimal.Decimal(1).scaleb(-int(d))
        return float(decimal.Decimal(repr(float(x))).quantize(q, rounding=getattr(decimal, "ROUND_" + m)))
    return pd.Series([uno(x, d, m) for x, d, m in zip(serie, decs, modos)], index=serie.index, dtype=float)


def _escribir_texto(df, ruta, sep=";", encierro='"', forzar=False, cabecera=True, anexar=False,
                    codificacion="utf-8", fin="\r\n", textos=()):
    """Escribe df como el Text file output de PDI: encierra los campos de texto (siempre si forzar;
    si no, sólo los que llevan el separador, el encierro o un salto de línea) y pone la cabecera si el
    archivo es nuevo."""
    nuevo = not (anexar and os.path.exists(ruta) and os.path.getsize(ruta) > 0)
    columnas = []
    for c in df.columns:
        s = df[c].astype(object).map(lambda v: "" if v is None or pd.isna(v) else str(v))
        if c in textos and encierro:
            if forzar:
                poner = s != ""
            else:
                poner = s.str.contains(sep, regex=False) | s.str.contains(encierro, regex=False) \
                    | s.str.contains("\n", regex=False)
            s = s.where(~poner, encierro + s.str.replace(encierro, encierro * 2, regex=False) + encierro)
        columnas.append(s)
    with open(ruta, "a" if anexar else "w", encoding=codificacion, newline="") as f:
        if cabecera and nuevo:
            f.write(sep.join(encierro + str(c) + encierro if forzar and c in textos else str(c)
                             for c in df.columns) + fin)
        if columnas:
            lineas = columnas[0]
            for s in columnas[1:]:
                lineas = lineas + sep + s
            f.write("".join(linea + fin for linea in lineas))


def _nombre_salida(nombre, extension="", fecha="", parte=None):
    """Nombre del archivo como lo arma PDI: nombre[fecha][_parte].extensión."""
    import datetime
    r = nombre + (datetime.datetime.now().strftime(fecha) if fecha else "")
    if parte is not None:
        r += f"_{parte}"
    return r + ("." + extension if extension else "")

# --- 1. Leer modelo estrella (TableInput)
# Lee la tabla hechos_ventas (dw_grano_de_oro)
# NOTA: Los tipos los decide el driver de Python/JDBC: pueden diferir un poco de los de PDI.
df_leer_modelo_estrella = pd.read_sql_query(text("""SELECT t.nombre AS tienda,
       p.categoria,
       h.id_ticket,
       h.cantidad,
       h.importe
FROM hechos_ventas h
JOIN dim_tienda t ON t.sk_tienda = h.sk_tienda
JOIN dim_producto p ON p.sk_producto = h.sk_producto"""), engine_dw_grano_de_oro)

# --- 2. Ordenar por tienda y categoría (SortRows)
# Ordena por tienda, categoria
df_ordenar_por_tienda_y_categoria = df_leer_modelo_estrella.sort_values(["tienda", "categoria"], ascending=True, na_position="first", kind="stable")
df_ordenar_por_tienda_y_categoria = df_ordenar_por_tienda_y_categoria.reset_index(drop=True)

# --- 3. Ordenar por tienda (SortRows)
# Ordena por tienda
df_ordenar_por_tienda = df_leer_modelo_estrella.sort_values(["tienda"], ascending=True, na_position="first", kind="stable")
df_ordenar_por_tienda = df_ordenar_por_tienda.reset_index(drop=True)

# --- 4. Ventas por tienda y categoría (GroupBy)
# Agrupa por tienda, categoria (2 totales)
# Group by de PDI agrupa filas SEGUIDAS con la misma clave (espera la entrada ordenada).
df_ventas_por_tienda_y_categoria = df_ordenar_por_tienda_y_categoria.groupby(["tienda", "categoria"], sort=False, dropna=False).agg(**{
    "unidades": ("cantidad", lambda s: s.sum(min_count=1)),
    "importe_sin_redondear": ("importe", lambda s: s.sum(min_count=1)),
}).reset_index()

# --- 5. Totales por tienda (GroupBy)
# Agrupa por tienda (2 totales)
# Group by de PDI agrupa filas SEGUIDAS con la misma clave (espera la entrada ordenada).
df_totales_por_tienda = df_ordenar_por_tienda.groupby(["tienda"], sort=False, dropna=False).agg(**{
    "importe_sin_redondear": ("importe", lambda s: s.sum(min_count=1)),
    "num_tickets": ("id_ticket", "nunique"),
}).reset_index()

# --- 6. Redondear importe (Calculator)
# Calcula dos_decimales, importe
df_redondear_importe = df_ventas_por_tienda_y_categoria.copy()
df_redondear_importe["dos_decimales"] = pd.Series(2, index=df_redondear_importe.index, dtype="Int64")
df_redondear_importe["importe"] = _redondear(df_redondear_importe["importe_sin_redondear"], df_redondear_importe["dos_decimales"], "HALF_UP")
df_redondear_importe = df_redondear_importe.drop(columns=["dos_decimales"])  # campos temporales

# --- 7. Calcular ticket medio (Calculator)
# Calcula dos_decimales, importe_total, ticket_sin_redondear, ticket_medio
df_calcular_ticket_medio = df_totales_por_tienda.copy()
df_calcular_ticket_medio["dos_decimales"] = pd.Series(2, index=df_calcular_ticket_medio.index, dtype="Int64")
df_calcular_ticket_medio["importe_total"] = _redondear(df_calcular_ticket_medio["importe_sin_redondear"], df_calcular_ticket_medio["dos_decimales"], "HALF_UP")
df_calcular_ticket_medio["ticket_sin_redondear"] = (df_calcular_ticket_medio["importe_sin_redondear"] / df_calcular_ticket_medio["num_tickets"])
df_calcular_ticket_medio["ticket_medio"] = _redondear(df_calcular_ticket_medio["ticket_sin_redondear"], df_calcular_ticket_medio["dos_decimales"], "HALF_UP")
df_calcular_ticket_medio = df_calcular_ticket_medio.drop(columns=["dos_decimales", "ticket_sin_redondear"])  # campos temporales

# --- 8. Cargar KPI tienda y categoría (TableOutput)
# Escribe en la tabla kpi_ventas_tienda_categoria (dw_grano_de_oro)
df_cargar_kpi_tienda_y_categoria = df_redondear_importe
with engine_dw_grano_de_oro.begin() as _con:
    _con.execute(text("DELETE FROM kpi_ventas_tienda_categoria"))  # vaciar la tabla (truncate)
df_redondear_importe[["tienda", "categoria", "unidades", "importe"]].to_sql("kpi_ventas_tienda_categoria", engine_dw_grano_de_oro, if_exists="append", index=False, chunksize=1000)

# --- 9. CSV KPI tienda y categoría (TextFileOutput)
# Escribe kpi_ventas_tienda_categoria.csv
df_csv_kpi_tienda_y_categoria = df_redondear_importe
sal_csv_kpi_tienda_y_categoria = df_redondear_importe[["tienda", "categoria", "unidades", "importe"]].copy()
ruta_csv_kpi_tienda_y_categoria = _nombre_salida(f"{VARS['Internal.Entry.Current.Directory']}/../salida/kpi_ventas_tienda_categoria", "csv")
_escribir_texto(sal_csv_kpi_tienda_y_categoria, ruta_csv_kpi_tienda_y_categoria, sep=";", encierro="\"", fin="\n",
                codificacion="UTF-8", textos=["tienda", "categoria", "unidades", "importe"])

# --- 10. Cargar KPI ticket medio (TableOutput)
# Escribe en la tabla kpi_ticket_medio_tienda (dw_grano_de_oro)
df_cargar_kpi_ticket_medio = df_calcular_ticket_medio
with engine_dw_grano_de_oro.begin() as _con:
    _con.execute(text("DELETE FROM kpi_ticket_medio_tienda"))  # vaciar la tabla (truncate)
df_calcular_ticket_medio[["tienda", "importe_total", "num_tickets", "ticket_medio"]].to_sql("kpi_ticket_medio_tienda", engine_dw_grano_de_oro, if_exists="append", index=False, chunksize=1000)

# --- 11. CSV KPI ticket medio (TextFileOutput)
# Escribe kpi_ticket_medio_tienda.csv
df_csv_kpi_ticket_medio = df_calcular_ticket_medio
sal_csv_kpi_ticket_medio = df_calcular_ticket_medio[["tienda", "importe_total", "num_tickets", "ticket_medio"]].copy()
ruta_csv_kpi_ticket_medio = _nombre_salida(f"{VARS['Internal.Entry.Current.Directory']}/../salida/kpi_ticket_medio_tienda", "csv")
_escribir_texto(sal_csv_kpi_ticket_medio, ruta_csv_kpi_ticket_medio, sep=";", encierro="\"", fin="\n",
                codificacion="UTF-8", textos=["tienda", "importe_total", "num_tickets", "ticket_medio"])

# Resumen: Steps: 11 (11 exactos, 0 aproximados, 0 a completar)
#   1. Leer modelo estrella (TableInput): exacto
#   2. Ordenar por tienda y categoría (SortRows): exacto
#   3. Ordenar por tienda (SortRows): exacto
#   4. Ventas por tienda y categoría (GroupBy): exacto
#   5. Totales por tienda (GroupBy): exacto
#   6. Redondear importe (Calculator): exacto
#   7. Calcular ticket medio (Calculator): exacto
#   8. Cargar KPI tienda y categoría (TableOutput): exacto
#   9. CSV KPI tienda y categoría (TextFileOutput): exacto
#   10. Cargar KPI ticket medio (TableOutput): exacto
#   11. CSV KPI ticket medio (TextFileOutput): exacto
