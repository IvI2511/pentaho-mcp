"""Corpus de mutaciones del linter (docs/linter-diseno.md §3.1): copias de los ejemplos de la skill con UN error
conocido cada una (los de 03-errores-comunes.md) y cuatro jobs chicos para la rama de error de un job.

Uso:  python -I tools/lint-corpus/mutar.py <carpeta de salida>
La carpeta se borra y se vuelve a crear. Lo que se espera de cada archivo está en esperado.tsv (al lado).
"""
import re
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / "claude" / "skills" / "pentaho-etl" / "manual" / "ejemplos"


def leer(n):
    return (SRC / n).read_text(encoding="utf-8")


def sin_hop(x, a, b):
    pat = re.compile(r"\s*<hop>\s*<from>%s</from>\s*<to>%s</to>.*?</hop>" % (re.escape(a), re.escape(b)), re.S)
    y, n = pat.subn("", x, count=1)
    assert n == 1, (a, b)
    return y


def con_hop(x, a, b):
    return x.replace("</order>", "<hop><from>%s</from><to>%s</to><enabled>Y</enabled></hop></order>" % (a, b), 1)


def sin_step(x, nombre):
    pat = re.compile(r"\s*<step>\s*<name>%s</name>.*?</step>" % re.escape(nombre), re.S)
    y, n = pat.subn("", x, count=1)
    assert n == 1, nombre
    return y


def en_step(x, nombre, f):
    m = re.search(r"<step>\s*<name>%s</name>.*?</step>" % re.escape(nombre), x, re.S)
    assert m, nombre
    return x[:m.start()] + f(m.group(0)) + x[m.end():]


def sub1(x, a, b):
    assert a in x, a
    return x.replace(a, b, 1)


def resub1(pat, rep, x, flags=re.S):
    y, n = re.subn(pat, rep, x, count=1, flags=flags)
    assert n == 1, pat
    return y


# ---------------------------------------------------------------- jobs chicos (rama roja)
START = ("<entry><name>START</name><description/><type>SPECIAL</type><attributes/><start>Y</start><dummy>N</dummy>"
         "<repeat>N</repeat><schedulerType>0</schedulerType><intervalSeconds>0</intervalSeconds>"
         "<intervalMinutes>60</intervalMinutes><hour>12</hour><minutes>0</minutes><weekDay>1</weekDay>"
         "<DayOfMonth>1</DayOfMonth><parallel>N</parallel><draw>Y</draw><nr>0</nr><xloc>50</xloc><yloc>50</yloc>"
         "<attributes_kjc/></entry>")
TRANS_FALLA = ("<entry><name>Falla</name><description/><type>TRANS</type><attributes/>"
               "<specification_method>filename</specification_method><trans_object_id/>"
               "<filename>${Internal.Entry.Current.Directory}/no_existe.ktr</filename><transname/>"
               "<arg_from_previous>N</arg_from_previous><params_from_previous>N</params_from_previous>"
               "<exec_per_row>N</exec_per_row><clear_rows>N</clear_rows><clear_files>N</clear_files>"
               "<set_logfile>N</set_logfile><logfile/><logext/><add_date>N</add_date><add_time>N</add_time>"
               "<loglevel>Basic</loglevel><cluster>N</cluster><slave_server_name/>"
               "<set_append_logfile>N</set_append_logfile><wait_until_finished>Y</wait_until_finished>"
               "<follow_abort_remote>N</follow_abort_remote><create_parent_folder>N</create_parent_folder>"
               "<logging_remote_work>N</logging_remote_work><run_configuration>Pentaho local</run_configuration>"
               "<suppress_result_data>N</suppress_result_data><parameters><pass_all_parameters>Y</pass_all_parameters>"
               "</parameters><parallel>N</parallel><draw>Y</draw><nr>0</nr><xloc>200</xloc><yloc>50</yloc>"
               "<attributes_kjc/></entry>")
EXISTE = ("<entry><name>Hay archivo</name><description/><type>FILE_EXISTS</type><attributes/>"
          "<filename>${Internal.Entry.Current.Directory}/no_existe.csv</filename><parallel>N</parallel><draw>Y</draw>"
          "<nr>0</nr><xloc>200</xloc><yloc>50</yloc><attributes_kjc/></entry>")
AVISAR = ("<entry><name>Avisar</name><description/><type>WRITE_TO_LOG</type><attributes/>"
          "<logmessage>fallo la transformacion</logmessage><loglevel>Basic</loglevel><logsubject>aviso</logsubject>"
          "<parallel>N</parallel><draw>Y</draw><nr>0</nr><xloc>200</xloc><yloc>200</yloc><attributes_kjc/></entry>")
EXITO = ("<entry><name>Listo</name><description/><type>SUCCESS</type><attributes/><parallel>N</parallel><draw>Y</draw>"
         "<nr>0</nr><xloc>350</xloc><yloc>200</yloc><attributes_kjc/></entry>")


