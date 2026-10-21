"""Genera las imágenes del instalador de Windows de Pentaho Live (Inno Setup).

* ``launcher/pentaho-live.ico``: el ícono multi-tamaño (16, 24, 32, 48, 64, 128 y 256 px) del
  instalador, el desinstalador y los accesos directos. Se versiona, como el ``.ico`` de las apps del
  portafolio (convención 05); este script lo regenera.
* ``<salida>/banner.bmp`` y ``banner@2x.bmp`` (164x314 y 328x628): ``WizardImageFile``, la franja
  de la página de bienvenida. Fondo noche, el ícono, el nombre, una línea en inglés, la rejilla y el
  logo largo de firma, como la tarjeta de compartir del producto.
* ``<salida>/logo.bmp`` y ``logo@2x.bmp`` (55x58 y 110x116): ``WizardSmallImageFile``, el ícono
  arriba a la derecha en todas las páginas. Va sobre BLANCO: el encabezado del asistente es blanco e
  Inno no compone alfa en estas imágenes.

Las imágenes del asistente van en BMP (convención 04). Se dibujan con Chrome sin ventana
(Playwright, canal "chrome"), como ``herramientas/exportar_png.py`` del manual de marca, y el
``.ico`` y los BMP los arma Pillow. Las piezas de la marca son las copias del repo
(``docs/images/pentaho-live-*.svg``, que salen del manual de marca y no se editan) y las letras de
``tools/instalador/fuentes`` (Outfit e Inter, OFL). Todo va en línea en la página (SVG y letras en
base64): una página de ``set_content`` no puede cargar ``file://``.

Uso:
    python tools/instalador/generar_imagenes.py [--salida build/instalador/staging/asistente] [--version X.Y.Z]

Lo corre ``preparar_instalador.ps1`` (raíz del repo). Requiere ``pip install playwright pillow`` y Chrome.
"""

from __future__ import annotations

import argparse
import base64
import io
import re
import struct
import sys
from pathlib import Path

from PIL import Image
from playwright.sync_api import sync_playwright

AQUI = Path(__file__).resolve().parent
REPO = AQUI.parent.parent  # tools/instalador -> raíz del repo
IMAGENES = REPO / "docs" / "images"
FUENTES = AQUI / "fuentes"

ICO = REPO / "launcher" / "pentaho-live.ico"
ICO_TAMANOS = (16, 24, 32, 48, 64, 128, 256)

#: Los tamaños de la convención 04: el primero es el de referencia y el segundo el de alto DPI.
#: Inno elige sola el que mejor encaja con la escala de la pantalla.
BANNER = (164, 314)
LOGO = (55, 58)

#: La línea en inglés del banner: el titular de la tarjeta de compartir del producto
#: (plantillas/productos/pentaho-live-og.html del manual de marca).
LINEA = "Claude builds your ETLs live, in Spoon."

# Colores de tokens/tokens.json del manual de marca.
BG = "#070a0f"
INK = "#f2f6fd"
MUTED = "#93a3bd"
LINE = "rgba(140, 170, 215, 0.22)"


def _svg(nombre: str) -> str:
    texto = (IMAGENES / nombre).read_text(encoding="utf-8")
    # El SVG ocupa la caja que le da el CSS: se le sacan el width/height propios, sólo a la
    # etiqueta <svg> (los <rect> de adentro también tienen width/height).
    inicio = re.search(r"<svg\b[^>]*>", texto)
    etiqueta = re.sub(r'\s(width|height)="[^"]*"', "", inicio.group(0))
    return texto[:inicio.start()] + etiqueta + texto[inicio.end():]


def _fuente(nombre: str, familia: str, formato: str, pesos: str) -> str:
    datos = base64.b64encode((FUENTES / nombre).read_bytes()).decode("ascii")
    tipo = "font/ttf" if formato == "truetype" else "font/woff2"
    return (f'@font-face {{ font-family: "{familia}"; font-weight: {pesos}; '
            f'src: url(data:{tipo};base64,{datos}) format("{formato}"); }}')


