"""
Carga del almacén de Grano de Oro

Traducción a Python del job «job_carga_dw» (pandas).
Origen: job_carga_dw.kjb
Generado por Pentaho Live. Entradas: 11 (11 exactas, 0 aproximadas, 0 a completar); transformaciones: 5 (43 steps, 0 a completar).
Ejecútalo como Kitchen: python script.py (código de salida 0 si el job termina bien).
"""
import pandas as pd
import numpy as np
import os
import logging
import sys
import contextlib
import functools
import json
from sqlalchemy import create_engine, text
from sqlalchemy.engine import URL

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("job_carga_dw")

# carpeta del script (o del notebook): la misma que la del .kjb
AQUI = os.path.dirname(os.path.abspath(__file__)) if "__file__" in globals() else os.getcwd()
VARS = {
    "Internal.Job.Filename.Directory": os.environ.get("Internal.Job.Filename.Directory", AQUI),
    "Internal.Entry.Current.Directory": os.environ.get("Internal.Entry.Current.Directory", AQUI),
    "Internal.Job.Name": "job_carga_dw",
    "Internal.Transformation.Filename.Directory": os.environ.get("Internal.Transformation.Filename.Directory", AQUI),
}
# filas que una transformación deja en el resultado (Copy rows to result) para la siguiente
RESULT = {}

engine_dw_grano_de_oro = create_engine("sqlite:///" + f"{VARS['Internal.Entry.Current.Directory']}/../salida/grano_de_oro_dw.sqlite")


class Abortar(Exception):
    """Abort job: corta el job entero (main() o el sub-job devuelve False)."""


def entrada(f):
    """Una entrada del job: devuelve True (éxito) o False (fallo); una excepción cuenta como fallo, como en PDI."""
    @functools.wraps(f)
    def envoltura():
        log.info("%s", f.__doc__.splitlines()[0] if f.__doc__ else f.__name__)
        try:
            return f() is not False
        except Abortar:
            raise
        except Exception:
            log.exception("Falló %s", f.__name__)
            return False
    return envoltura


@contextlib.contextmanager
def con_variables(valores):
    """Da valor a variables (los parámetros de una entrada) mientras dura el bloque."""
    antes = {k: VARS[k] for k in valores if k in VARS}
    VARS.update(valores)
    try:
        yield
    finally:
        for k in valores:
            if k in antes:
                VARS[k] = antes[k]
            else:
                VARS.pop(k, None)


def sentencias_sql(sql):
    """Separa un script SQL en sentencias por «;» (fuera de comillas), como Execute SQL script."""
    partes, actual, comilla = [], [], None
    for ch in sql:
        if comilla:
            comilla = None if ch == comilla else comilla
        elif ch in "'\"":
            comilla = ch
        elif ch == ";":
            partes.append("".join(actual))
            actual = []
            continue
        actual.append(ch)
    partes.append("".join(actual))
    return [p.strip() for p in partes if p.strip()]


def ejecutar_sql(engine, sql, una_sola=False):
    """Ejecuta un script SQL en una transacción (sentencia a sentencia, o entero)."""
    with engine.begin() as con:
        for sentencia in [sql] if una_sola else sentencias_sql(sql):
            con.exec_driver_sql(sentencia)


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


@entrada
def e_comprobar_ficheros_de_entrada():
    """Comprobar ficheros de entrada (FILES_EXIST): Checks if files exist: éxito si existen todos los archivos. Los tres ficheros de datos/ tienen que existir."""
    rutas = [
        f"{VARS['Internal.Entry.Current.Directory']}/../datos/ventas_2026_09.csv",
        f"{VARS['Internal.Entry.Current.Directory']}/../datos/productos.xlsx",
        f"{VARS['Internal.Entry.Current.Directory']}/../datos/tiendas.json",
    ]
    faltan = [r for r in rutas if not os.path.exists(r)]
    for r in faltan:
        log.warning("No existe %s", r)
    return not faltan


