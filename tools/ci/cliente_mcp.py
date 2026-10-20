"""Cliente MCP chico para la prueba de plataformas (.github/workflows/plataformas.yml).

Habla JSON-RPC por POST con el servidor del plugin de Spoon y recorre lo mínimo que tiene que andar en
cualquier sistema: initialize, tools/list, status, new_transformation, build_steps (Generate rows ->
Add constants -> Dummy), validate, preview_step, canvas_screenshot y save. Cada paso se controla por el
contenido de la respuesta, no solo por el código HTTP. Sigue aunque un paso falle (para tener el
diagnóstico completo) y termina con código 1 si alguno falló.

Uso:  python -I tools/ci/cliente_mcp.py --salida DIR [--url http://127.0.0.1:8765/mcp]
                                        [--herramientas 54] [--plataforma linux-x64]
Deja en DIR: cliente.log (cada pedido y su respuesta), canvas.png y ci_plataformas.ktr.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

PROTOCOLO = "2025-06-18"


class Cliente:
    def __init__(self, url: str, log) -> None:
        self.url = url
        self.log = log
        self.sesion = None
        self.n = 0

    def _post(self, cuerpo: dict, timeout: float = 120) -> dict | None:
        datos = json.dumps(cuerpo).encode()
        pedido = urllib.request.Request(self.url, data=datos, method="POST", headers={
            "Content-Type": "application/json", "Accept": "application/json, text/event-stream"})
        if self.sesion:
            pedido.add_header("Mcp-Session-Id", self.sesion)
        with urllib.request.urlopen(pedido, timeout=timeout) as r:
            self.sesion = r.headers.get("Mcp-Session-Id") or self.sesion
            texto = r.read().decode("utf-8")
        return json.loads(texto) if texto.strip() else None

    def rpc(self, metodo: str, params: dict | None = None, timeout: float = 120) -> dict:
        self.n += 1
        cuerpo = {"jsonrpc": "2.0", "id": self.n, "method": metodo, "params": params or {}}
        self.log.escribir(">>", cuerpo)
        t0 = time.monotonic()
        r = self._post(cuerpo, timeout)
        self.log.escribir(f"<< ({time.monotonic() - t0:.2f} s)", r)
        if r is None or "error" in r:
            raise RuntimeError(f"{metodo}: error JSON-RPC {r and r.get('error')}")
        return r["result"]

    def avisar(self, metodo: str) -> None:
        cuerpo = {"jsonrpc": "2.0", "method": metodo}
        self.log.escribir(">>", cuerpo)
        self._post(cuerpo)

    def tool(self, nombre: str, args: dict | None = None, timeout: float = 120):
        """Devuelve (texto, json o None, contenido). Lanza si isError."""
        res = self.rpc("tools/call", {"name": nombre, "arguments": args or {}}, timeout)
        contenido = res.get("content", [])
        texto = "\n".join(c.get("text", "") for c in contenido if c.get("type") == "text")
        if res.get("isError"):
            raise RuntimeError(f"{nombre}: isError=true: {texto[:2000]}")
        try:
            dato = json.loads(texto)
        except ValueError:
            dato = None
        return texto, dato, contenido


class Log:
    def __init__(self, ruta: str) -> None:
        self.f = open(ruta, "w", encoding="utf-8")

    def escribir(self, prefijo: str, obj) -> None:
        self.f.write(f"{prefijo} {json.dumps(self._recortar(obj), ensure_ascii=False)}\n")
        self.f.flush()

    def _recortar(self, obj):
        # las imágenes en base64 no se escriben enteras
        if isinstance(obj, dict):
            return {k: (f"<{len(v)} caracteres base64>" if k == "data" and isinstance(v, str) and len(v) > 200
                        else self._recortar(v)) for k, v in obj.items()}
        if isinstance(obj, list):
            return [self._recortar(x) for x in obj]
        return obj

    def texto(self, linea: str) -> None:
        print(linea, flush=True)
        self.f.write(linea + "\n")
        self.f.flush()


def campo(nombre: str, tipo: str, valor: str) -> str:
    return (f"<field><name>{nombre}</name><type>{tipo}</type><format/><currency/><decimal/><group/>"
            f"<nullif>{valor}</nullif><length>-1</length><precision>-1</precision>"
            f"<set_empty_string>N</set_empty_string></field>")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:8765/mcp")
    ap.add_argument("--salida", required=True)
    ap.add_argument("--herramientas", type=int, default=54, help="cuántas herramientas espera tools/list")
    ap.add_argument("--plataforma", default=sys.platform)
    a = ap.parse_args()
    os.makedirs(a.salida, exist_ok=True)
    log = Log(os.path.join(a.salida, "cliente.log"))
    c = Cliente(a.url, log)
    fallas: list[str] = []
    estado: dict = {}

    def paso(nombre: str, funcion) -> None:
        t0 = time.monotonic()
        try:
            detalle = funcion()
            log.texto(f"OK    {nombre} ({time.monotonic() - t0:.2f} s){': ' + detalle if detalle else ''}")
        except (RuntimeError, AssertionError, OSError, urllib.error.URLError, ValueError, KeyError) as e:
            fallas.append(nombre)
            log.texto(f"FALLA {nombre} ({time.monotonic() - t0:.2f} s): {e}")

    def initialize():
        r = c.rpc("initialize", {"protocolVersion": PROTOCOLO, "capabilities": {},
                                 "clientInfo": {"name": "ci-plataformas", "version": "1"}})
        c.avisar("notifications/initialized")
        info = r.get("serverInfo", {})
        assert info.get("name") == "pentaho", f"serverInfo inesperado: {info}"
        return f"{info.get('name')} {info.get('version')}, protocolo {r.get('protocolVersion')}"

    def tools_list():
        nombres = [t["name"] for t in c.rpc("tools/list")["tools"]]
        faltan = {"status", "new_transformation", "build_steps", "validate", "preview_step",
                  "canvas_screenshot", "save"} - set(nombres)
        assert not faltan, f"faltan herramientas: {sorted(faltan)}"
        assert len(nombres) == a.herramientas, f"{len(nombres)} herramientas, se esperaban {a.herramientas}"
        return f"{len(nombres)} herramientas"

    def status():
        _, d, _ = c.tool("status")
        assert d and "documents" in d, f"status sin 'documents': {d}"
        return f"plugin {d.get('plugin')}, {len(d['documents'])} documentos abiertos"

    def nueva():
        _, d, _ = c.tool("new_transformation", {"name": "ci_plataformas"})
        assert d and d.get("id"), f"new_transformation sin id: {d}"
        estado["doc"] = d["id"]
        return f"documento {d['id']}"

    def construir():
        pasos = [
            {"type": "RowGenerator", "name": "Generate rows",
             "config_xml": f"<fields>{campo('saludo', 'String', 'hola')}{campo('n', 'Integer', '7')}</fields>"
                           "<limit>5</limit>"},
            {"type": "Constant", "name": "Add constants", "after": "Generate rows",
             "config_xml": f"<fields>{campo('plataforma', 'String', a.plataforma)}</fields>"},
            {"type": "Dummy", "name": "Dummy", "after": "Add constants"},
        ]
        _, d, _ = c.tool("build_steps", {"document": estado["doc"], "steps": pasos, "delay_ms": 0})
        assert d, "build_steps no devolvió JSON"
        creados = [s.get("name") for s in d.get("steps", [])]
        assert creados == ["Generate rows", "Add constants", "Dummy"], f"steps creados: {creados}"
        assert len(d.get("hops", [])) == 2, f"hops: {d.get('hops')}"
        return f"{len(creados)} steps, {len(d['hops'])} hops"

    def validar():
        _, d, _ = c.tool("validate", {"document": estado["doc"]})
        assert d and d.get("errors") == 0, f"validate con errores: {d}"
        return f"valid={d.get('valid')}, errors={d.get('errors')}, warnings={d.get('warnings')}"

    def preview():
        _, d, _ = c.tool("preview_step", {"document": estado["doc"], "step": "Dummy", "rows": 10}, timeout=180)
        assert d, "preview_step no devolvió JSON"
        filas = d.get("rows", [])
        nombres = [f.get("name") if isinstance(f, dict) else f for f in d.get("fields", [])]
        assert d.get("errors", 0) == 0, f"preview con errores: {d}"
        assert len(filas) == 5, f"{len(filas)} filas, se esperaban 5"
        assert nombres == ["saludo", "n", "plataforma"], f"campos: {nombres}"
        assert a.plataforma in json.dumps(filas[0], ensure_ascii=False), f"fila 1 sin la plataforma: {filas[0]}"
        return f"{len(filas)} filas, campos {nombres}, fila 1 = {json.dumps(filas[0], ensure_ascii=False)}"

    def captura():
        texto, _, contenido = c.tool("canvas_screenshot", {"document": estado["doc"]})
        imagenes = [x for x in contenido if x.get("type") == "image"]
        assert imagenes, f"canvas_screenshot sin imagen: {texto}"
        png = base64.b64decode(imagenes[0]["data"])
        assert png[:8] == b"\x89PNG\r\n\x1a\n", "lo que volvió no es un PNG"
        ruta = os.path.join(a.salida, "canvas.png")
        with open(ruta, "wb") as f:
            f.write(png)
        return f"{len(png)} bytes -> canvas.png ({texto})"

    def guardar():
        ruta = os.path.abspath(os.path.join(a.salida, "ci_plataformas.ktr"))
        c.tool("save", {"document": estado["doc"], "path": ruta})
        assert os.path.isfile(ruta), f"save no escribió {ruta}"
        with open(ruta, encoding="utf-8") as f:
            ktr = f.read()
        assert all(f"<name>{s}</name>" in ktr for s in ("Generate rows", "Add constants", "Dummy")), \
            "el .ktr guardado no tiene los tres steps"
        return f"{os.path.getsize(ruta)} bytes -> ci_plataformas.ktr"

    paso("initialize", initialize)
    paso("tools/list", tools_list)
    paso("status", status)
    paso("new_transformation", nueva)
    if "doc" in estado:
        paso("build_steps", construir)
        paso("validate", validar)
        paso("preview_step", preview)
        paso("canvas_screenshot", captura)
        paso("save", guardar)
    else:
        fallas.append("(sin documento: no se probó el resto)")
    log.texto("RESULTADO: " + ("todo OK" if not fallas else "fallaron " + ", ".join(fallas)))
    return 1 if fallas else 0


if __name__ == "__main__":
    sys.exit(main())
