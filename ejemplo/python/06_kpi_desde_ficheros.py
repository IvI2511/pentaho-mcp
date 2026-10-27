"""
Traducción a Python de la transformación «06_kpi_desde_ficheros» (pandas).

Origen: 06_kpi_desde_ficheros.ktr
Fecha: 2026-10-27
Generado por Pentaho Live. Steps: 17 (17 exactos, 0 aproximados, 0 a completar).

Cómo correrlo:
  1. Instala las bibliotecas: pip install pandas numpy openpyxl
  2. Deja este archivo en la carpeta del .ktr (las rutas relativas parten de ahí).
"""
import pandas as pd
import numpy as np
import os
import json

# carpeta del script (o del notebook): la misma que la del .ktr
AQUI = os.path.dirname(os.path.abspath(__file__)) if "__file__" in globals() else os.getcwd()
VARS = {
    "Internal.Transformation.Filename.Directory": os.environ.get("Internal.Transformation.Filename.Directory", AQUI),
    "Internal.Entry.Current.Directory": os.environ.get("Internal.Entry.Current.Directory", AQUI),
    "Internal.Transformation.Name": "06_kpi_desde_ficheros",
}


def _jsonpath(doc, ruta):
    """Valores de un JSONPath sencillo ($.a.b, $..a, [*], [n], ['x']) en orden de documento."""
    import re
    pasos = re.findall(r"\.\.[^.\[]+|\.[^.\[]+|\[[^\]]*\]", ruta.lstrip("$"))
    nodos = [doc]
    for p in pasos:
        sig = []
        if p.startswith(".."):
            clave = p[2:]
            def bajar(n):
                if isinstance(n, dict):
                    if clave in n:
                        sig.append(n[clave])
                    for v in n.values():
                        bajar(v)
                elif isinstance(n, list):
                    for v in n:
                        bajar(v)
            for n in nodos:
                bajar(n)
        elif p in (".*", "[*]"):
            for n in nodos:
                sig.extend(n if isinstance(n, list) else n.values() if isinstance(n, dict) else [])
        elif p.startswith("["):
            k = p[1:-1].strip("'\"")
            for n in nodos:
                if isinstance(n, list) and k.lstrip("-").isdigit() and -len(n) <= int(k) < len(n):
                    sig.append(n[int(k)])
                elif isinstance(n, dict) and k in n:
                    sig.append(n[k])
        else:
            for n in nodos:
                if isinstance(n, dict) and p[1:] in n:
                    sig.append(n[p[1:]])
        nodos = sig
    return nodos


def pdi_cambia(claves):
    """True en la primera fila de cada racha de filas seguidas con las mismas claves (PDI compara cada fila con la anterior; nulo = nulo)."""
    antes = claves.shift()
    distinto = claves.ne(antes).astype("boolean").fillna(True) & ~(claves.isna() & antes.isna())
    return distinto.any(axis=1) | (np.arange(len(claves)) == 0)


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

# --- 3. Normalizar precio (ReplaceString)
# Limpia campos de texto
df_normalizar_precio = df_estandarizar_textos.copy()
df_normalizar_precio["precio_unitario"] = df_normalizar_precio["precio_unitario"].str.replace(",", ".", regex=True, case=False)

# --- 4. Convertir precio a número (SelectValues)
# Cambia el tipo de 1 campos
df_convertir_precio_a_numero = df_normalizar_precio.copy()
df_convertir_precio_a_numero["precio_unitario"] = pd.to_numeric(df_convertir_precio_a_numero["precio_unitario"])

# --- 5. Leer productos (Excel) (ExcelInput)
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

# --- 6. Ordenar por ticket y línea (SortRows)
# Ordena por id_ticket, linea
df_ordenar_por_ticket_y_linea = df_convertir_precio_a_numero.sort_values(["id_ticket", "linea"], ascending=True, na_position="first", kind="stable", key=lambda c: c.str.lower() if c.name in ["id_ticket"] else c)
df_ordenar_por_ticket_y_linea = df_ordenar_por_ticket_y_linea.reset_index(drop=True)

