#!/usr/bin/env python3
"""Genera los datos de entrada del ejemplo «Grano de Oro» (ventas de septiembre de 2026).

Solo usa la biblioteca estándar de Python (3.8 o posterior). La semilla es fija: cada ejecución
produce exactamente los mismos datos, así que se puede volver a ejecutar sin miedo.

Escribe en la carpeta datos/ (junto a este script):

    ventas_2026_09.csv        líneas de ticket del TPV, con errores inyectados en cantidades conocidas
    productos.xlsx            catálogo de productos (texto sin estandarizar)
    tiendas.json              las 4 tiendas de la cadena (JSON anidado)
    LEEME.md                  descripción de los ficheros y RESULTADO ESPERADO de la ETL
    resultado_esperado.json   lo mismo en formato máquina (lo usa comprobar_resultados.py)

Uso:
    python generar_datos.py
"""

import csv
import datetime as dt
import json
import random
import zipfile
from collections import Counter, defaultdict
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path
from xml.sax.saxutils import escape

SEMILLA = 20260901
CARPETA = Path(__file__).resolve().parent / "datos"

ANIO, MES, DIAS = 2026, 9, 30
FICHERO_VENTAS = "ventas_2026_09.csv"

# Volumen y errores inyectados (cantidades EXACTAS: son el resultado esperado)
LINEAS_UNICAS = 2940            # líneas de ticket distintas
N_DUPLICADOS = 60               # copias exactas de líneas válidas (2 % del fichero)
N_TIENDA_VACIA = 15             # id_tienda vacío
N_CANTIDAD_NEGATIVA = 11        # cantidad < 0
N_CANTIDAD_CERO = 7             # cantidad = 0
N_PRODUCTO_INEXISTENTE = 22     # código de producto que no está en el catálogo

# Rarezas de formato (no son errores: la ETL debe corregirlas)
P_FECHA_DMY = 0.30              # por ticket: fecha dd/MM/aaaa en lugar de aaaa-MM-dd
P_PRECIO_COMA = 0.18            # por línea: precio con coma decimal (2,50)

CENTIMO = Decimal("0.01")

# ---------------------------------------------------------------------------------------------
# Datos maestros (ficticios)
# ---------------------------------------------------------------------------------------------

TIENDAS = [
    # id, nombre, barrio, distrito, cp, apertura, m2, plazas, peso, factor laborable, factor finde
    ("T01", "Malasaña", "Universidad", "Centro", "28004", "2017-04-03", 95, 34, 1.25, 1.00, 1.35),
    ("T02", "Chamberí", "Trafalgar", "Chamberí", "28010", "2018-10-15", 80, 28, 1.00, 1.00, 1.00),
    ("T03", "Salamanca", "Recoletos", "Salamanca", "28001", "2020-02-24", 110, 40, 1.15, 1.15, 0.60),
    ("T04", "Retiro", "Ibiza", "Retiro", "28009", "2023-05-08", 70, 24, 0.80, 0.90, 1.50),
]

# código, nombre, categoría canónica, precio de venta, popularidad
PRODUCTOS = [
    ("CAF-001", "Espresso", "Café", "1.50", 9),
    ("CAF-002", "Cortado", "Café", "1.70", 10),
    ("CAF-003", "Café con leche", "Café", "2.10", 16),
    ("CAF-004", "Cappuccino", "Café", "2.60", 9),
    ("CAF-005", "Latte", "Café", "2.80", 8),
    ("CAF-006", "Flat white", "Café", "3.00", 6),
    ("CAF-007", "Americano", "Café", "1.90", 7),
    ("CAF-008", "Café bombón", "Café", "2.20", 3),
    ("INF-001", "Té verde", "Infusiones", "2.00", 4),
    ("INF-002", "Té negro", "Infusiones", "2.00", 3),
    ("INF-003", "Rooibos", "Infusiones", "2.20", 2),
    ("INF-004", "Chai latte", "Infusiones", "3.20", 4),
    ("INF-005", "Matcha latte", "Infusiones", "3.50", 4),
    ("BOL-001", "Croissant", "Bollería", "1.80", 10),
    ("BOL-002", "Napolitana de chocolate", "Bollería", "2.10", 7),
    ("BOL-003", "Magdalena", "Bollería", "1.40", 4),
    ("BOL-004", "Tarta de queso (porción)", "Bollería", "3.90", 5),
    ("BOL-005", "Cookie", "Bollería", "2.30", 5),
    ("BOL-006", "Bizcocho de limón", "Bollería", "2.50", 3),
    ("SAL-001", "Tostada con tomate", "Salado", "2.80", 8),
    ("SAL-002", "Bocadillo de jamón", "Salado", "4.50", 4),
    ("SAL-003", "Sándwich mixto", "Salado", "3.80", 4),
    ("SAL-004", "Pincho de tortilla", "Salado", "3.20", 6),
    ("SAL-005", "Quiche de verduras", "Salado", "4.20", 2),
    ("BEB-001", "Zumo de naranja", "Bebidas", "3.00", 6),
    ("BEB-002", "Agua mineral", "Bebidas", "1.50", 4),
    ("BEB-003", "Limonada casera", "Bebidas", "2.90", 3),
    ("BEB-004", "Smoothie de frutos rojos", "Bebidas", "4.20", 2),
]