@entrada
def e_crear_carpeta_salida():
    """Crear carpeta salida (CREATE_FOLDER): Create a folder: crea la carpeta. Carpeta del almacén SQLite, los rechazos y los KPI."""
    carpeta = f"{VARS['Internal.Entry.Current.Directory']}/../salida"
    if os.path.isdir(carpeta):
        return True
    os.makedirs(carpeta)
    return True


@entrada
def e_crear_modelo_estrella():
    """Crear modelo estrella (SQL): Execute SQL script: ejecuta sentencias SQL en la base. Borra y vuelve a crear las tablas: la carga es completa e idempotente."""
    sql = """
    DROP TABLE IF EXISTS hechos_ventas;
    DROP TABLE IF EXISTS kpi_ventas_tienda_categoria;
    DROP TABLE IF EXISTS kpi_ticket_medio_tienda;
    DROP TABLE IF EXISTS dim_tienda;
    DROP TABLE IF EXISTS dim_producto;
    DROP TABLE IF EXISTS dim_fecha;

    CREATE TABLE dim_tienda (
      sk_tienda      INTEGER PRIMARY KEY,
      id_tienda      TEXT NOT NULL UNIQUE,
      nombre         TEXT NOT NULL,
      barrio         TEXT,
      distrito       TEXT,
      codigo_postal  TEXT,
      fecha_apertura TEXT,
      superficie_m2  INTEGER,
      plazas         INTEGER
    );

    CREATE TABLE dim_producto (
      sk_producto     INTEGER PRIMARY KEY,
      codigo_producto TEXT NOT NULL UNIQUE,
      nombre          TEXT NOT NULL,
      categoria       TEXT NOT NULL,
      precio_venta    REAL
    );

    CREATE TABLE dim_fecha (
      sk_fecha         INTEGER PRIMARY KEY,
      fecha            TEXT NOT NULL UNIQUE,
      anio             INTEGER NOT NULL,
      mes              INTEGER NOT NULL,
      dia              INTEGER NOT NULL,
      dia_semana       INTEGER NOT NULL,
      nombre_dia       TEXT NOT NULL,
      es_fin_de_semana TEXT NOT NULL
    );

    CREATE TABLE hechos_ventas (
      id_ticket       TEXT    NOT NULL,
      linea           INTEGER NOT NULL,
      sk_fecha        INTEGER NOT NULL REFERENCES dim_fecha (sk_fecha),
      sk_tienda       INTEGER NOT NULL REFERENCES dim_tienda (sk_tienda),
      sk_producto     INTEGER NOT NULL REFERENCES dim_producto (sk_producto),
      cantidad        INTEGER NOT NULL,
      precio_unitario REAL    NOT NULL,
      importe         REAL    NOT NULL,
      metodo_pago     TEXT    NOT NULL,
      PRIMARY KEY (id_ticket, linea)
    );

    CREATE TABLE kpi_ventas_tienda_categoria (
      tienda    TEXT NOT NULL,
      categoria TEXT NOT NULL,
      unidades  INTEGER NOT NULL,
      importe   REAL NOT NULL,
      PRIMARY KEY (tienda, categoria)
    );

    CREATE TABLE kpi_ticket_medio_tienda (
      tienda        TEXT PRIMARY KEY,
      importe_total REAL NOT NULL,
      num_tickets   INTEGER NOT NULL,
      ticket_medio  REAL NOT NULL
    );
    """
    ejecutar_sql(engine_dw_grano_de_oro, sql)
    return True