def hop(a, b, verde, incondicional):
    return ("<hop><from>%s</from><to>%s</to><from_nr>0</from_nr><to_nr>0</to_nr><enabled>Y</enabled>"
            "<evaluation>%s</evaluation><unconditional>%s</unconditional></hop>"
            % (a, b, "Y" if verde else "N", "Y" if incondicional else "N"))


def job(nombre, entradas, hops):
    return ('<?xml version="1.0" encoding="UTF-8"?>\n<job><name>%s</name><description>prueba del linter</description>'
            "<extended_description/><job_version/><directory>/</directory><parameters></parameters>"
            "<slaveservers></slaveservers><entries>\n    %s\n</entries><hops>\n    %s\n</hops><notepads></notepads></job>\n"
            % (nombre, "\n    ".join(entradas), "\n    ".join(hops)))


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = Path(sys.argv[1])
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    shutil.copytree(SRC / "datos", out / "datos")
    hechos = []

    def guardar(nombre, x):
        (out / nombre).write_text(x, encoding="utf-8")
        hechos.append(nombre)

    e01, e02, e03, e05, e07, e10 = (leer(n) for n in (
        "01_generar_constantes.ktr", "02_filtrar_clientes.ktr", "03_ventas_por_producto.ktr",
        "05_clientes_con_ventas.ktr", "07_conexion_sqlite.ktr", "10_job_secuencia.kjb"))

    # D3 decimal vacio (con formato #.##: en es_ES falla)
    guardar("m01_decimal_vacio.ktr", en_step(e03, "Leer ventas", lambda s: resub1(
        r"(<name>precio_unitario</name>.*?)<decimal>\.</decimal>", r"\1<decimal/>", s)))
    # D4 Integer x Number
    guardar("m02_calc_entero_primero.ktr", sub1(e03, "<field_a>precio_unitario</field_a>\n      <field_b>cantidad</field_b>",
                                                "<field_a>cantidad</field_a>\n      <field_b>precio_unitario</field_b>"))
    # D5 Group by sin ordenar
    x = sin_step(e03, "Ordenar por producto")
    x = sin_hop(x, "Calcular importe", "Ordenar por producto")
    x = sin_hop(x, "Ordenar por producto", "Totales por producto")
    guardar("m03_groupby_sin_ordenar.ktr", con_hop(x, "Calcular importe", "Totales por producto"))
    # D6 Merge join sin ordenar
    x = sin_step(sin_step(e05, "Ordenar clientes"), "Ordenar ventas")
    for a, b in (("Leer clientes", "Ordenar clientes"), ("Leer ventas", "Ordenar ventas"),
                 ("Ordenar clientes", "Unir clientes y ventas"), ("Ordenar ventas", "Unir clientes y ventas")):
        x = sin_hop(x, a, b)
    x = con_hop(con_hop(x, "Leer clientes", "Unir clientes y ventas"), "Leer ventas", "Unir clientes y ventas")
    x = sub1(x, "<step1>Ordenar clientes</step1>", "<step1>Leer clientes</step1>")
    x = sub1(x, "<step2>Ordenar ventas</step2>", "<step2>Leer ventas</step2>")
    guardar("m04_mergejoin_sin_ordenar.ktr", x)
    # D7 hop deshabilitado
    guardar("m05_hop_deshabilitado.ktr", resub1(r"(<from>Elegir columnas</from>\s*<to>Escribir filtrados</to>\s*)<enabled>Y</enabled>",
                                                r"\1<enabled>N</enabled>", e02))
    # D9 variable en Add constants
    guardar("m06_variable_en_constante.ktr", sub1(e01, "<nullif>España</nullif>", "<nullif>${ARCHIVO_SALIDA}</nullif>"))
    # D11 Internal.Job en un ktr
    guardar("m07_variable_de_job.ktr", e01.replace("${Internal.Entry.Current.Directory}", "${Internal.Job.Filename.Directory}"))
    # C15 ruta relativa
    guardar("m08_ruta_relativa.ktr", e03.replace("${Internal.Entry.Current.Directory}/", ""))
    # ruta absoluta
    guardar("m09_ruta_absoluta.ktr", e03.replace("${Internal.Entry.Current.Directory}", "C:/Users/alumno/etl"))
    # contraseña en claro
    guardar("m10_password_claro.ktr", sub1(e07, "<username/>\n    <password/>", "<username>admin</username>\n    <password>secreto123</password>"))
    # C24 START renombrado
    guardar("m11_start_renombrado.kjb", e10.replace("<name>START</name>", "<name>Inicio</name>").replace("<from>START</from>", "<from>Inicio</from>"))
    # D10 rama de error a Success
    guardar("m12_error_a_success.kjb", resub1(r"(<from>Ventas por producto</from>\s*)<to>Abortar</to>", r"\1<to>Success</to>", e10))
    # reparto con 2 salidas
    guardar("m13_reparto.ktr", con_hop(e02, "Leer clientes", "Descartados"))
    # D1 nombres duplicados
    x = e02.replace("<name>Descartados</name>", "<name>Escribir filtrados</name>").replace(
        "<to>Descartados</to>", "<to>Escribir filtrados</to>").replace(
        "<send_false_to>Descartados</send_false_to>", "<send_false_to>Escribir filtrados</send_false_to>")
    guardar("m14_nombres_duplicados.ktr", x)
    # D8 Text file output sin campos (hoy no rellena: info)
    guardar("m15_salida_sin_campos.ktr", en_step(e03, "Escribir totales", lambda s: resub1(r"<fields>.*</fields>", "<fields/>", s)))
    # D8 con un String de longitud 30: rellena (warning)
    x = en_step(e03, "Leer ventas", lambda s: resub1(r"(<name>producto</name>.*?)<length>-1</length>", r"\g<1><length>30</length>", s))
    guardar("m15b_salida_sin_campos_con_longitud.ktr", en_step(x, "Escribir totales", lambda s: resub1(r"<fields>.*</fields>", "<fields/>", s)))
    # copias que rompen el orden antes del Merge join
    guardar("m16_copias_antes_de_join.ktr", en_step(e05, "Ordenar clientes", lambda s: sub1(s, "<copies>1</copies>", "<copies>2</copies>")))
    # ordenado por otra clave
    guardar("m17_orden_otra_clave.ktr", en_step(e03, "Ordenar por producto", lambda s: resub1(r"(<field>\s*<name>)producto(</name>)", r"\1venta_id\2", s)))
    # D2 ciclo
    guardar("m18_ciclo.ktr", con_hop(e01, "Escribir CSV", "Agregar constantes"))
    # step sin hops
    guardar("m19_step_huerfano.ktr", sin_hop(e01, "Agregar constantes", "Escribir CSV"))
    # D3 decimal y formato vacios (en es_ES 2.50 se lee 250)
    guardar("m20_decimal_y_formato_vacios.ktr", en_step(e03, "Leer ventas", lambda s: resub1(
        r"(<name>precio_unitario</name>\s*<type>Number</type>\s*)<format>#\.##</format>(.*?)<decimal>\.</decimal>",
        r"\1<format/>\2<decimal/>", s)))
    # D4 dos calculos mas: Integer/Integer con resultado Number y Integer x Number
    extra = ("</calculation><calculation>\n      <field_name>ratio</field_name>\n      <calc_type>DIVIDE</calc_type>\n"
             "      <field_a>venta_id</field_a>\n      <field_b>cantidad</field_b>\n      <field_c/>\n"
             "      <value_type>Number</value_type>\n      <value_length>12</value_length>\n      <value_precision>2</value_precision>\n"
             "      <remove>N</remove>\n      <conversion_mask/>\n      <decimal_symbol/>\n      <grouping_symbol/>\n"
             "      <currency_symbol/>\n    </calculation><calculation>\n      <field_name>importe_mal</field_name>\n"
             "      <calc_type>MULTIPLY</calc_type>\n      <field_a>cantidad</field_a>\n      <field_b>precio_unitario</field_b>\n"
             "      <field_c/>\n      <value_type>Number</value_type>\n      <value_length>12</value_length>\n"
             "      <value_precision>2</value_precision>\n      <remove>N</remove>\n      <conversion_mask/>\n"
             "      <decimal_symbol/>\n      <grouping_symbol/>\n      <currency_symbol/>\n    </calculation>")
    guardar("m21_calculadora_enteros.ktr", sub1(e03, "</calculation>", extra))

    # rama roja de un job: lo que NO es falla silenciosa (revision del diseño) y lo que si
    guardar("j01_existe_a_success.kjb", job("j01_existe_a_success",
                                            [START, EXISTE, EXITO.replace("<name>Listo</name>", "<name>Nada</name>")],
                                            [hop("START", "Hay archivo", True, True), hop("Hay archivo", "Nada", False, False)]))
    guardar("j02_rojo_a_log.kjb", job("j02_rojo_a_log", [START, TRANS_FALLA, AVISAR],
                                      [hop("START", "Falla", True, True), hop("Falla", "Avisar", False, False)]))
    guardar("j03_rojo_log_incondicional_success.kjb", job("j03_rojo_log_incondicional_success",
                                                          [START, TRANS_FALLA, AVISAR, EXITO],
                                                          [hop("START", "Falla", True, True), hop("Falla", "Avisar", False, False),
                                                           hop("Avisar", "Listo", True, True)]))
    guardar("j04_rojo_log_verde_success.kjb", job("j04_rojo_log_verde_success", [START, TRANS_FALLA, AVISAR, EXITO],
                                                  [hop("START", "Falla", True, True), hop("Falla", "Avisar", False, False),
                                                   hop("Avisar", "Listo", True, False)]))
    # transformacion sin rama roja: solo la apagada job_entry_without_failure_hop lo marca
    guardar("j05_trans_sin_rama_roja.kjb", job("j05_trans_sin_rama_roja", [START, TRANS_FALLA, EXITO],
                                               [hop("START", "Falla", True, True), hop("Falla", "Listo", True, False)]))
    print(len(hechos), "archivos en", out)


if __name__ == "__main__":
    main()
