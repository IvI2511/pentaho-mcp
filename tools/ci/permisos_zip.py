"""Muestra cómo vienen guardados en un zip los permisos de algunos archivos; si es remoto, sin bajarlo entero.

De un zip remoto lee solo el directorio central con pedidos HTTP Range (unos pocos MB de los ~380 del PDI).
Sirve para saber si el zip del release trae spoon.sh y la .app como ejecutables (0755) o si salieron
sin permisos Unix (armado en Windows: «MS-DOS», todo 0666), que es lo que les pasó a la 0.4.0 y la 0.4.1.
También acepta un zip local (el que arma tools/armar-zip-pdi.py en la prueba con «plugin: rama»).

Uso:  python -I tools/ci/permisos_zip.py [--modo 0755] <url o ruta del zip> <final de ruta> [<final de ruta> ...]
      (ej.: .../pdi-ce-9.4-con-pentaho-mcp.zip data-integration/spoon.sh Contents/MacOS/JavaApplicationStub)
Código de salida: 0 si todos los pedidos son ejecutables (con --modo: si tienen justo esos permisos), 1 si
alguno no, 2 si alguno no está en el zip.
"""
from __future__ import annotations

import io
import stat
import sys
import urllib.request
import zipfile

SISTEMAS = {0: "MS-DOS", 3: "Unix"}


class ZipRemoto(io.RawIOBase):
    """Archivo de solo lectura sobre HTTP: cada read() es un pedido Range."""

    def __init__(self, url: str) -> None:
        with urllib.request.urlopen(urllib.request.Request(url, method="HEAD")) as r:
            self.url = r.url  # la URL final, después de las redirecciones de GitHub
            self.tam = int(r.headers["Content-Length"])
        self.pos = 0
        self.bajado = 0

    def seekable(self) -> bool:
        return True

    def readable(self) -> bool:
        return True

    def seek(self, desplazamiento: int, desde: int = io.SEEK_SET) -> int:
        self.pos = {io.SEEK_SET: 0, io.SEEK_CUR: self.pos, io.SEEK_END: self.tam}[desde] + desplazamiento
        return self.pos

    def tell(self) -> int:
        return self.pos

    def readinto(self, b) -> int:
        if self.pos >= self.tam or len(b) == 0:
            return 0
        fin = min(self.tam, self.pos + len(b)) - 1
        pedido = urllib.request.Request(self.url, headers={"Range": f"bytes={self.pos}-{fin}"})
        with urllib.request.urlopen(pedido) as r:
            datos = r.read()
        b[:len(datos)] = datos
        self.pos += len(datos)
        self.bajado += len(datos)
        return len(datos)


def main() -> int:
    args = sys.argv[1:]
    modo_pedido = None
    if args[:1] == ["--modo"] and len(args) > 1:
        modo_pedido = int(args[1], 8)
        args = args[2:]
    if len(args) < 2:
        sys.exit(__doc__)
    remoto = ZipRemoto(args[0]) if args[0].startswith(("http://", "https://")) else None
    # un búfer grande: zipfile lee el directorio central de a pedazos chicos
    with zipfile.ZipFile(io.BufferedReader(remoto, buffer_size=4 << 20) if remoto else args[0]) as z:
        entradas = z.infolist()
        codigo = 0
        for buscado in args[1:]:
            info = next((i for i in entradas if i.filename.endswith(buscado)), None)
            if info is None:
                print(f"{buscado}: NO ESTÁ en el zip")
                codigo = 2
                continue
            modo = info.external_attr >> 16
            sistema = SISTEMAS.get(info.create_system, str(info.create_system))
            ejecutable = info.create_system == 3 and bool(modo & stat.S_IXUSR)
            permisos = f"{stat.filemode(modo)} ({stat.S_IMODE(modo):04o})" if modo else "(sin permisos Unix)"
            print(f"{info.filename}: creado en {sistema}, {permisos} -> "
                  f"{'ejecutable' if ejecutable else 'NO ejecutable al descomprimir'}")
            correcto = ejecutable if modo_pedido is None else (
                info.create_system == 3 and stat.S_IMODE(modo) == modo_pedido)
            if not correcto:
                if modo_pedido is not None:
                    print(f"   se esperaba {modo_pedido:04o}")
                codigo = codigo or 1
    leido = f"; leídos {remoto.bajado / 1048576:.1f} MB de {remoto.tam / 1048576:.0f} MB" if remoto else ""
    print(f"({len(entradas)} entradas{leido})")
    return codigo


if __name__ == "__main__":
    sys.exit(main())