@entrada
def tr_cargar_dim_tienda():
    """Cargar dim_tienda (TRANS): Transformación «01_dim_tienda» (01_dim_tienda.ktr): 5 steps."""
    # --- 1. Localizar tiendas.json (GetFileNames)
    # Step de tipo GetFileNames
    # NOTA: lastmodifiedtime va en UTC; la fecha de PDI es la local.
    archivos_localizar_tiendas_json = [f"{VARS['Internal.Entry.Current.Directory']}/../datos/tiendas.json"]
    df_localizar_tiendas_json = pd.DataFrame([{
        "filename": os.path.abspath(a), "short_filename": os.path.basename(a),
        "path": os.path.dirname(os.path.abspath(a)), "type": "file" if os.path.isfile(a) else "folder",
        "exists": os.path.exists(a), "ishidden": os.path.basename(a).startswith("."),
        "isreadable": os.access(a, os.R_OK), "iswriteable": os.access(a, os.W_OK),
        "lastmodifiedtime": pd.Timestamp(os.path.getmtime(a), unit="s"),
        "size": os.path.getsize(a), "extension": os.path.splitext(a)[1].lstrip("."),
        "uri": "file:///" + os.path.abspath(a).replace(os.sep, "/").lstrip("/"),
        "rooturi": "file:///",
    } for a in archivos_localizar_tiendas_json if os.path.isfile(a)])

    # --- 2. Leer tiendas (JSON) (JsonInput)
    # Step de tipo JsonInput
    # NOTA: El JSON sale del campo "filename" de la entrada (los demás campos no se conservan).
    # NOTA: _jsonpath cubre los JSONPath habituales; los filtros [?(...)] hay que hacerlos a mano.
    def _filas_json(doc):
        cols = {"id_tienda": _jsonpath(doc, "$.tiendas[*].id_tienda"), "nombre": _jsonpath(doc, "$.tiendas[*].nombre"), "barrio": _jsonpath(doc, "$.tiendas[*].ubicacion.barrio"), "distrito": _jsonpath(doc, "$.tiendas[*].ubicacion.distrito"), "codigo_postal": _jsonpath(doc, "$.tiendas[*].ubicacion.codigo_postal"), "fecha_apertura": _jsonpath(doc, "$.tiendas[*].fecha_apertura"), "superficie_m2": _jsonpath(doc, "$.tiendas[*].superficie_m2"), "plazas": _jsonpath(doc, "$.tiendas[*].plazas")}
        n = max((len(v) for v in cols.values()), default=0)
        return pd.DataFrame({k: (v + [None] * n)[:n] for k, v in cols.items()}, dtype=object)
    df_leer_tiendas_json = pd.concat([_filas_json(json.load(open(t, encoding="utf-8"))) for t in df_localizar_tiendas_json["filename"]], ignore_index=True)
    df_leer_tiendas_json = df_leer_tiendas_json.astype(object).where(df_leer_tiendas_json.notna(), None)
    df_leer_tiendas_json[["id_tienda", "nombre", "barrio", "distrito", "codigo_postal", "fecha_apertura", "superficie_m2", "plazas"]] = df_leer_tiendas_json[["id_tienda", "nombre", "barrio", "distrito", "codigo_postal", "fecha_apertura", "superficie_m2", "plazas"]].map(lambda v: v if v is None or isinstance(v, str) else json.dumps(v) if isinstance(v, (dict, list)) else str(v).lower() if isinstance(v, bool) else str(v))
    df_leer_tiendas_json["superficie_m2"] = pd.to_numeric(df_leer_tiendas_json["superficie_m2"]).astype("Int64")
    df_leer_tiendas_json["plazas"] = pd.to_numeric(df_leer_tiendas_json["plazas"]).astype("Int64")

    # --- 3. Estandarizar textos (StringOperations)
    # Limpia campos de texto
    df_estandarizar_textos = df_leer_tiendas_json.copy()
    df_estandarizar_textos["id_tienda"] = df_estandarizar_textos["id_tienda"].str.strip(" ").str.upper()
    df_estandarizar_textos["nombre"] = df_estandarizar_textos["nombre"].str.strip(" ")
    df_estandarizar_textos["barrio"] = df_estandarizar_textos["barrio"].str.strip(" ")
    df_estandarizar_textos["distrito"] = df_estandarizar_textos["distrito"].str.strip(" ")

    # --- 4. Asignar clave subrogada (Sequence)
    # Step de tipo Sequence
    df_asignar_clave_subrogada = df_estandarizar_textos.copy()
    df_asignar_clave_subrogada["sk_tienda"] = pd.Series(1 + np.arange(len(df_asignar_clave_subrogada)) * 1, index=df_asignar_clave_subrogada.index, dtype="Int64")

    # --- 5. Cargar dim_tienda (TableOutput)
    # Escribe en la tabla dim_tienda (dw_grano_de_oro)
    df_cargar_dim_tienda = df_asignar_clave_subrogada
    with engine_dw_grano_de_oro.begin() as _con:
        _con.execute(text("DELETE FROM dim_tienda"))  # vaciar la tabla (truncate)
    df_asignar_clave_subrogada[["sk_tienda", "id_tienda", "nombre", "barrio", "distrito", "codigo_postal", "fecha_apertura", "superficie_m2", "plazas"]].to_sql("dim_tienda", engine_dw_grano_de_oro, if_exists="append", index=False, chunksize=1000)