# Cómo aparece escrita cada categoría en el Excel (mayúsculas mezcladas y espacios sobrantes)
VARIANTES_CATEGORIA = {
    "Café": ["Café", "café", "CAFÉ", " Café", "café "],
    "Infusiones": ["Infusiones", "infusiones", "INFUSIONES ", " Infusiones"],
    "Bollería": ["Bollería", "bollería", "BOLLERÍA", "Bollería  "],
    "Salado": ["Salado", "salado", "SALADO", " salado "],
    "Bebidas": ["Bebidas", "bebidas", "BEBIDAS", "Bebidas "],
}

CODIGOS_INEXISTENTES = ["CAF-099", "BOL-015", "SAL-021", "BEB-010", "INF-777", "PROMO-2X1"]

METODOS = [("TARJETA", 56), ("EFECTIVO", 24), ("BIZUM", 20)]
# Cómo lo escribe cada TPV (el valor canónico es el de la izquierda)
VARIANTES_METODO = {
    "TARJETA": [("TARJETA", 55), ("tarjeta", 20), ("Tarjeta ", 15), (" tarjeta", 10)],
    "EFECTIVO": [("EFECTIVO", 55), ("efectivo", 20), ("Efectivo ", 15), ("  EFECTIVO", 10)],
    "BIZUM": [("BIZUM", 55), ("Bizum", 25), ("bizum ", 20)],
}

TAMANOS_TICKET = [(1, 38), (2, 34), (3, 18), (4, 10)]
CANTIDADES = [(1, 80), (2, 15), (3, 5)]
CANTIDADES_NEGATIVAS = [(-1, 75), (-2, 25)]

COLUMNAS_VENTAS = ["id_ticket", "linea", "fecha", "id_tienda", "codigo_producto",
                   "cantidad", "precio_unitario", "metodo_pago"]

MOTIVOS = ["DUPLICADO", "TIENDA_VACIA", "CANTIDAD_NO_VALIDA", "TIENDA_DESCONOCIDA",
           "PRODUCTO_DESCONOCIDO", "FECHA_NO_VALIDA"]


# ---------------------------------------------------------------------------------------------
# Utilidades
# ---------------------------------------------------------------------------------------------

def elegir(rng, opciones):
    """Elige un valor de una lista [(valor, peso), ...] usando solo rng.random()."""
    total = sum(p for _, p in opciones)
    r = rng.random() * total
    acumulado = 0
    for valor, peso in opciones:
        acumulado += peso
        if r < acumulado:
            return valor
    return opciones[-1][0]


def muestra_ponderada(rng, productos, n):
    """n productos distintos, con probabilidad proporcional a su popularidad."""
    disponibles = list(productos)
    elegidos = []
    for _ in range(n):
        p = elegir(rng, [(prod, prod[4]) for prod in disponibles])
        elegidos.append(p)
        disponibles.remove(p)
    return elegidos


def euros(valor):
    return str(Decimal(valor).quantize(CENTIMO, ROUND_HALF_UP))


# ---------------------------------------------------------------------------------------------
# Ventas
# ---------------------------------------------------------------------------------------------

