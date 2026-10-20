"""Muestra cómo vienen guardados en un zip remoto los permisos de algunos archivos, sin bajarlo entero.

Lee solo el directorio central del zip con pedidos HTTP Range (unos pocos MB de los ~380 del PDI).
Sirve para saber si el zip del release trae spoon.sh y la .app como ejecutables (0755) o si salieron
sin permisos Unix (armado en Windows: «MS-DOS», todo 0666), que es lo que les pasó a la 0.4.0 y la 0.4.1.

Uso:  python -I tools/ci/permisos_zip.py <url del zip> <final de ruta> [<final de ruta> ...]
      (ej.: .../pdi-ce-9.4-con-pentaho-mcp.zip data-integration/spoon.sh Contents/MacOS/JavaApplicationStub)
Código de salida: 0 si todos los pedidos son ejecutables, 1 si alguno no, 2 si alguno no está en el zip.
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
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    remoto = ZipRemoto(sys.argv[1])
    # un búfer grande: zipfile lee el directorio central de a pedazos chicos
    with zipfile.ZipFile(io.BufferedReader(remoto, buffer_size=4 << 20)) as z:
        entradas = z.infolist()
        codigo = 0
        for buscado in sys.argv[2:]:
            info = next((i for i in entradas if i.filename.endswith(buscado)), None)
            if info is None:
                print(f"{buscado}: NO ESTÁ en el zip")
                codigo = 2
                continue
            modo = info.external_attr >> 16
            sistema = SISTEMAS.get(info.create_system, str(info.create_system))
            ejecutable = info.create_system == 3 and bool(modo & stat.S_IXUSR)
            permisos = stat.filemode(modo) if modo else "(sin permisos Unix)"
            print(f"{info.filename}: creado en {sistema}, {permisos} -> "
                  f"{'ejecutable' if ejecutable else 'NO ejecutable al descomprimir'}")
            if not ejecutable and codigo == 0:
                codigo = 1
    print(f"({len(entradas)} entradas; leídos {remoto.bajado / 1048576:.1f} MB de {remoto.tam / 1048576:.0f} MB)")
    return codigo


if __name__ == "__main__":
    sys.exit(main())
