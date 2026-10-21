"""Validates catalogo/glosario-clase.json (the class glossary of class mode, docs/modo-clase-diseno.md §7).

Checks that:
  - every step id exists in steps.json (id or ids_alternativos), so the glossary never names a type
    that PDI 9.4 does not have;
  - every step has en, es and pt with a non-empty one-line what, why and analogy;
  - every watch_out code (and every any_step code) is a real heading (### C13. ...) of
    manual/03-errores-comunes.md;
  - there are at least MINIMO steps, and lists the steps of steps-comunes.md without an entry.

The texts are written once and reviewed by hand (the translations included): this script does not
generate them, it keeps them honest when the catalog or the error manual changes.

Usage: python glosario.py [<dir_catalogo>]   (exit 1 if something is wrong)
"""
import json
import os
import re
import sys

MINIMO = 40
IDIOMAS = ("en", "es", "pt")
CAMPOS = ("what", "why", "analogy")
MAX_LARGO = 160  # una linea en una nota del lienzo

CAT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "claude", "skills", "pentaho-etl", "catalogo")
SKILL = os.path.dirname(os.path.abspath(CAT))


def leer(ruta):
    with open(ruta, encoding="utf-8-sig") as f:  # tolera el BOM de algun editor, como McpResources
        return f.read()


def main():
    errores = []
    glos = json.loads(leer(os.path.join(CAT, "glosario-clase.json")))
    steps = json.loads(leer(os.path.join(CAT, "steps.json")))
    ids = {s["id"] for s in steps} | {a for s in steps for a in s.get("ids_alternativos", [])}
    manual = leer(os.path.join(SKILL, "manual", "03-errores-comunes.md"))
    codigos = set(re.findall(r"^### ([A-E]\d+)\.", manual, re.M))
    comunes = re.findall(r"^### .+ — `([A-Za-z0-9_]+)`\s*$", leer(os.path.join(CAT, "steps-comunes.md")), re.M)

    if tuple(glos.get("languages", ())) != IDIOMAS:
        errores.append("languages debe ser %s" % list(IDIOMAS))
    for c in glos.get("any_step", []):
        if c not in codigos:
            errores.append("any_step: el codigo %s no esta en 03-errores-comunes.md" % c)

    pasos = glos.get("steps", {})
    for sid, e in pasos.items():
        if sid not in ids:
            errores.append("%s: no es un id de steps.json" % sid)
        wo = e.get("watch_out")
        if not isinstance(wo, list):
            errores.append("%s: watch_out debe ser una lista de codigos" % sid)
            wo = []
        for c in wo:
            if c not in codigos:
                errores.append("%s: el codigo %s no esta en 03-errores-comunes.md" % (sid, c))
        if len(set(wo)) != len(wo):
            errores.append("%s: codigos repetidos en watch_out" % sid)
        for lang in IDIOMAS:
            t = e.get(lang)
            if not isinstance(t, dict):
                errores.append("%s: falta el idioma %s" % (sid, lang))
                continue
            for campo in CAMPOS:
                v = t.get(campo)
                if not isinstance(v, str) or not v.strip():
                    errores.append("%s/%s: falta %s" % (sid, lang, campo))
                elif "\n" in v or len(v) > MAX_LARGO:
                    errores.append("%s/%s: %s no cabe en una linea (%d caracteres)" % (sid, lang, campo, len(v)))
        sobran = set(e) - set(IDIOMAS) - {"watch_out"}
        if sobran:
            errores.append("%s: claves desconocidas %s" % (sid, sorted(sobran)))

    if len(pasos) < MINIMO:
        errores.append("solo %d steps (minimo %d)" % (len(pasos), MINIMO))
    faltan = [s for s in comunes if s not in pasos]

    print("glosario-clase.json: %d steps, %d codigos del manual citados, %d steps de steps-comunes.md sin entrada%s"
          % (len(pasos), len({c for e in pasos.values() for c in e.get("watch_out", [])}), len(faltan),
             (" (" + ", ".join(faltan) + ")") if faltan else ""))
    for e in errores:
        print("ERROR " + e)
    return 1 if errores else 0


if __name__ == "__main__":
    sys.exit(main())
