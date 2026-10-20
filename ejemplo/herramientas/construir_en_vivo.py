"""Construye en vivo, con las herramientas del MCP de Pentaho Live (Spoon abierto con el plugin), el ETL del
ejemplo «Grano de Oro»: las 5 transformaciones y el job, tomando la configuracion de cada step de la solucion de
referencia. Graba la ventana de Spoon mientras arma 04_hechos_ventas (para el GIF de la demo; solo Windows).
El puerto del MCP sale de PENTAHO_MCP_PORT (8765 si no esta).

Uso: python -I construir_en_vivo.py <carpeta ejemplo> <carpeta de trabajo (frames, logs)>
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
EJEMPLO, TRABAJO = sys.argv[1], sys.argv[2]
REF = os.path.join(EJEMPLO, "solucion-referencia")
DEST = os.path.join(EJEMPLO, "construido-en-vivo")
os.makedirs(DEST, exist_ok=True)
LOG = open(os.path.join(TRABAJO, "construir.log"), "w", encoding="utf-8")
MCP_URL = f"http://127.0.0.1:{os.environ.get('PENTAHO_MCP_PORT', '8765')}/mcp"


def mcp(nombre, **args):
    msg = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": nombre, "arguments": args}}
    req = urllib.request.Request(MCP_URL, data=json.dumps(msg).encode(),
                                 headers={"Content-Type": "application/json"})
    r = json.load(urllib.request.urlopen(req, timeout=900))
    if "error" in r:
        raise RuntimeError(f"{nombre}: {r['error']}")
    res = r["result"]
    texto = "".join(c.get("text", "") for c in res.get("content", []) if c["type"] == "text")
    LOG.write(f"\n### {nombre} {json.dumps({k: v for k, v in args.items() if k != 'steps'}, ensure_ascii=False)[:300]}\n{texto[:3000]}\n")
    LOG.flush()
    if res.get("isError"):
        raise RuntimeError(f"{nombre}: {texto[:2000]}")
    return res, texto


SALTEAR = {"name", "type", "description", "distribute", "custom_distribution", "copies", "partitioning", "GUI",
           "attributes", "cluster_schema", "remotesteps"}


def cfg(elem, saltear):
    return "".join(ET.tostring(c, encoding="unicode") for c in elem if c.tag not in saltear)


def construir_trans(archivo, grabar=False):
    raiz = ET.parse(os.path.join(REF, archivo)).getroot()
    nombre = os.path.splitext(archivo)[0]
    _, t = mcp("new_transformation", name=nombre)
    doc = json.loads(t)["id"]
    for c in raiz.findall("connection"):
        mcp("add_connection", document=doc, xml=ET.tostring(c, encoding="unicode"))
    pasos = {s.findtext("name"): s for s in raiz.findall("step")}
    errores = {(e.findtext("source_step"), e.findtext("target_step")): e
               for e in raiz.findall("step_error_handling/error")}
    hops = [(h.findtext("from"), h.findtext("to"), h.findtext("enabled") != "N") for h in raiz.findall("order/hop")]
    # orden topologico: cada step despues de todos sus antecesores; 'after' = el primer antecesor normal
    entrantes = {n: [f for f, to, _ in hops if to == n] for n in pasos}
    orden, hechos = [], set()
    while len(orden) < len(pasos):
        for n in pasos:
            if n not in hechos and all(f in hechos for f in entrantes[n]):
                orden.append(n)
                hechos.add(n)
    lista, usados = [], set()
    for n in orden:
        s = pasos[n]
        item = {"type": s.findtext("type"), "name": n, "config_xml": cfg(s, SALTEAR),
                "x": int(s.findtext("GUI/xloc")), "y": int(s.findtext("GUI/yloc"))}
        normales = [f for f in entrantes[n] if (f, n) not in errores]
        if normales:
            item["after"] = normales[0]
            usados.add((normales[0], n))
        salidas = [to for f, to, _ in hops if f == n and (f, to) not in errores]
        if len(salidas) >= 2 and s.findtext("type") not in ("FilterRows", "SwitchCase"):
            item["output_mode"] = "distribute" if s.findtext("distribute") == "Y" else "copy"
        lista.append(item)
    extra = [{"from": f, "to": to, "error": (f, to) in errores, "enabled": en}
             for f, to, en in hops if (f, to) not in usados]
    grabador = None
    if grabar:
        parar = os.path.join(TRABAJO, "parar.flag")
        if os.path.exists(parar):
            os.remove(parar)
        frames = os.path.join(TRABAJO, "frames")
        grabador = subprocess.Popen(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                                     os.path.join(os.path.dirname(os.path.abspath(__file__)), "grabar.ps1"), "-Carpeta", frames, "-Parar", parar,
                                     "-Ms", "200"])
        time.sleep(2.5)
    mcp("build_steps", document=doc, steps=lista, hops=extra, delay_ms=450, arrange=False)
    for (f, to), e in errores.items():
        args = {"document": doc, "step": f, "target": to}
        for tag, clave in (("nr_valuename", "count_field"), ("descriptions_valuename", "description_field"),
                           ("fields_valuename", "fields_field"), ("codes_valuename", "codes_field"),
                           ("max_errors", "max_errors"), ("max_pct_errors", "max_percent_errors"),
                           ("min_pct_rows", "min_rows_for_percent")):
            if e.findtext(tag):
                args[clave] = e.findtext(tag)
        mcp("set_error_handling", **args)
    for nota in raiz.findall("notepads/notepad"):
        args = {"document": doc, "text": nota.findtext("note"), "x": int(nota.findtext("xloc")),
                "y": int(nota.findtext("yloc"))}
        if int(nota.findtext("width") or 0):
            args["width"] = int(nota.findtext("width"))
        if int(nota.findtext("heigth") or 0):
            args["height"] = int(nota.findtext("heigth"))
        mcp("add_note", **args)
    if grabador:
        time.sleep(2)
        open(os.path.join(TRABAJO, "parar.flag"), "w").close()
        grabador.wait(timeout=60)
    mcp("save", document=doc, path=os.path.join(DEST, archivo))
    _, v = mcp("validate", document=doc)
    print(f"{archivo}: {len(lista)} steps, {len(hops)} hops ({len(errores)} de error), "
          f"{len(raiz.findall('notepads/notepad'))} notas -> guardado; validate: {v[:160]}")
    return doc


def construir_job(archivo):
    raiz = ET.parse(os.path.join(REF, archivo)).getroot()
    _, t = mcp("new_job", name=os.path.splitext(archivo)[0])
    doc = json.loads(t)["id"]
    for c in raiz.findall("connection"):
        mcp("add_connection", document=doc, xml=ET.tostring(c, encoding="unicode"))
    saltear = {"name", "description", "type", "attributes", "parallel", "draw", "nr", "xloc", "yloc"}
    for e in raiz.findall("entries/entry"):
        tipo = e.findtext("type")
        if tipo == "SPECIAL":
            tipo = "START" if e.findtext("start") == "Y" else "DUMMY"
            conf = None
        else:
            conf = cfg(e, saltear)
        args = {"document": doc, "type": tipo, "name": e.findtext("name"),
                "x": int(e.findtext("xloc")), "y": int(e.findtext("yloc"))}
        if conf:
            args["config_xml"] = conf
        mcp("add_entry", **args)
        time.sleep(0.35)
    for h in raiz.findall("hops/hop"):
        cond = "always" if h.findtext("unconditional") == "Y" else (
            "on_success" if h.findtext("evaluation") == "Y" else "on_failure")
        mcp("connect_entries", document=doc, **{"from": h.findtext("from")}, to=h.findtext("to"), condition=cond)
    for nota in raiz.findall("notepads/notepad"):
        mcp("add_note", document=doc, text=nota.findtext("note"), x=int(nota.findtext("xloc")),
            y=int(nota.findtext("yloc")))
    mcp("save", document=doc, path=os.path.join(DEST, archivo))
    print(f"{archivo}: {len(raiz.findall('entries/entry'))} entradas, {len(raiz.findall('hops/hop'))} hops -> guardado")
    return doc


docs = {}
for f in ("01_dim_tienda.ktr", "02_dim_producto.ktr", "03_dim_fecha.ktr", "04_hechos_ventas.ktr", "05_kpi_ventas.ktr"):
    docs[f] = construir_trans(f, grabar=(f == "04_hechos_ventas.ktr"))
docs["job_carga_dw.kjb"] = construir_job("job_carga_dw.kjb")
json.dump(docs, open(os.path.join(TRABAJO, "docs.json"), "w"))
print(json.dumps(docs))