# --- 7. Leer tiendas (JSON) (JsonInput)
# Lee tiendas.json (2 campos)
# NOTA: _jsonpath cubre los JSONPath habituales; los filtros [?(...)] hay que hacerlos a mano.
def _filas_json(doc):
    cols = {"id_tienda": _jsonpath(doc, "$.tiendas[*].id_tienda"), "nombre": _jsonpath(doc, "$.tiendas[*].nombre")}
    n = max((len(v) for v in cols.values()), default=0)
    return pd.DataFrame({k: (v + [None] * n)[:n] for k, v in cols.items()}, dtype=object)
archivos_leer_tiendas_json = [f"{VARS['Internal.Entry.Current.Directory']}/../datos/tiendas.json"]
partes = []
for archivo in archivos_leer_tiendas_json:
    with open(archivo, encoding="utf-8") as fh:
        parte = _filas_json(json.load(fh))
    partes.append(parte)
df_leer_tiendas_json = pd.concat(partes, ignore_index=True)
df_leer_tiendas_json = df_leer_tiendas_json.astype(object).where(df_leer_tiendas_json.notna(), None)
df_leer_tiendas_json[["id_tienda", "nombre"]] = df_leer_tiendas_json[["id_tienda", "nombre"]].map(lambda v: v if v is None or isinstance(v, str) else json.dumps(v) if isinstance(v, (dict, list)) else str(v).lower() if isinstance(v, bool) else str(v))
df_leer_tiendas_json["id_tienda"] = df_leer_tiendas_json["id_tienda"].str.strip()
df_leer_tiendas_json["nombre"] = df_leer_tiendas_json["nombre"].str.strip()

# --- 8. Estandarizar productos (StringOperations)
# Limpia campos de texto
df_estandarizar_productos = df_leer_productos_excel.copy()
df_estandarizar_productos["codigo_producto"] = df_estandarizar_productos["codigo_producto"].str.strip(" ").str.upper()
df_estandarizar_productos["nombre"] = df_estandarizar_productos["nombre"].str.strip(" ")
df_estandarizar_productos["categoria"] = df_estandarizar_productos["categoria"].str.strip(" ").str.title()

# --- 9. Eliminar duplicados (Unique)
# Quita duplicados por id_ticket, linea
# PDI compara cada fila con la anterior: sólo quita duplicados SEGUIDOS (entrada ordenada).
nueva = pdi_cambia(df_ordenar_por_ticket_y_linea[["id_ticket", "linea"]])
df_eliminar_duplicados = df_ordenar_por_ticket_y_linea[nueva]
df_eliminar_duplicados = df_eliminar_duplicados.reset_index(drop=True)

# --- 10. Buscar tienda (StreamLookup)
# Busca en «Leer tiendas (JSON)» por id_tienda
# Si la clave se repite en el flujo de búsqueda, PDI se queda con la última fila.
busca = df_leer_tiendas_json[["id_tienda", "nombre"]].rename(columns={"id_tienda": "_k0", "nombre": "tienda"})
busca = busca.drop_duplicates(subset=["_k0"], keep="last")
df_buscar_tienda = df_eliminar_duplicados.merge(busca, how="left", left_on=["id_tienda"], right_on=["_k0"]).drop(columns=["_k0"])

# --- 11. Buscar categoría (StreamLookup)
# Busca en «Estandarizar productos» por codigo_producto
# Si la clave se repite en el flujo de búsqueda, PDI se queda con la última fila.
busca_2 = df_estandarizar_productos[["codigo_producto", "categoria"]].rename(columns={"codigo_producto": "_k0"})
busca_2 = busca_2.drop_duplicates(subset=["_k0"], keep="last")
df_buscar_categoria = df_buscar_tienda.merge(busca_2, how="left", left_on=["codigo_producto"], right_on=["_k0"]).drop(columns=["_k0"])

# --- 12. ¿Línea válida? (FilterRows)
# Filtra filas con tienda IS NOT NULL AND categoria IS NOT NULL AND cantidad > 0
cond_linea_valida = ((~(df_buscar_categoria["tienda"].fillna("").eq("")) & ~(df_buscar_categoria["categoria"].fillna("").eq(""))) & (df_buscar_categoria["cantidad"].notna() & df_buscar_categoria["cantidad"].gt(0)))
df_linea_valida = df_buscar_categoria[cond_linea_valida].reset_index(drop=True)

