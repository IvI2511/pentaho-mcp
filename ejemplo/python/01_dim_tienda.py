"""
Traducción a Python de la transformación «01_dim_tienda» (pandas).

Origen: 01_dim_tienda.ktr
Fecha: 2026-10-27
Generado por Pentaho Live. Steps: 5 (5 exactos, 0 aproximados, 0 a completar).

Cómo correrlo:
  1. Instala las bibliotecas: pip install pandas numpy sqlalchemy
  2. Deja este archivo en la carpeta del .ktr (las rutas relativas parten de ahí).
  3. Variables de entorno: PDI_PASSWORD_DW_GRANO_DE_ORO (las contraseñas nunca van en el código).
"""
import pandas as pd
import numpy as np
import os
import json
from sqlalchemy import create_engine, text
from sqlalchemy.engine import URL

# carpeta del script (o del notebook): la misma que la del .ktr
AQUI = os.path.dirname(os.path.abspath(__file__)) if "__file__" in globals() else os.getcwd()
VARS = {
    "Internal.Transformation.Filename.Directory": os.environ.get("Internal.Transformation.Filename.Directory", AQUI),
    "Internal.Entry.Current.Directory": os.environ.get("Internal.Entry.Current.Directory", AQUI),
    "Internal.Transformation.Name": "01_dim_tienda",
}

engine_dw_grano_de_oro = create_engine("sqlite:///" + f"{VARS['Internal.Entry.Current.Directory']}/../salida/grano_de_oro_dw.sqlite")


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

# Resumen: Steps: 5 (5 exactos, 0 aproximados, 0 a completar)
#   1. Localizar tiendas.json (GetFileNames): exacto
#   2. Leer tiendas (JSON) (JsonInput): exacto
#   3. Estandarizar textos (StringOperations): exacto
#   4. Asignar clave subrogada (Sequence): exacto
#   5. Cargar dim_tienda (TableOutput): exacto
