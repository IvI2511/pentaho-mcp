"""Abre en Spoon el ETL construido en vivo, lo corre (job y hechos) y genera los informes del
ejemplo con el MCP. Uso: python -I informes_ejemplo.py <carpeta ejemplo>"""
import json
import os
import sys
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
EJ = sys.argv[1]
VIVO = os.path.join(EJ, "construido-en-vivo")
DEST = os.path.join(EJ, "informes")
MCP_URL = f"http://127.0.0.1:{os.environ.get('PENTAHO_MCP_PORT', '8765')}/mcp"


def mcp(n, **a):
    m = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": n, "arguments": a}}
    req = urllib.request.Request(MCP_URL, data=json.dumps(m).encode(),
                                 headers={"Content-Type": "application/json"})
    r = json.load(urllib.request.urlopen(req, timeout=900))
    res = r["result"]
    t = res["content"][0]["text"]
    if res.get("isError"):
        raise SystemExit(f"{n}: {t}")
    return t


for d in json.loads(mcp("status"))["documents"]:
    mcp("close_document", document=d["id"], discard_changes=True)
docs = {}
for f in ("01_dim_tienda.ktr", "02_dim_producto.ktr", "03_dim_fecha.ktr", "04_hechos_ventas.ktr",
          "05_kpi_ventas.ktr", "job_carga_dw.kjb"):
    docs[f] = json.loads(mcp("open_file", path=os.path.join(VIVO, f)))["id"]
r = json.loads(mcp("run", document=docs["job_carga_dw.kjb"], wait_s=300))
print("job:", r.get("state"), r.get("result"), "entradas ok:", sum(1 for e in r.get("entries", []) if e.get("result") == "ok"))
r = json.loads(mcp("run", document=docs["04_hechos_ventas.ktr"], wait_s=120))
print("04:", r.get("state"), "errores:", r.get("errors"))
for f, d in docs.items():
    if f.endswith(".ktr"):
        print(f, "valid:", json.loads(mcp("validate", document=d))["valid"])

resumen04 = (
    "Esta transformación carga la tabla de hechos del modelo estrella de **Grano de Oro** a partir del fichero de "
    "ventas de septiembre de 2026. En la última ejecución leyó **3.000 líneas**: 60 eran duplicados exactos, que salen "
    "por el hop de error de «Eliminar duplicados» y reciben el motivo DUPLICADO en «Marcar duplicado»; 55 no superaron "
    "las reglas de «¿Línea válida?», y las **2.885 restantes** se cargaron en `hechos_ventas`. Las 115 líneas "
    "descartadas quedan en `rechazos_ventas.csv` con su motivo, de modo que 2.885 + 115 = 3.000: no se pierde ninguna línea.")
conclus04 = (
    "- La carga es completa y trazable: cada línea del fichero termina en `hechos_ventas` o en el fichero de rechazos, con el motivo.\n"
    "- La validación de Spoon no encuentra errores ni advertencias.\n"
    "- El paso más lento es la escritura en SQLite («Cargar hechos_ventas»), lo esperable en una carga de este tamaño.")
r = json.loads(mcp("build_report", document=docs["04_hechos_ventas.ktr"],
                   formats=["html", "html_light", "pdf", "md", "pptx", "docx"], output_dir=DEST, language="es",
                   title="Carga de hechos de ventas · Grano de Oro", profile_step="Convertir precio a número",
                   narrative={"summary": resumen04, "conclusions": conclus04}))
print("informe 04:", [(s["id"], s["status"]) for s in r["sections"]], r.get("warnings"))
resumenJob = (
    "El job orquesta la carga completa del modelo estrella: comprueba que estén los ficheros de entrada, crea la "
    "carpeta de salida y las tablas, carga las tres dimensiones (tiendas, productos y fechas), después los hechos y por "
    "último los KPI. Si algún paso falla, registra el error y aborta la carga. En la última ejecución todas las "
    "entradas terminaron correctamente.")
r = json.loads(mcp("build_report", document=docs["job_carga_dw.kjb"], formats=["html_light", "pdf", "md", "pptx", "docx"],
                   output_dir=DEST, language="es", title="Job de carga del modelo estrella · Grano de Oro",
                   narrative={"summary": resumenJob}))
print("informe job:", [(s["id"], s["status"]) for s in r["sections"]], r.get("warnings"))
r = json.loads(mcp("build_report", document=docs["job_carga_dw.kjb"], include_job_transformations=True,
                   combine=True, formats=["pdf", "docx"], output_dir=DEST, language="es", file_name="carga_completa",
                   title="Carga completa del modelo estrella · Grano de Oro"))
print("combinado:", [f["path"] for f in r.get("files", [])], r.get("warnings"))
print("capitulos:", sorted({s.get("chapter") for s in r["sections"] if s.get("chapter")}))
mcp("activate_document", document=docs["04_hechos_ventas.ktr"])