def repartir_lineas(rng):
    """Reparte LINEAS_UNICAS entre los 120 pares (día, tienda) según el peso de cada tienda y el
    día de la semana, con un poco de ruido. Método del mayor resto: la suma es exacta."""
    cuotas = []
    for d in range(1, DIAS + 1):
        dia = dt.date(ANIO, MES, d)
        finde = dia.weekday() >= 5
        for t in TIENDAS:
            peso = t[8] * (t[10] if finde else t[9]) * (0.85 + 0.30 * rng.random())
            cuotas.append(((dia, t[0]), peso))
    total = sum(p for _, p in cuotas)
    exactas = [(clave, LINEAS_UNICAS * p / total) for clave, p in cuotas]
    reparto = {clave: int(q) for clave, q in exactas}
    faltan = LINEAS_UNICAS - sum(reparto.values())
    por_resto = sorted(exactas, key=lambda cq: (-(cq[1] - int(cq[1])), cq[0]))
    for clave, _ in por_resto[:faltan]:
        reparto[clave] += 1
    return reparto


def generar_lineas(rng):
    reparto = repartir_lineas(rng)
    lineas = []
    n_ticket = 0
    for d in range(1, DIAS + 1):
        dia = dt.date(ANIO, MES, d)
        for t in TIENDAS:
            pendientes = reparto[(dia, t[0])]
            while pendientes > 0:
                tam = min(pendientes, elegir(rng, TAMANOS_TICKET))
                pendientes -= tam
                n_ticket += 1
                id_ticket = "TK%06d" % n_ticket
                metodo = elegir(rng, METODOS)
                fecha_dmy = rng.random() < P_FECHA_DMY
                for i, prod in enumerate(muestra_ponderada(rng, PRODUCTOS, tam), start=1):
                    lineas.append({
                        "id_ticket": id_ticket,
                        "linea": i,
                        "fecha": dia,
                        "id_tienda": t[0],
                        "codigo_producto": prod[0],
                        "cantidad": elegir(rng, CANTIDADES),
                        "precio_unitario": Decimal(prod[3]),
                        "metodo_pago": metodo,
                        # cómo se escribe en el CSV
                        "fecha_dmy": fecha_dmy,
                        "precio_coma": rng.random() < P_PRECIO_COMA,
                        "metodo_texto": elegir(rng, VARIANTES_METODO[metodo]),
                        # error inyectado (None = línea correcta)
                        "error": None,
                        "detalle": None,
                    })
    assert len(lineas) == LINEAS_UNICAS
    return lineas


def inyectar_errores(rng, lineas):
    """Cada línea recibe como mucho un error; los duplicados son copias de líneas sin error."""
    indices = list(range(len(lineas)))
    rng.shuffle(indices)
    cursor = [0]

    def tomar(n):
        trozo = indices[cursor[0]:cursor[0] + n]
        cursor[0] += n
        return sorted(trozo)

    for i in tomar(N_TIENDA_VACIA):
        lineas[i].update(id_tienda="", error="TIENDA_VACIA", detalle="id_tienda vacío")
    for i in tomar(N_CANTIDAD_NEGATIVA):
        lineas[i].update(cantidad=elegir(rng, CANTIDADES_NEGATIVAS), error="CANTIDAD_NO_VALIDA",
                         detalle="cantidad negativa")
    for i in tomar(N_CANTIDAD_CERO):
        lineas[i].update(cantidad=0, error="CANTIDAD_NO_VALIDA", detalle="cantidad cero")
    for i in tomar(N_PRODUCTO_INEXISTENTE):
        lineas[i].update(codigo_producto=rng.choice(CODIGOS_INEXISTENTES),
                         error="PRODUCTO_DESCONOCIDO", detalle="código de producto inexistente")
    return tomar(N_DUPLICADOS)


def fila_csv(linea):
    if linea["fecha_dmy"]:
        fecha = linea["fecha"].strftime("%d/%m/%Y")
    else:
        fecha = linea["fecha"].isoformat()
    precio = euros(linea["precio_unitario"])
    if linea["precio_coma"]:
        precio = precio.replace(".", ",")
    return [linea["id_ticket"], str(linea["linea"]), fecha, linea["id_tienda"],
            linea["codigo_producto"], str(linea["cantidad"]), precio, linea["metodo_texto"]]