# --- 13. Calcular importe (Calculator)
# Calcula dos_decimales, importe_sin_redondear, importe
df_calcular_importe = df_linea_valida.copy()
df_calcular_importe["dos_decimales"] = pd.Series(2, index=df_calcular_importe.index, dtype="Int64")
df_calcular_importe["importe_sin_redondear"] = (df_calcular_importe["precio_unitario"] * df_calcular_importe["cantidad"])
df_calcular_importe["importe"] = _redondear(df_calcular_importe["importe_sin_redondear"], df_calcular_importe["dos_decimales"], "HALF_UP")
df_calcular_importe = df_calcular_importe.drop(columns=["dos_decimales", "importe_sin_redondear"])  # campos temporales

# --- 14. Ordenar por tienda y categoría (SortRows)
# Ordena por tienda, categoria
df_ordenar_por_tienda_y_categoria = df_calcular_importe.sort_values(["tienda", "categoria"], ascending=True, na_position="first", kind="stable", key=lambda c: c.str.lower() if c.name in ["tienda", "categoria"] else c)
df_ordenar_por_tienda_y_categoria = df_ordenar_por_tienda_y_categoria.reset_index(drop=True)

# --- 15. Ventas por tienda y categoría (GroupBy)
# Agrupa por tienda, categoria (2 totales)
# Group by de PDI agrupa filas SEGUIDAS con la misma clave (espera la entrada ordenada).
df_ventas_por_tienda_y_categoria = df_ordenar_por_tienda_y_categoria.groupby(["tienda", "categoria"], sort=False, dropna=False).agg(**{
    "unidades": ("cantidad", lambda s: s.sum(min_count=1)),
    "importe_sin_redondear": ("importe", lambda s: s.sum(min_count=1)),
}).reset_index()

# --- 16. Redondear importe (Calculator)
# Calcula dos_decimales, importe
df_redondear_importe = df_ventas_por_tienda_y_categoria.copy()
df_redondear_importe["dos_decimales"] = pd.Series(2, index=df_redondear_importe.index, dtype="Int64")
df_redondear_importe["importe"] = _redondear(df_redondear_importe["importe_sin_redondear"], df_redondear_importe["dos_decimales"], "HALF_UP")
df_redondear_importe = df_redondear_importe.drop(columns=["dos_decimales"])  # campos temporales

# --- 17. Escribir KPI (TextFileOutput)
# Escribe kpi_desde_ficheros.csv
df_escribir_kpi = df_redondear_importe
sal_escribir_kpi = df_redondear_importe[["tienda", "categoria", "unidades", "importe"]].copy()
sal_escribir_kpi["importe"] = _numero_texto(sal_escribir_kpi["importe"], 2, 2, False, ".", ",")
ruta_escribir_kpi = _nombre_salida(f"{VARS['Internal.Entry.Current.Directory']}/../salida/kpi_desde_ficheros", "csv")
_escribir_texto(sal_escribir_kpi, ruta_escribir_kpi, sep=";", encierro="\"", fin="\n",
                codificacion="UTF-8", textos=["tienda", "categoria"])

# Resumen: Steps: 17 (17 exactos, 0 aproximados, 0 a completar)
#   1. Leer ventas (CSV) (CsvInput): exacto
#   2. Estandarizar textos (StringOperations): exacto
#   3. Normalizar precio (ReplaceString): exacto
#   4. Convertir precio a número (SelectValues): exacto
#   5. Leer productos (Excel) (ExcelInput): exacto
#   6. Ordenar por ticket y línea (SortRows): exacto
#   7. Leer tiendas (JSON) (JsonInput): exacto
#   8. Estandarizar productos (StringOperations): exacto
#   9. Eliminar duplicados (Unique): exacto
#   10. Buscar tienda (StreamLookup): exacto
#   11. Buscar categoría (StreamLookup): exacto
#   12. ¿Línea válida? (FilterRows): exacto
#   13. Calcular importe (Calculator): exacto
#   14. Ordenar por tienda y categoría (SortRows): exacto
#   15. Ventas por tienda y categoría (GroupBy): exacto
#   16. Redondear importe (Calculator): exacto
#   17. Escribir KPI (TextFileOutput): exacto
