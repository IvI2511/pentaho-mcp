"""Datos del caso oculto del pack de corrección (pack-correccion/datos/oculto).

Usa las mismas reglas que generar_datos.py con otra semilla: mismos nombres de fichero, mismos tipos de
error, otros tickets, otras cantidades y otra suciedad en el catálogo. Así, una salida copiada o
«dibujada» a mano para el caso visible no pasa el oculto.

    python herramientas/generar_caso_oculto.py <carpeta>

Escribe en <carpeta> ventas_2026_09.csv, productos.xlsx, tiendas.json, LEEME.md y
resultado_esperado.json (este último sirve para comprobar esperado/oculto.csv del pack). Solo
biblioteca estándar, Python 3.8 o posterior.
"""
import importlib.util
import sys
from pathlib import Path

SEMILLA_OCULTO = 20261001

if len(sys.argv) != 2:
    sys.exit(__doc__)
destino = Path(sys.argv[1]).resolve()
generador = Path(__file__).resolve().parent.parent / "generar_datos.py"
spec = importlib.util.spec_from_file_location("generar_datos", generador)
g = importlib.util.module_from_spec(spec)
spec.loader.exec_module(g)
g.SEMILLA = SEMILLA_OCULTO
g.CARPETA = destino
g.main()