def escribir_ventas(rng, lineas, duplicados):
    """Las copias aparecen más abajo que su original, en posiciones al azar (como un lote
    reenviado por el TPV). Devuelve las filas tal y como quedan en el fichero."""
    ordenables = [(float(i), 0, i) for i in range(len(lineas))]
    for i in duplicados:
        ordenables.append((i + 0.5 + rng.random() * (len(lineas) - i - 1), 1, i))
    ordenables.sort()
    filas = [fila_csv(lineas[i]) for _, _, i in ordenables]
    with open(CARPETA / FICHERO_VENTAS, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter=";", lineterminator="\n")
        w.writerow(COLUMNAS_VENTAS)
        w.writerows(filas)
    return filas


# ---------------------------------------------------------------------------------------------
# Productos (xlsx escrito a mano: un .xlsx es un zip con XML dentro)
# ---------------------------------------------------------------------------------------------

def productos_sucios(rng):
    filas = []
    nombres_con_espacios = 0
    for codigo, nombre, categoria, precio, _ in PRODUCTOS:
        categoria_txt = rng.choice(VARIANTES_CATEGORIA[categoria])
        nombre_txt = nombre
        r = rng.random()
        if r < 0.15:
            nombre_txt = "  " + nombre
        elif r < 0.30:
            nombre_txt = nombre + " "
        if nombre_txt != nombre:
            nombres_con_espacios += 1
        filas.append([codigo, nombre_txt, categoria_txt, Decimal(precio)])
    categorias_sucias = sum(1 for f, p in zip(filas, PRODUCTOS) if f[2] != p[2])
    return filas, nombres_con_espacios, categorias_sucias


def _celda(ref, valor, cadenas, estilo=None):
    s = ' s="%d"' % estilo if estilo is not None else ""
    if isinstance(valor, str):
        if valor not in cadenas:
            cadenas[valor] = len(cadenas)
        return '<c r="%s" t="s"%s><v>%d</v></c>' % (ref, s, cadenas[valor])
    return '<c r="%s"%s><v>%s</v></c>' % (ref, s, valor)


