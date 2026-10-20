"""Arma pdi-ce-9.4-con-pentaho-mcp.zip (el PDI completo con el plugin) desde una carpeta data-integration.

Uso:  python -I tools/armar-zip-pdi.py <carpeta que contiene data-integration> <zip de salida>
      (en el release: instalador/staging  spoon-plugin/dist/pdi-ce-9.4-con-pentaho-mcp.zip)

Por qué un script: armado en Windows, un zip guarda los permisos como «MS-DOS» y todo sale 0666, así que en
macOS/Linux `spoon.sh` no se puede ejecutar y la .app no abre (pasó con la 0.4.0 y la 0.4.1). Acá cada
entrada se marca como Unix con los permisos del zip original de PDI 9.4: los *.sh, spoon.command y lo que
está en Contents/MacOS/ ejecutables (0755), el resto 0644 y las carpetas 0755. Al final controla que no
haya datos personales en los archivos de texto ni el conector de MySQL (no se puede redistribuir).
"""
from __future__ import annotations

import os
import sys
import time
import zipfile
from pathlib import PurePosixPath

EJECUTABLE = 0o100755
ARCHIVO = 0o100644
CARPETA = 0o040755
UNIX = 3  # ZipInfo.create_system: 0 = MS-DOS, 3 = Unix
NO_TEXTO = (".jar", ".zip", ".png", ".gif", ".jpg", ".svg", ".dll", ".so", ".exe", ".ico", ".jnilib",
            ".dylib", ".icns", ".xlsx", ".xls")
SOSPECHOSOS = (b"ivanc", b"OneDrive", b"david.castano", b"Castano", "Castaño".encode())


def es_ejecutable(ruta: str) -> bool:
    p = PurePosixPath(ruta)
    return p.suffix in (".sh", ".command") or "Contents/MacOS" in p.parent.as_posix()


def entrada(ruta: str, modo: int, fecha) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(ruta, date_time=fecha)
    info.create_system = UNIX
    info.external_attr = (modo << 16) | (0x10 if modo == CARPETA else 0)
    info.compress_type = zipfile.ZIP_STORED if modo == CARPETA else zipfile.ZIP_DEFLATED
    return info


def armar(base: str, destino: str) -> None:
    raiz = os.path.join(base, "data-integration")
    if not os.path.isfile(os.path.join(raiz, "Spoon.bat")):
        sys.exit(f"{raiz} no parece un data-integration (no tiene Spoon.bat)")
    for actual, _, archivos in os.walk(raiz):
        for nombre in archivos:
            if "mysql-connector" in nombre.lower():
                sys.exit(f"{os.path.join(actual, nombre)}: el conector de MySQL no se puede redistribuir;"
                         " sacalo de la copia")
    temporal = destino + ".tmp"
    ejecutables = 0
    with zipfile.ZipFile(temporal, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for actual, carpetas, archivos in os.walk(raiz):
            carpetas.sort()
            archivos.sort()
            rel = os.path.relpath(actual, base).replace(os.sep, "/")
            z.writestr(entrada(rel + "/", CARPETA, (1980, 1, 1, 0, 0, 0)), b"")
            for nombre in archivos:
                origen = os.path.join(actual, nombre)
                ruta = f"{rel}/{nombre}"
                modo = EJECUTABLE if es_ejecutable(ruta) else ARCHIVO
                ejecutables += modo == EJECUTABLE
                fecha = max((1980, 1, 1, 0, 0, 0), tuple(time.localtime(os.path.getmtime(origen))[:6]))
                with open(origen, "rb") as f:
                    z.writestr(entrada(ruta, modo, fecha), f.read())
    sospechosos = []
    with zipfile.ZipFile(temporal) as z:
        for info in z.infolist():
            if info.is_dir() or info.file_size > 5_000_000 or info.filename.lower().endswith(NO_TEXTO):
                continue
            datos = z.read(info)
            sospechosos += [(info.filename, p.decode(errors="replace")) for p in SOSPECHOSOS if p in datos]
    if sospechosos:
        os.remove(temporal)
        sys.exit(f"datos personales en el zip: {sospechosos[:10]}")
    os.replace(temporal, destino)
    print(f"{destino}: {os.path.getsize(destino) / 1048576:.0f} MB, {ejecutables} ejecutables (0755)")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    armar(sys.argv[1], sys.argv[2])