def _rejilla(columnas: int, filas: int) -> str:
    """Las celdas de la firma del sistema IDC, con el mismo azar con semilla de la tarjeta de
    compartir del producto: siempre salen las mismas, así el BMP no cambia de un build a otro."""
    s = 11
    celdas = []
    for _ in range(columnas * filas):
        s = (s * 16807) % 2147483647
        q = (s - 1) / 2147483646
        clase = "b" if q > 0.97 else "a" if q > 0.93 else "o" if q > 0.915 else ""
        celdas.append(f'<i class="{clase}"></i>')
    return "".join(celdas)


def _pagina_banner(version: str) -> str:
    ancho, alto = BANNER
    return f"""<!DOCTYPE html><html><head><meta charset="utf-8"><style>
{_fuente("Outfit-Variable.ttf", "Outfit", "truetype", "100 900")}
{_fuente("Inter-latin.woff2", "Inter", "woff2", "100 900")}
html, body {{ margin: 0; }}
body {{ width: {ancho}px; height: {alto}px; overflow: hidden; position: relative;
  background: {BG}; color: {INK}; font-family: "Inter", sans-serif; }}
.velo {{ position: absolute; inset: 0;
  background: radial-gradient(220px 200px at 0% 0%, #111a2b 0%, transparent 72%),
              radial-gradient(200px 160px at 100% 100%, rgba(34, 211, 166, .07) 0%, transparent 70%); }}
.rejilla {{ position: absolute; left: -6px; right: -6px; top: 172px; height: 76px;
  display: grid; grid-template-columns: repeat(13, 1fr); gap: 4px;
  -webkit-mask: linear-gradient(180deg, transparent 0%, #000 50%, #000 66%, transparent 100%); }}
.rejilla i {{ height: 6px; border-radius: 1.5px; background: linear-gradient(160deg, #161f2e, #0f1520);
  box-shadow: 0 1px 0 #090d14; }}
.rejilla i.a {{ background: linear-gradient(180deg, rgba(255,255,255,.3), transparent 40%),
  linear-gradient(160deg, #3a63ad, #23468a); box-shadow: 0 1px 0 #172f60; }}
.rejilla i.o {{ background: linear-gradient(180deg, rgba(255,255,255,.3), transparent 40%),
  linear-gradient(160deg, #1f8a73, #135e4f); box-shadow: 0 1px 0 #0c3f35; }}
.rejilla i.b {{ background: linear-gradient(180deg, rgba(255,255,255,.4), transparent 40%),
  linear-gradient(160deg, #6fa0ff, #2f67e0); box-shadow: 0 1px 0 #1e46a6; }}
.icono {{ position: absolute; left: 16px; top: 20px; width: 54px; height: 54px;
  filter: drop-shadow(0 8px 16px rgba(0, 0, 0, .55)); }}
.icono svg, .firma svg {{ display: block; width: 100%; height: 100%; }}
h1 {{ position: absolute; left: 16px; right: 12px; top: 88px; margin: 0;
  font: 600 21px/1.05 "Outfit", sans-serif; letter-spacing: -.02em; color: {INK}; }}
p.linea {{ position: absolute; left: 16px; right: 14px; top: 117px; margin: 0;
  font: 400 10.5px/1.42 "Inter", sans-serif; color: {MUTED}; }}
p.version {{ position: absolute; left: 16px; bottom: 39px; margin: 0;
  font: 500 8px "Inter", sans-serif; letter-spacing: .06em; color: {MUTED}; }}
.raya {{ position: absolute; left: 16px; right: 16px; bottom: 32px; height: 1px; background: {LINE}; }}
.firma {{ position: absolute; left: 16px; right: 16px; bottom: 14px; height: {(BANNER[0] - 32) * 21 / 263.15:.2f}px; }}
</style></head><body>
<div class="velo"></div>
<div class="rejilla">{_rejilla(13, 8)}</div>
<div class="icono">{_svg("pentaho-live-icono.svg")}</div>
<h1>Pentaho Live</h1>
<p class="linea">{LINEA}</p>
<p class="version">v{version}</p>
<div class="raya"></div>
<div class="firma">{_svg("pentaho-live-oscuro.svg")}</div>
</body></html>"""


def _pagina_icono(lado: float, ancho: int, alto: int, fondo: str) -> str:
    return (f'<!DOCTYPE html><html><head><meta charset="utf-8"><style>html, body {{ margin: 0; }} '
            f'body {{ width: {ancho}px; height: {alto}px; background: {fondo}; overflow: hidden; }} '
            f'div {{ position: absolute; width: {lado}px; height: {lado}px; '
            f'left: {(ancho - lado) / 2}px; top: {(alto - lado) / 2}px; }} '
            f'svg {{ display: block; width: 100%; height: 100%; }}</style></head>'
            f'<body><div>{_svg("pentaho-live-icono.svg")}</div></body></html>')


