#!/usr/bin/env python3
"""Compara lo que ha cargado una ETL de «Grano de Oro» con el resultado esperado.

Lee datos/resultado_esperado.json (lo escribe generar_datos.py) y lo compara con:

    salida/grano_de_oro_dw.sqlite             modelo estrella y tablas de KPI
    salida/rechazos_ventas.csv                filas rechazadas con su motivo
    salida/kpi_ventas_tienda_categoria.csv    KPI en CSV
    salida/kpi_ticket_medio_tienda.csv

Sirve para la solución de referencia y para cualquier otra que siga los nombres del enunciado
(por ejemplo, la que Claude construye en directo en Spoon). Solo usa la biblioteca estándar.

Uso:
    python comprobar_resultados.py
    python comprobar_resultados.py --salida otra/carpeta

Código de salida: 0 si todo cuadra, 1 si algo no cuadra.
"""

import argparse
import csv
import json
import sqlite3
import sys
from collections import Counter
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

AQUI = Path(__file__).resolve().parent


def euros(valor):
    return str(Decimal(str(valor)).quantize(Decimal("0.01"), ROUND_HALF_UP))


class Informe:
    def __init__(self):
        self.filas = []

    def comparar(self, que, esperado, obtenido):
        self.filas.append((que, str(esperado), str(obtenido), esperado == obtenido))

    def imprimir(self):
        ancho = max(len(f[0]) for f in self.filas)
        ancho_e = max(len("Esperado"), max(len(f[1]) for f in self.filas))
        print("%-*s  %*s  %*s  %s" % (ancho, "Comprobación", ancho_e, "Esperado", ancho_e, "Obtenido", ""))
        print("-" * (ancho + 2 * ancho_e + 10))
        for que, esp, obt, ok in self.filas:
            print("%-*s  %*s  %*s  %s" % (ancho, que, ancho_e, esp, ancho_e, obt, "OK" if ok else "FALLA"))
        fallos = sum(1 for f in self.filas if not f[3])
        print()
        if fallos:
            print("%d de %d comprobaciones NO cuadran." % (fallos, len(self.filas)))
        else:
            print("Las %d comprobaciones cuadran." % len(self.filas))
        return fallos == 0


