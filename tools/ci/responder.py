"""Corre un comando en una terminal (pty) y contesta cuando aparece un texto, como una persona.

Uso: python3 -I tools/ci/responder.py --espera "[Y/n]" --respuesta y --log salida.log -- bash instalar.sh ...

Escribe la respuesta (más Enter) recién cuando el texto aparece en la salida: con la respuesta metida de
antemano (printf 'y' | script ...) el read del script no la recibía. Muestra y guarda la salida tal cual
(con \\r) y sale con el código del comando. Solo macOS / Linux.
"""
import argparse
import os
import pty
import sys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--espera", required=True, help="texto que tiene que aparecer antes de contestar")
    ap.add_argument("--respuesta", required=True, help="lo que se escribe (se agrega Enter)")
    ap.add_argument("--log", required=True)
    ap.add_argument("comando", nargs=argparse.REMAINDER)
    a = ap.parse_args()
    comando = a.comando[1:] if a.comando[:1] == ["--"] else a.comando
    if not comando:
        ap.error("falta el comando")

    pid, fd = pty.fork()
    if pid == 0:
        os.execvp(comando[0], comando)

    espera = a.espera.encode()
    visto = b""
    contestado = False
    with open(a.log, "wb") as log:
        while True:
            try:
                datos = os.read(fd, 4096)
            except OSError:  # EIO: el comando terminó y se cerró la terminal
                break
            if not datos:
                break
            log.write(datos)
            sys.stdout.buffer.write(datos)
            sys.stdout.flush()
            if not contestado:
                visto = (visto + datos)[-4096:]
                if espera in visto:
                    os.write(fd, a.respuesta.encode() + b"\n")
                    contestado = True
    _, estado = os.waitpid(pid, 0)
    codigo = os.waitstatus_to_exitcode(estado)
    if not contestado:
        print(f"\n[responder] no apareció {a.espera!r}: no se contestó nada", flush=True)
    return codigo


if __name__ == "__main__":
    sys.exit(main())