def _captura(navegador, html: str, ancho: int, alto: int, escala: int, transparente: bool) -> Image.Image:
    pagina = navegador.new_page(viewport={"width": ancho, "height": alto}, device_scale_factor=escala)
    try:
        pagina.set_content(html)
        pagina.evaluate("document.fonts.ready")
        png = pagina.screenshot(omit_background=transparente,
                                clip={"x": 0, "y": 0, "width": ancho, "height": alto})
        if "@font-face" in html:
            for consulta in ('600 21px "Outfit"', '400 10px "Inter"'):
                if not pagina.evaluate(f"document.fonts.check({consulta!r})"):
                    raise RuntimeError(f"La letra no cargó: {consulta}")
    finally:
        pagina.close()
    return Image.open(io.BytesIO(png))


def _guardar_ico(navegador) -> None:
    marcos = {}
    for t in ICO_TAMANOS:
        marco = _captura(navegador, _pagina_icono(t, t, t, "transparent"), t, t, 1, True).convert("RGBA")
        if marco.size != (t, t):
            raise RuntimeError(f"El marco de {t} px salió de {marco.size}")
        marcos[t] = marco
    grande = marcos[max(ICO_TAMANOS)]
    otros = [marcos[t] for t in ICO_TAMANOS if t != max(ICO_TAMANOS)]
    grande.save(ICO, format="ICO", sizes=[(t, t) for t in ICO_TAMANOS], append_images=otros)


def tamanos_ico(ruta: Path) -> list[int]:
    """Los lados de los marcos que trae un .ico (lee el directorio del archivo)."""
    datos = ruta.read_bytes()
    _, tipo, n = struct.unpack("<HHH", datos[:6])
    if tipo != 1:
        raise ValueError(f"{ruta} no es un .ico")
    return sorted((datos[6 + 16 * i] or 256) for i in range(n))


def generar(salida: Path, version: str) -> list[Path]:
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError(f"Versión inválida: {version!r}")
    salida.mkdir(parents=True, exist_ok=True)
    escritos = []
    with sync_playwright() as p:
        # Sin ClearType: el suavizado subpíxel deja bordes de color en el texto del BMP.
        navegador = p.chromium.launch(channel="chrome", headless=True, args=["--disable-lcd-text"])
        try:
            _guardar_ico(navegador)
            escritos.append(ICO)

            html = _pagina_banner(version)
            for nombre, escala in (("banner.bmp", 1), ("banner@2x.bmp", 2)):
                imagen = _captura(navegador, html, *BANNER, escala, False).convert("RGB")
                ruta = salida / nombre
                imagen.save(ruta, "BMP")
                escritos.append(ruta)

            # El ícono ocupa el lado corto menos un margen, centrado sobre blanco.
            for nombre, escala in (("logo.bmp", 1), ("logo@2x.bmp", 2)):
                ancho, alto = LOGO
                lado = min(ancho, alto) - 4
                imagen = _captura(navegador, _pagina_icono(lado, ancho, alto, "#ffffff"),
                                  ancho, alto, escala, False).convert("RGB")
                ruta = salida / nombre
                imagen.save(ruta, "BMP")
                escritos.append(ruta)
        finally:
            navegador.close()
    return escritos


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--salida", type=Path, default=REPO / "build" / "instalador" / "staging" / "asistente",
                        help="carpeta de los BMP del asistente (default: build/instalador/staging/asistente)")
    parser.add_argument("--version", default=None,
                        help="versión que va en el banner (default: el archivo VERSION del repo)")
    args = parser.parse_args()
    version = args.version or (REPO / "VERSION").read_text(encoding="ascii").strip()
    for ruta in generar(args.salida, version):
        if ruta.suffix == ".ico":
            print(f"Icono: {ruta} ({', '.join(map(str, tamanos_ico(ruta)))} px)")
        else:
            with Image.open(ruta) as im:
                print(f"Imagen del asistente: {ruta} ({im.width}x{im.height}, {im.mode})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