def leer_csv(ruta):
    with open(ruta, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f, delimiter=";"))


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--salida", default=str(AQUI / "salida"), help="carpeta con la salida de la ETL")
    p.add_argument("--esperado", default=str(AQUI / "datos" / "resultado_esperado.json"))
    args = p.parse_args()

    salida = Path(args.salida)
    with open(args.esperado, encoding="utf-8") as f:
        r = json.load(f)
    e = r["esperado"]
    inf = Informe()

    bd = salida / "grano_de_oro_dw.sqlite"
    if not bd.exists():
        print("No existe %s: ejecuta antes la ETL." % bd)
        return 1
    con = sqlite3.connect(str(bd))

    def uno(sql):
        try:
            return con.execute(sql).fetchone()[0]
        except sqlite3.Error as ex:
            return "error: %s" % ex

    # Recuentos del modelo estrella
    for tabla in ("dim_tienda", "dim_producto", "dim_fecha", "hechos_ventas"):
        inf.comparar("filas en %s" % tabla, e[tabla], uno("SELECT COUNT(*) FROM %s" % tabla))

    # Calidad del modelo
    categorias = [x[0] for x in con.execute(
        "SELECT DISTINCT categoria FROM dim_producto ORDER BY categoria")]
    inf.comparar("categorías de dim_producto", "las %d canónicas" % len(e["categorias"]),
                 "las %d canónicas" % len(categorias) if categorias == e["categorias"]
                 else ", ".join(categorias))
    inf.comparar("claves (id_ticket, linea) repetidas en hechos", 0, uno(
        "SELECT COUNT(*) FROM (SELECT id_ticket, linea FROM hechos_ventas "
        "GROUP BY id_ticket, linea HAVING COUNT(*) > 1)"))
    inf.comparar("hechos con todas sus dimensiones", e["hechos_ventas"], uno(
        "SELECT COUNT(*) FROM hechos_ventas h "
        "JOIN dim_fecha f ON f.sk_fecha = h.sk_fecha "
        "JOIN dim_tienda t ON t.sk_tienda = h.sk_tienda "
        "JOIN dim_producto p ON p.sk_producto = h.sk_producto"))
    inf.comparar("importe total en hechos_ventas", e["importe_total_cadena"],
                 euros(uno("SELECT ROUND(SUM(importe), 2) FROM hechos_ventas")))
    metodo = dict(con.execute("SELECT metodo_pago, COUNT(*) FROM hechos_ventas GROUP BY metodo_pago"))
    for m, n in e["hechos_por_metodo_pago"].items():
        inf.comparar("hechos con metodo_pago = %s" % m, n, metodo.pop(m, 0))
    inf.comparar("otros valores de metodo_pago", "-", ", ".join(sorted(metodo)) or "-")

    # Rechazos
    ruta_rech = salida / "rechazos_ventas.csv"
    if ruta_rech.exists():
        rechazos = leer_csv(ruta_rech)
        inf.comparar("filas en rechazos_ventas.csv", e["rechazos_total"], len(rechazos))
        motivos = Counter(f.get("motivo_rechazo") for f in rechazos)
        for m, n in e["rechazos_por_motivo"].items():
            inf.comparar("rechazos con motivo %s" % m, n, motivos.pop(m, 0))
        inf.comparar("otros motivos de rechazo", "-", ", ".join(sorted(str(m) for m in motivos)) or "-")
        inf.comparar("hechos + rechazos = líneas del fichero", r["ficheros"]["ventas"]["lineas"],
                     uno("SELECT COUNT(*) FROM hechos_ventas") + len(rechazos))
    else:
        inf.comparar("existe rechazos_ventas.csv", "sí", "no")

    # KPI: tablas
    esperado_tm = {k["tienda"]: k for k in e["kpi_ticket_medio_tienda"]}
    esperado_tc = {(k["tienda"], k["categoria"]): k for k in e["kpi_ventas_tienda_categoria"]}
    try:
        tm = {t: (euros(i), n, euros(m)) for t, i, n, m in con.execute(
            "SELECT tienda, importe_total, num_tickets, ticket_medio FROM kpi_ticket_medio_tienda")}
        for t, k in esperado_tm.items():
            inf.comparar("kpi_ticket_medio_tienda · %s" % t,
                         "%s / %d / %s" % (k["importe_total"], k["num_tickets"], k["ticket_medio"]),
                         "%s / %s / %s" % tm[t] if t in tm else "falta")
        tc = {(t, c): (u, euros(i)) for t, c, u, i in con.execute(
            "SELECT tienda, categoria, unidades, importe FROM kpi_ventas_tienda_categoria")}
        bien = sum(1 for clave, k in esperado_tc.items()
                   if tc.get(clave) == (k["unidades"], k["importe"]))
        inf.comparar("kpi_ventas_tienda_categoria: filas que cuadran", len(esperado_tc), bien)
        inf.comparar("kpi_ventas_tienda_categoria: filas", len(esperado_tc), len(tc))
    except sqlite3.Error as ex:
        inf.comparar("tablas de KPI", "existen", "error: %s" % ex)

    # KPI: CSV
    for nombre, comprobar in (
        ("kpi_ticket_medio_tienda.csv",
         lambda f: esperado_tm.get(f["tienda"]) is not None
         and (euros(f["importe_total"]), int(f["num_tickets"]), euros(f["ticket_medio"]))
         == (esperado_tm[f["tienda"]]["importe_total"], esperado_tm[f["tienda"]]["num_tickets"],
             esperado_tm[f["tienda"]]["ticket_medio"])),
        ("kpi_ventas_tienda_categoria.csv",
         lambda f: esperado_tc.get((f["tienda"], f["categoria"])) is not None
         and (int(f["unidades"]), euros(f["importe"]))
         == (esperado_tc[(f["tienda"], f["categoria"])]["unidades"],
             esperado_tc[(f["tienda"], f["categoria"])]["importe"])),
    ):
        ruta = salida / nombre
        total = len(esperado_tm) if nombre.startswith("kpi_ticket") else len(esperado_tc)
        if ruta.exists():
            filas = leer_csv(ruta)
            inf.comparar("%s: filas que cuadran" % nombre, total, sum(1 for f in filas if comprobar(f)))
        else:
            inf.comparar("existe %s" % nombre, "sí", "no")

    con.close()
    return 0 if inf.imprimir() else 1


if __name__ == "__main__":
    sys.exit(main())