@entrada
def tr_cargar_dim_producto():
    """Cargar dim_producto (TRANS): Transformación «02_dim_producto» (02_dim_producto.ktr): 5 steps."""
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


@entrada
def tr_cargar_dim_fecha():
    """Cargar dim_fecha (TRANS): Transformación «03_dim_fecha» (03_dim_fecha.ktr): 6 steps."""
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


@entrada
def tr_cargar_hechos_ventas():
    """Cargar hechos_ventas (TRANS): Transformación «04_hechos_ventas» (04_hechos_ventas.ktr): 16 steps."""
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


@entrada
def tr_calcular_kpi():
    """Calcular KPI (TRANS): Transformación «05_kpi_ventas» (05_kpi_ventas.ktr): 11 steps."""
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


@entrada
def e_carga_correcta():
    """Carga correcta (SUCCESS): Success: termina este camino del job con éxito."""
    return True


@entrada
def e_registrar_el_error():
    """Registrar el error (WRITE_TO_LOG): Write to log: escribe un mensaje en el log. Mensaje claro en el log antes de abortar."""
    log.error("Grano de Oro - La carga del almacén de Grano de Oro ha fallado: la entrada que ha fallado es la última que aparece como «Starting entry» antes de este mensaje.")
    return True


@entrada
def e_abortar_la_carga():
    """Abortar la carga (ABORT): Abort job: corta el job entero con error (ningún otro camino sigue). El job termina con error."""
    raise Abortar("Carga abortada: el almacén puede haber quedado a medias. Corrige el error y vuelve a lanzar el job.")


def rama_registrar_el_error():
    """Registrar el error: Sigue el flujo desde «Registrar el error» y devuelve su resultado."""
    e_registrar_el_error()
    return e_abortar_la_carga()


def main():
    """Flujo del job «job_carga_dw»: True si termina bien (como el código 0 de Kitchen)."""
    try:
        if not e_comprobar_ficheros_de_entrada():
            return rama_registrar_el_error()
        if not e_crear_carpeta_salida():
            return rama_registrar_el_error()
        if not e_crear_modelo_estrella():
            return rama_registrar_el_error()
        if not tr_cargar_dim_tienda():
            return rama_registrar_el_error()
        if not tr_cargar_dim_producto():
            return rama_registrar_el_error()
        if not tr_cargar_dim_fecha():
            return rama_registrar_el_error()
        if not tr_cargar_hechos_ventas():
            return rama_registrar_el_error()
        if not tr_calcular_kpi():
            return rama_registrar_el_error()
        return e_carga_correcta()
    except Abortar as e:
        log.error(str(e))
        return False


if __name__ == "__main__":
    sys.exit(0 if main() else 1)
