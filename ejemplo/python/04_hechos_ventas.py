"""
Traducción a Python de la transformación «04_hechos_ventas» (pandas).

Origen: 04_hechos_ventas.ktr
Fecha: 2026-10-27
Generado por Pentaho Live. Steps: 16 (16 exactos, 0 aproximados, 0 a completar).

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
    "Internal.Transformation.Name": "04_hechos_ventas",
}

engine_dw_grano_de_oro = create_engine("sqlite:///" + f"{VARS['Internal.Entry.Current.Directory']}/../salida/grano_de_oro_dw.sqlite")


def pdi_cambia(claves):
    """True en la primera fila de cada racha de filas seguidas con las mismas claves (PDI compara cada fila con la anterior; nulo = nulo)."""
    antes = claves.shift()
    distinto = claves.ne(antes).astype("boolean").fillna(True) & ~(claves.isna() & antes.isna())
    return distinto.any(axis=1) | (np.arange(len(claves)) == 0)


def _numero_texto(serie, decimales, fijos, agrupar, sep_decimal=".", sep_grupo=","):
    """Número a texto como una máscara de Java (#,##0.00): decimales, mínimos fijos y separadores."""
    def f(v):
        if pd.isna(v):
            return None
        t = format(float(v), ("," if agrupar else "") + f".{decimales}f")
        if fijos < decimales and "." in t:
            ent, dec = t.split(".")
            dec = dec[:fijos] + dec[fijos:].rstrip("0")
            t = ent + ("." + dec if dec else "")
        return t.replace(",", "\0").replace(".", sep_decimal).replace("\0", sep_grupo)
    return serie.map(f)


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

# --- 1. Leer ventas (CSV) (CsvInput)
# Lee ventas_2026_09.csv (8 campos)
# NOTA: Como en PDI, el texto vacío se lee como nulo.
archivo_leer_ventas_csv = f"{VARS['Internal.Entry.Current.Directory']}/../datos/ventas_2026_09.csv"
df_leer_ventas_csv = pd.read_csv(archivo_leer_ventas_csv, sep=";", header=None, skiprows=1, names=["id_ticket", "linea", "fecha", "id_tienda", "codigo_producto", "cantidad", "precio_unitario", "metodo_pago"], usecols=range(8),
    encoding="UTF-8", dtype=str, keep_default_na=False, na_values=[""])
df_leer_ventas_csv["linea"] = pd.to_numeric(df_leer_ventas_csv["linea"]).astype("Int64")
df_leer_ventas_csv["cantidad"] = pd.to_numeric(df_leer_ventas_csv["cantidad"]).astype("Int64")

# --- 2. Estandarizar textos (StringOperations)
# Limpia campos de texto
df_estandarizar_textos = df_leer_ventas_csv.copy()
df_estandarizar_textos["id_tienda"] = df_estandarizar_textos["id_tienda"].str.strip(" ").str.upper()
df_estandarizar_textos["codigo_producto"] = df_estandarizar_textos["codigo_producto"].str.strip(" ").str.upper()
df_estandarizar_textos["metodo_pago"] = df_estandarizar_textos["metodo_pago"].str.strip(" ").str.upper()

# --- 3. Normalizar fecha y precio (ReplaceString)
# Limpia campos de texto
df_normalizar_fecha_y_precio = df_estandarizar_textos.copy()
df_normalizar_fecha_y_precio["fecha"] = df_normalizar_fecha_y_precio["fecha"].str.replace("^(\\d{2})/(\\d{2})/(\\d{4})$", "\\3-\\2-\\1", regex=True, case=False)
df_normalizar_fecha_y_precio["precio_unitario"] = df_normalizar_fecha_y_precio["precio_unitario"].str.replace(",", ".", regex=True, case=False)

# --- 4. Convertir precio a número (SelectValues)
# Cambia el tipo de 1 campos
df_convertir_precio_a_numero = df_normalizar_fecha_y_precio.copy()
df_convertir_precio_a_numero["precio_unitario"] = pd.to_numeric(df_convertir_precio_a_numero["precio_unitario"])

# --- 5. Ordenar por ticket y línea (SortRows)
# Ordena por id_ticket, linea
df_ordenar_por_ticket_y_linea = df_convertir_precio_a_numero.sort_values(["id_ticket", "linea"], ascending=True, na_position="first", kind="stable", key=lambda c: c.str.lower() if c.name in ["id_ticket"] else c)
df_ordenar_por_ticket_y_linea = df_ordenar_por_ticket_y_linea.reset_index(drop=True)

# --- 6. Eliminar duplicados (Unique)
# Quita duplicados por id_ticket, linea
# PDI compara cada fila con la anterior: sólo quita duplicados SEGUIDOS (entrada ordenada).
nueva = pdi_cambia(df_ordenar_por_ticket_y_linea[["id_ticket", "linea"]])
df_eliminar_duplicados = df_ordenar_por_ticket_y_linea[nueva]
df_eliminar_duplicados_error = df_ordenar_por_ticket_y_linea[~nueva].reset_index(drop=True)
df_eliminar_duplicados = df_eliminar_duplicados.reset_index(drop=True)

# --- 7. Buscar tienda (DBLookup)
# Busca valores en la tabla dim_tienda
# NOTA: La tabla se lee una vez entera (como la caché «cargar todo» de PDI).
tabla = pd.read_sql(text("SELECT id_tienda, sk_tienda FROM dim_tienda"), engine_dw_grano_de_oro)
busca = tabla[["id_tienda", "sk_tienda"]].rename(columns={"id_tienda": "_k0"})
busca = busca.drop_duplicates(subset=["_k0"], keep="first")
df_buscar_tienda = df_eliminar_duplicados.merge(busca, how="left", left_on=["id_tienda"], right_on=["_k0"]).drop(columns=["_k0"])

# --- 8. Marcar duplicado (Constant)
# Agrega las constantes motivo_rechazo
df_marcar_duplicado = df_eliminar_duplicados_error.copy()
df_marcar_duplicado["motivo_rechazo"] = "DUPLICADO"

# --- 9. Buscar producto (DBLookup)
# Busca valores en la tabla dim_producto
# NOTA: La tabla se lee una vez entera (como la caché «cargar todo» de PDI).
tabla_2 = pd.read_sql(text("SELECT codigo_producto, sk_producto FROM dim_producto"), engine_dw_grano_de_oro)
busca_2 = tabla_2[["codigo_producto", "sk_producto"]].rename(columns={"codigo_producto": "_k0"})
busca_2 = busca_2.drop_duplicates(subset=["_k0"], keep="first")
df_buscar_producto = df_buscar_tienda.merge(busca_2, how="left", left_on=["codigo_producto"], right_on=["_k0"]).drop(columns=["_k0"])

# --- 10. Buscar fecha (DBLookup)
# Busca valores en la tabla dim_fecha
# NOTA: La tabla se lee una vez entera (como la caché «cargar todo» de PDI).
tabla_3 = pd.read_sql(text("SELECT fecha, sk_fecha FROM dim_fecha"), engine_dw_grano_de_oro)
busca_3 = tabla_3[["fecha", "sk_fecha"]].rename(columns={"fecha": "_k0"})
busca_3 = busca_3.drop_duplicates(subset=["_k0"], keep="first")
df_buscar_fecha = df_buscar_producto.merge(busca_3, how="left", left_on=["fecha"], right_on=["_k0"]).drop(columns=["_k0"])

# --- 11. Clasificar línea (Janino)
# Step de tipo Janino
# NOTA: Con nulos, el código Java de PDI suele fallar; aquí da nulo.
df_clasificar_linea = df_buscar_fecha.copy()
df_clasificar_linea["motivo_rechazo"] = pd.Series(np.where((df_clasificar_linea["id_tienda"].isna() | (df_clasificar_linea["id_tienda"].str.len() == 0)), "TIENDA_VACIA", np.where((df_clasificar_linea["cantidad"].isna() | (np.trunc(df_clasificar_linea["cantidad"]) <= 0)), "CANTIDAD_NO_VALIDA", np.where(df_clasificar_linea["sk_tienda"].isna(), "TIENDA_DESCONOCIDA", np.where(df_clasificar_linea["sk_producto"].isna(), "PRODUCTO_DESCONOCIDO", np.where(df_clasificar_linea["sk_fecha"].isna(), "FECHA_NO_VALIDA", None))))), index=df_clasificar_linea.index)

# --- 12. ¿Línea válida? (FilterRows)
# Filtra filas con motivo_rechazo IS NULL
cond_linea_valida = df_clasificar_linea["motivo_rechazo"].fillna("").eq("")
df_linea_valida = df_clasificar_linea[cond_linea_valida].reset_index(drop=True)
df_linea_valida_false = df_clasificar_linea[~cond_linea_valida].reset_index(drop=True)

# --- 13. Preparar rechazo (SelectValues)
# Se queda con 9 campos
df_preparar_rechazo = df_linea_valida_false[["id_ticket", "linea", "fecha", "id_tienda", "codigo_producto", "cantidad", "precio_unitario", "metodo_pago", "motivo_rechazo"]].copy()

# --- 14. Escribir rechazos (TextFileOutput)
# Escribe rechazos_ventas.csv
df_escribir_rechazos_in = pd.concat([df_marcar_duplicado, df_preparar_rechazo], ignore_index=True)
df_escribir_rechazos = df_escribir_rechazos_in
sal_escribir_rechazos = df_escribir_rechazos_in[["id_ticket", "linea", "fecha", "id_tienda", "codigo_producto", "cantidad", "precio_unitario", "metodo_pago", "motivo_rechazo"]].copy()
sal_escribir_rechazos["precio_unitario"] = _numero_texto(sal_escribir_rechazos["precio_unitario"], 2, 2, False, ".", ",")
ruta_escribir_rechazos = _nombre_salida(f"{VARS['Internal.Entry.Current.Directory']}/../salida/rechazos_ventas", "csv")
_escribir_texto(sal_escribir_rechazos, ruta_escribir_rechazos, sep=";", encierro="\"", fin="\n",
                codificacion="UTF-8", textos=["id_ticket", "fecha", "id_tienda", "codigo_producto", "metodo_pago", "motivo_rechazo"])

# --- 15. Calcular importe (Calculator)
# Calcula dos_decimales, importe_sin_redondear, importe
df_calcular_importe = df_linea_valida.copy()
df_calcular_importe["dos_decimales"] = pd.Series(2, index=df_calcular_importe.index, dtype="Int64")
df_calcular_importe["importe_sin_redondear"] = (df_calcular_importe["precio_unitario"] * df_calcular_importe["cantidad"])
df_calcular_importe["importe"] = _redondear(df_calcular_importe["importe_sin_redondear"], df_calcular_importe["dos_decimales"], "HALF_UP")
df_calcular_importe = df_calcular_importe.drop(columns=["dos_decimales", "importe_sin_redondear"])  # campos temporales

# --- 16. Cargar hechos_ventas (TableOutput)
# Escribe en la tabla hechos_ventas (dw_grano_de_oro)
df_cargar_hechos_ventas = df_calcular_importe
with engine_dw_grano_de_oro.begin() as _con:
    _con.execute(text("DELETE FROM hechos_ventas"))  # vaciar la tabla (truncate)
df_calcular_importe[["id_ticket", "linea", "sk_fecha", "sk_tienda", "sk_producto", "cantidad", "precio_unitario", "importe", "metodo_pago"]].to_sql("hechos_ventas", engine_dw_grano_de_oro, if_exists="append", index=False, chunksize=1000)

# Resumen: Steps: 16 (16 exactos, 0 aproximados, 0 a completar)
#   1. Leer ventas (CSV) (CsvInput): exacto
#   2. Estandarizar textos (StringOperations): exacto
#   3. Normalizar fecha y precio (ReplaceString): exacto
#   4. Convertir precio a número (SelectValues): exacto
#   5. Ordenar por ticket y línea (SortRows): exacto
#   6. Eliminar duplicados (Unique): exacto
#   7. Buscar tienda (DBLookup): exacto
#   8. Marcar duplicado (Constant): exacto
#   9. Buscar producto (DBLookup): exacto
#   10. Buscar fecha (DBLookup): exacto
#   11. Clasificar línea (Janino): exacto
#   12. ¿Línea válida? (FilterRows): exacto
#   13. Preparar rechazo (SelectValues): exacto
#   14. Escribir rechazos (TextFileOutput): exacto
#   15. Calcular importe (Calculator): exacto
#   16. Cargar hechos_ventas (TableOutput): exacto