def escribir_xlsx(ruta, hoja, cabecera, filas, anchos):
    letras = "ABCDEFGHIJ"
    cadenas = {}
    n_textos = len(cabecera) + sum(1 for f in filas for v in f if isinstance(v, str))
    xml_filas = []
    celdas = [_celda("%s1" % letras[j], h, cadenas, estilo=1) for j, h in enumerate(cabecera)]
    xml_filas.append('<row r="1">%s</row>' % "".join(celdas))
    for i, fila in enumerate(filas, start=2):
        celdas = []
        for j, v in enumerate(fila):
            ref = "%s%d" % (letras[j], i)
            if isinstance(v, Decimal):
                celdas.append(_celda(ref, v, cadenas, estilo=2))
            else:
                celdas.append(_celda(ref, v, cadenas))
        xml_filas.append('<row r="%d">%s</row>' % (i, "".join(celdas)))
    ultima = "%s%d" % (letras[len(cabecera) - 1], len(filas) + 1)
    cols = "".join('<col min="%d" max="%d" width="%d" customWidth="1"/>' % (j + 1, j + 1, a)
                   for j, a in enumerate(anchos))
    ns = 'xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"'
    ns_r = 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
    cab = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
    rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    hoja_xml = (
        cab + '<worksheet %s %s><dimension ref="A1:%s"/>' % (ns, ns_r, ultima)
        + '<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" '
          'activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>'
        + '<sheetFormatPr defaultRowHeight="15"/><cols>%s</cols>' % cols
        + "<sheetData>%s</sheetData></worksheet>" % "".join(xml_filas))
    orden = sorted(cadenas.items(), key=lambda kv: kv[1])
    compartidas = (
        cab + '<sst %s count="%d" uniqueCount="%d">' % (ns, n_textos, len(orden))
        + "".join('<si><t xml:space="preserve">%s</t></si>' % escape(t) for t, _ in orden)
        + "</sst>")
    estilos = (
        cab + "<styleSheet %s>" % ns
        + '<fonts count="2"><font><sz val="11"/><name val="Calibri"/><family val="2"/></font>'
          '<font><b/><sz val="11"/><name val="Calibri"/><family val="2"/></font></fonts>'
        + '<fills count="2"><fill><patternFill patternType="none"/></fill>'
          '<fill><patternFill patternType="gray125"/></fill></fills>'
        + '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
        + '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
        + '<cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>'
          '<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>'
          '<xf numFmtId="2" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>'
          "</cellXfs>"
        + '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>'
        + "</styleSheet>")
    libro = (cab + "<workbook %s %s><sheets>" % (ns, ns_r)
             + '<sheet name="%s" sheetId="1" r:id="rId1"/></sheets></workbook>' % escape(hoja))
    rels_libro = (
        cab + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        + '<Relationship Id="rId1" Type="%s/worksheet" Target="worksheets/sheet1.xml"/>' % rel
        + '<Relationship Id="rId2" Type="%s/styles" Target="styles.xml"/>' % rel
        + '<Relationship Id="rId3" Type="%s/sharedStrings" Target="sharedStrings.xml"/>' % rel
        + "</Relationships>")
    rels_raiz = (
        cab + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        + '<Relationship Id="rId1" Type="%s/officeDocument" Target="xl/workbook.xml"/>' % rel
        + "</Relationships>")
    tipo = "application/vnd.openxmlformats-officedocument.spreadsheetml"
    tipos = (
        cab + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        + '<Default Extension="xml" ContentType="application/xml"/>'
        + '<Override PartName="/xl/workbook.xml" ContentType="%s.sheet.main+xml"/>' % tipo
        + '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="%s.worksheet+xml"/>' % tipo
        + '<Override PartName="/xl/styles.xml" ContentType="%s.styles+xml"/>' % tipo
        + '<Override PartName="/xl/sharedStrings.xml" ContentType="%s.sharedStrings+xml"/>' % tipo
        + "</Types>")
    partes = [
        ("[Content_Types].xml", tipos),
        ("_rels/.rels", rels_raiz),
        ("xl/workbook.xml", libro),
        ("xl/_rels/workbook.xml.rels", rels_libro),
        ("xl/styles.xml", estilos),
        ("xl/sharedStrings.xml", compartidas),
        ("xl/worksheets/sheet1.xml", hoja_xml),
    ]
    with zipfile.ZipFile(ruta, "w", zipfile.ZIP_DEFLATED) as z:
        for nombre, contenido in partes:
            info = zipfile.ZipInfo(nombre, date_time=(2026, 9, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, contenido.encode("utf-8"))


# ---------------------------------------------------------------------------------------------
# Tiendas
# ---------------------------------------------------------------------------------------------

def escribir_tiendas():
    doc = {
        "cadena": "Grano de Oro",
        "ciudad": "Madrid",
        "actualizado": "2026-09-01",
        "tiendas": [
            {
                "id_tienda": t[0],
                "nombre": t[1],
                "ubicacion": {"barrio": t[2], "distrito": t[3], "codigo_postal": t[4]},
                "fecha_apertura": t[5],
                "superficie_m2": t[6],
                "plazas": t[7],
            }
            for t in TIENDAS
        ],
    }
    with open(CARPETA / "tiendas.json", "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
        f.write("\n")


# ---------------------------------------------------------------------------------------------
# Resultado esperado
# ---------------------------------------------------------------------------------------------

def calcular_esperado(lineas, filas_fichero, productos_xlsx, n_nombres, n_categorias):
    nombre_tienda = {t[0]: t[1] for t in TIENDAS}
    categoria = {p[0]: p[2] for p in PRODUCTOS}

    validas = [ln for ln in lineas if ln["error"] is None]
    rechazos = Counter(ln["error"] for ln in lineas if ln["error"])
    rechazos["DUPLICADO"] = N_DUPLICADOS
    detalle_errores = Counter(ln["detalle"] for ln in lineas if ln["detalle"])

    importe_tienda = defaultdict(Decimal)
    tickets_tienda = defaultdict(set)
    unidades_tc = defaultdict(int)
    importe_tc = defaultdict(Decimal)
    metodo = Counter()
    for ln in validas:
        imp = ln["precio_unitario"] * ln["cantidad"]
        tienda = nombre_tienda[ln["id_tienda"]]
        cat = categoria[ln["codigo_producto"]]
        importe_tienda[tienda] += imp
        tickets_tienda[tienda].add(ln["id_ticket"])
        unidades_tc[(tienda, cat)] += ln["cantidad"]
        importe_tc[(tienda, cat)] += imp
        metodo[ln["metodo_pago"]] += 1

    kpi_tienda = []
    for tienda in sorted(importe_tienda):
        total = importe_tienda[tienda]
        n = len(tickets_tienda[tienda])
        kpi_tienda.append({
            "tienda": tienda,
            "importe_total": euros(total),
            "num_tickets": n,
            "ticket_medio": euros(total / n),
        })
    kpi_tc = [{
        "tienda": t,
        "categoria": c,
        "unidades": unidades_tc[(t, c)],
        "importe": euros(importe_tc[(t, c)]),
    } for (t, c) in sorted(importe_tc)]

    total_cadena = sum(importe_tienda.values(), Decimal(0))
    tickets_cadena = set(ln["id_ticket"] for ln in validas)

    formatos = {
        "fecha_dd_mm_aaaa": sum(1 for f in filas_fichero if "/" in f[2]),
        "fecha_aaaa_mm_dd": sum(1 for f in filas_fichero if "-" in f[2]),
        "precio_con_coma": sum(1 for f in filas_fichero if "," in f[6]),
        "precio_con_punto": sum(1 for f in filas_fichero if "." in f[6]),
        "metodo_pago_tal_cual": dict(sorted(Counter(f[7] for f in filas_fichero).items(),
                                            key=lambda kv: (-kv[1], kv[0]))),
    }

    return {
        "semilla": SEMILLA,
        "ficheros": {
            "ventas": {"fichero": FICHERO_VENTAS, "lineas": len(filas_fichero),
                       "lineas_unicas": len(lineas), "tickets": len(set(ln["id_ticket"] for ln in lineas))},
            "productos": {"fichero": "productos.xlsx", "filas": len(productos_xlsx),
                          "nombres_con_espacios": n_nombres, "categorias_mal_escritas": n_categorias},
            "tiendas": {"fichero": "tiendas.json", "tiendas": len(TIENDAS)},
        },
        "formatos": formatos,
        "errores_inyectados": {
            "duplicados_exactos": N_DUPLICADOS,
            "tienda_vacia": N_TIENDA_VACIA,
            "cantidad_negativa": detalle_errores["cantidad negativa"],
            "cantidad_cero": detalle_errores["cantidad cero"],
            "producto_inexistente": N_PRODUCTO_INEXISTENTE,
        },
        "esperado": {
            "dim_tienda": len(TIENDAS),
            "dim_producto": len(PRODUCTOS),
            "dim_fecha": DIAS,
            "hechos_ventas": len(validas),
            "rechazos_total": sum(rechazos.values()),
            "rechazos_por_motivo": {m: rechazos.get(m, 0) for m in MOTIVOS},
            "hechos_por_metodo_pago": dict(sorted(metodo.items())),
            "categorias": sorted(set(p[2] for p in PRODUCTOS)),
            "importe_total_cadena": euros(total_cadena),
            "tickets_cadena": len(tickets_cadena),
            "ticket_medio_cadena": euros(total_cadena / len(tickets_cadena)),
            "kpi_ticket_medio_tienda": kpi_tienda,
            "kpi_ventas_tienda_categoria": kpi_tc,
        },
    }


def escribir_leeme(r):
    e = r["esperado"]
    f = r["formatos"]
    v = r["ficheros"]["ventas"]
    err = r["errores_inyectados"]
    lineas = []
    w = lineas.append
    w("# Datos de entrada · Grano de Oro, septiembre de 2026")
    w("")
    w("> Fichero generado por `generar_datos.py` (semilla %d). No lo edites a mano: si cambias el" % r["semilla"])
    w("> generador, vuelve a ejecutarlo y este documento se reescribe con los números nuevos.")
    w("")
    w("Todos los datos son **ficticios**. Los errores se inyectan en cantidades exactas para que el")
    w("resultado de la ETL se pueda comprobar al número.")
    w("")
    w("## Ficheros")
    w("")
    w("| Fichero | Formato | Contenido |")
    w("|---|---|---|")
    w("| `%s` | CSV, UTF-8, separador `;`, con cabecera | %d líneas de ticket (%d tickets) de las 4 tiendas |"
      % (v["fichero"], v["lineas"], v["tickets"]))
    w("| `productos.xlsx` | Excel 2007+ (hoja `Productos`), con cabecera | catálogo de %d productos en 5 categorías |"
      % r["ficheros"]["productos"]["filas"])
    w("| `tiendas.json` | JSON anidado, UTF-8 | las %d tiendas (`$.tiendas[*]`, ubicación en un objeto anidado) |"
      % r["ficheros"]["tiendas"]["tiendas"])
    w("| `resultado_esperado.json` | JSON | lo mismo que la sección «Resultado esperado», para `comprobar_resultados.py` |")
    w("")
    w("### `%s`" % v["fichero"])
    w("")
    w("| Columna | Descripción |")
    w("|---|---|")
    w("| `id_ticket` | identificador del ticket (`TK000001`…); un ticket tiene de 1 a 4 líneas |")
    w("| `linea` | número de línea dentro del ticket; `(id_ticket, linea)` identifica la línea |")
    w("| `fecha` | fecha de la venta, en **dos formatos**: `aaaa-MM-dd` y `dd/MM/aaaa` |")
    w("| `id_tienda` | `T01`…`T04` (a veces vacío) |")
    w("| `codigo_producto` | código del catálogo (`CAF-003`…; a veces no existe) |")
    w("| `cantidad` | unidades vendidas (a veces 0 o negativa) |")
    w("| `precio_unitario` | precio de venta en euros, con **punto o coma decimal** (`2.50` / `2,50`) |")
    w("| `metodo_pago` | `TARJETA`, `EFECTIVO` o `BIZUM`, escritos de varias maneras |")
    w("")
    w("### `productos.xlsx`")
    w("")
    w("Columnas `codigo_producto`, `nombre`, `categoria` y `precio_venta` (numérico). %d nombres llevan"
      % r["ficheros"]["productos"]["nombres_con_espacios"])
    w("espacios sobrantes al principio o al final y %d de las %d categorías están escritas con mayúsculas"
      % (r["ficheros"]["productos"]["categorias_mal_escritas"], r["ficheros"]["productos"]["filas"]))
    w("mezcladas o espacios (`café`, `CAFÉ`, ` salado `…). Las categorías canónicas son: %s."
      % ", ".join("`%s`" % c for c in e["categorias"]))
    w("")
    w("### `tiendas.json`")
    w("")
    w("Un objeto con la cadena y la lista `tiendas`; cada tienda trae `id_tienda`, `nombre`,")
    w("`ubicacion.barrio`, `ubicacion.distrito`, `ubicacion.codigo_postal`, `fecha_apertura`,")
    w("`superficie_m2` y `plazas`.")
    w("")
    w("## Errores inyectados en las ventas")
    w("")
    w("Cada línea tiene como mucho un error, y los duplicados son copias exactas (byte a byte) de")
    w("líneas sin error, colocadas más abajo en el fichero, como un lote que el TPV reenvió.")
    w("")
    w("| Error | Líneas | Motivo de rechazo esperado |")
    w("|---|---:|---|")
    w("| Duplicado exacto de otra línea | %d | `DUPLICADO` |" % err["duplicados_exactos"])
    w("| `id_tienda` vacío | %d | `TIENDA_VACIA` |" % err["tienda_vacia"])
    w("| Cantidad negativa | %d | `CANTIDAD_NO_VALIDA` |" % err["cantidad_negativa"])
    w("| Cantidad cero | %d | `CANTIDAD_NO_VALIDA` |" % err["cantidad_cero"])
    w("| Código de producto inexistente | %d | `PRODUCTO_DESCONOCIDO` |" % err["producto_inexistente"])
    w("| **Total** | **%d** | |" % sum(err.values()))
    w("")
    w("## Rarezas de formato (no son errores: hay que corregirlas)")
    w("")
    w("Contadas sobre las %d líneas del fichero, duplicados incluidos." % v["lineas"])
    w("")
    w("| Rareza | Líneas |")
    w("|---|---:|")
    w("| Fecha `dd/MM/aaaa` | %d |" % f["fecha_dd_mm_aaaa"])
    w("| Fecha `aaaa-MM-dd` | %d |" % f["fecha_aaaa_mm_dd"])
    w("| Precio con coma decimal (`2,50`) | %d |" % f["precio_con_coma"])
    w("| Precio con punto decimal (`2.50`) | %d |" % f["precio_con_punto"])
    w("")
    w("`metodo_pago` tal y como aparece (las comillas muestran los espacios):")
    w("")
    w("| Valor en el fichero | Líneas |")
    w("|---|---:|")
    for valor, n in f["metodo_pago_tal_cual"].items():
        w('| `"%s"` | %d |' % (valor, n))
    w("")
    w("## Resultado esperado")
    w("")
    w("Es lo que debe dar una ETL correcta (y lo que da la solución de referencia). Los importes van")
    w("con punto decimal, igual que en los ficheros de salida.")
    w("")
    w("### Recuento de filas")
    w("")
    w("| Tabla o fichero | Filas |")
    w("|---|---:|")
    w("| `dim_tienda` | %d |" % e["dim_tienda"])
    w("| `dim_producto` | %d |" % e["dim_producto"])
    w("| `dim_fecha` | %d |" % e["dim_fecha"])
    w("| `hechos_ventas` | %d |" % e["hechos_ventas"])
    w("| `rechazos_ventas.csv` | %d |" % e["rechazos_total"])
    w("| **Comprobación:** hechos + rechazos = líneas del fichero | %d + %d = %d |"
      % (e["hechos_ventas"], e["rechazos_total"], e["hechos_ventas"] + e["rechazos_total"]))
    w("")
    w("### Rechazos por motivo")
    w("")
    w("Si una línea tuviera varios problemas, cuenta el primero de esta lista (en estos datos no pasa).")
    w("")
    w("| Orden | Motivo | Filas |")
    w("|---:|---|---:|")
    for i, m in enumerate(MOTIVOS, start=1):
        w("| %d | `%s` | %d |" % (i, m, e["rechazos_por_motivo"][m]))
    w("")
    w("### Método de pago en `hechos_ventas` (ya estandarizado)")
    w("")
    w("| metodo_pago | Filas |")
    w("|---|---:|")
    for m, n in e["hechos_por_metodo_pago"].items():
        w("| `%s` | %d |" % (m, n))
    w("")
    w("### KPI: ticket medio por tienda")
    w("")
    w("`ticket_medio = importe_total / num_tickets`, donde `num_tickets` son los tickets distintos con al")
    w("menos una línea válida. Redondeo a 2 decimales (mitad hacia arriba).")
    w("")
    w("| tienda | importe_total | num_tickets | ticket_medio |")
    w("|---|---:|---:|---:|")
    for k in e["kpi_ticket_medio_tienda"]:
        w("| %s | %s | %d | %s |" % (k["tienda"], k["importe_total"], k["num_tickets"], k["ticket_medio"]))
    w("| **Cadena** | **%s** | **%d** | **%s** |"
      % (e["importe_total_cadena"], e["tickets_cadena"], e["ticket_medio_cadena"]))
    w("")
    w("### KPI: ventas por tienda y categoría")
    w("")
    w("| tienda | categoria | unidades | importe |")
    w("|---|---|---:|---:|")
    for k in e["kpi_ventas_tienda_categoria"]:
        w("| %s | %s | %d | %s |" % (k["tienda"], k["categoria"], k["unidades"], k["importe"]))
    w("")
    with open(CARPETA / "LEEME.md", "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lineas))


# ---------------------------------------------------------------------------------------------

def main():
    CARPETA.mkdir(parents=True, exist_ok=True)
    rng = random.Random(SEMILLA)

    lineas = generar_lineas(rng)
    duplicados = inyectar_errores(rng, lineas)
    filas = escribir_ventas(rng, lineas, duplicados)

    productos, n_nombres, n_categorias = productos_sucios(rng)
    escribir_xlsx(CARPETA / "productos.xlsx", "Productos",
                  ["codigo_producto", "nombre", "categoria", "precio_venta"], productos, [17, 30, 14, 13])
    escribir_tiendas()

    resultado = calcular_esperado(lineas, filas, productos, n_nombres, n_categorias)
    with open(CARPETA / "resultado_esperado.json", "w", encoding="utf-8", newline="\n") as f:
        json.dump(resultado, f, ensure_ascii=False, indent=2)
        f.write("\n")
    escribir_leeme(resultado)

    e = resultado["esperado"]
    print("Datos generados en %s" % CARPETA)
    print("  ventas:    %d líneas (%d únicas, %d duplicadas)"
          % (len(filas), len(lineas), N_DUPLICADOS))
    print("  productos: %d   tiendas: %d" % (len(productos), len(TIENDAS)))
    print("  esperado:  %d hechos, %d rechazos" % (e["hechos_ventas"], e["rechazos_total"]))


if __name__ == "__main__":
    main()
