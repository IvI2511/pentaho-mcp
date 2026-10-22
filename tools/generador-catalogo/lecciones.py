# -*- coding: utf-8 -*-
"""Genera los guiones de las lecciones listas del modo clase (docs/modo-clase-diseno.md §11).

Los guiones son el argumento de `lesson_start` tal cual. La configuración de cada step (config_xml) se
copia de un .ktr verificado (los ejemplos de la skill o la solución de referencia de ejemplo/), sin las
etiquetas que envuelven al step (name, type, GUI...: las pone la lección). Los nombres de los steps se
traducen en en/pt y se reemplazan también dentro de config_xml (send_true_to, <from>, step1/step2).

    python tools/generador-catalogo/lecciones.py          # escribe los .json
    python tools/generador-catalogo/lecciones.py --check  # falla si alguno no coincide con lo generado

Los textos (qué / por qué / ¡ojo! / tip / pregunta) están aquí, en es/en/pt. La prueba
PruebaLeccionesListas reproduce cada guion hasta el final sin Spoon y ejecuta el resultado.
"""
import json
import os
import re
import sys

RAIZ = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SKILL = os.path.join(RAIZ, "claude", "skills", "pentaho-etl")
EJEMPLOS = os.path.join(SKILL, "manual", "ejemplos")
LECCIONES = os.path.join(SKILL, "lecciones")
DEMO = os.path.join(RAIZ, "ejemplo", "leccion")
REFERENCIA = os.path.join(RAIZ, "ejemplo", "solucion-referencia")

ENVOLTORIO = ("name", "type", "description", "distribute", "custom_distribution", "copies", "partitioning",
              "attributes", "cluster_schema", "remotesteps", "GUI")


def config(ktr, step):
    """Cuerpo de un step de un .ktr sin las etiquetas de primer nivel que lo envuelven."""
    with open(ktr, encoding="utf-8") as f:
        t = f.read()
    for m in re.finditer(r"<step>(.*?)\n  </step>", t, re.S):
        cuerpo = m.group(1)
        if re.search(r"^\s{4}<name>" + re.escape(step) + r"</name>", cuerpo, re.M):
            for tag in ENVOLTORIO:
                cuerpo = re.sub(r"\n    <%s\s*/>" % tag, "", cuerpo)
                cuerpo = re.sub(r"\n    <%s>.*?</%s>" % (tag, tag), "", cuerpo, count=1, flags=re.S)
            lineas = [x[4:] if x.startswith("    ") else x for x in cuerpo.strip("\n").split("\n")]
            return "\n".join(lineas).strip() + "\n"
    raise SystemExit("no step %r in %s" % (step, ktr))


def T(es, en, pt):
    return {"es": es, "en": en, "pt": pt}


def txt(v, lang):
    if v is None:
        return None
    return v[lang] if isinstance(v, dict) else v


# ---------------------------------------------------------------------------------------------- L1

L1 = {
    "archivo": "L1-primer-etl",
    "base": "02_filtrar_clientes.ktr",
    "title": T("Primer ETL: filtrar clientes", "First ETL: filtering customers", "Primeiro ETL: filtrar clientes"),
    "level": "beginner",
    "intro": T(
        "Una transformación es una cadena de steps unidos por hops: las filas entran por un lado, cada step "
        "las cambia y salen por el otro. Vamos a leer un CSV de clientes, quedarnos con los activos con saldo "
        "y escribirlos en otro CSV.",
        "A transformation is a chain of steps joined by hops: rows come in on one side, each step changes them "
        "and they leave on the other. We will read a CSV of customers, keep the active ones with a balance and "
        "write them to another CSV.",
        "Uma transformação é uma cadeia de steps ligados por hops: as linhas entram por um lado, cada step as "
        "muda e elas saem pelo outro. Vamos ler um CSV de clientes, ficar com os ativos com saldo e "
        "escrevê-los noutro CSV."),
    "outro": T(
        "Ejercicio: cambia el saldo mínimo de 1000 a 500 en «Activos con saldo», predice cuántas filas saldrán "
        "y compruébalo ejecutándola: la pestaña Step Metrics da las filas leídas y escritas de cada step "
        "(I/O/R/W/E).",
        "Exercise: change the minimum balance from 1000 to 500 in \"Active with balance\", predict how many rows "
        "will come out and check it by running it: the Step Metrics tab gives the rows read and written by each "
        "step (I/O/R/W/E).",
        "Exercício: muda o saldo mínimo de 1000 para 500 em «Ativos com saldo», prevê quantas linhas vão sair e "
        "confirma executando-a: o separador Step Metrics dá as linhas lidas e escritas de cada step (I/O/R/W/E)."),
    "nombres": {
        "Leer clientes": T("Leer clientes", "Read customers", "Ler clientes"),
        "Activos con saldo": T("Activos con saldo", "Active with balance", "Ativos com saldo"),
        "Elegir columnas": T("Elegir columnas", "Choose columns", "Escolher colunas"),
        "Escribir filtrados": T("Escribir filtrados", "Write filtered", "Escrever filtrados"),
        "Descartados": T("Descartados", "Discarded", "Descartados"),
    },
    "steps": [
        {"type": "CsvInput", "name": "Leer clientes", "preview_rows": 5,
         "what": T("lee datos/clientes.csv: 8 clientes, una fila por línea, campos por posición con su tipo",
                   "reads datos/clientes.csv: 8 customers, one row per line, fields by position with their type",
                   "lê datos/clientes.csv: 8 clientes, uma linha por registo, campos por posição com o seu tipo"),
         "why": T("toda transformación empieza por una entrada", "every transformation starts with an input",
                  "toda transformação começa por uma entrada"),
         "watch_out": T("C15: una ruta relativa sin variable depende de la carpeta desde la que se lanza; usa "
                        "${Internal.Entry.Current.Directory}/datos/...",
                        "C15: a relative path without a variable depends on the folder it is launched from; use "
                        "${Internal.Entry.Current.Directory}/datos/...",
                        "C15: um caminho relativo sem variável depende da pasta de onde se lança; usa "
                        "${Internal.Entry.Current.Directory}/datos/...")},
        {"type": "FilterRows", "name": "Activos con saldo", "after": "Leer clientes", "preview_rows": 5,
         "what": T("deja pasar saldo >= 1000 Y activo = S; el resto va a la rama falsa",
                   "lets through balance >= 1000 AND activo = S; the rest goes to the false branch",
                   "deixa passar saldo >= 1000 E activo = S; o resto vai para o ramo falso"),
         "why": T("separa en dos ramas lo que queremos y lo que no", "splits what we want from what we do not",
                  "separa em dois ramos o que queremos e o que não"),
         "watch_out": T("C20: send_true_to / send_false_to necesitan su hop a ese step, o la transformación no "
                        "arranca", "C20: send_true_to / send_false_to need their hop to that step, or the "
                        "transformation does not start", "C20: send_true_to / send_false_to precisam do seu hop "
                        "para esse step, senão a transformação não arranca")},
        {"type": "SelectValues", "name": "Elegir columnas", "after": "Activos con saldo", "preview_rows": 5,
         "what": T("se queda con 4 columnas, renombra cliente_id → id y nombre → cliente y da formato al saldo",
                   "keeps 4 columns, renames cliente_id → id and nombre → cliente and formats the balance",
                   "fica com 4 colunas, renomeia cliente_id → id e nombre → cliente e formata o saldo"),
         "why": T("la salida solo lleva lo útil, con nombres claros", "the output only carries what is useful, "
                  "with clear names", "a saída só leva o útil, com nomes claros"),
         "watch_out": T("C4: un campo que no existe (o mal escrito) hace fallar el step",
                        "C4: a field that does not exist (or is misspelt) makes the step fail",
                        "C4: um campo que não existe (ou mal escrito) faz falhar o step")},
        {"type": "TextFileOutput", "name": "Escribir filtrados", "after": "Elegir columnas",
         "what": T("escribe salida/02_clientes_filtrados.csv: los clientes 1, 5, 7 y 8",
                   "writes salida/02_clientes_filtrados.csv: customers 1, 5, 7 and 8",
                   "escreve salida/02_clientes_filtrados.csv: os clientes 1, 5, 7 e 8"),
         "why": T("es el resultado que se entrega", "it is the result we hand in", "é o resultado que se entrega"),
         "watch_out": T("D8: con la pestaña Campos vacía se escriben todos los campos, con los textos rellenos de espacios; si falta un campo, ese no sale y nada avisa",
                        "D8: with an empty Fields tab every field is written, texts padded with spaces; a field missing from the list is not written and nothing warns",
                        "D8: com o separador Campos vazio saem todos os campos, com os textos preenchidos com espaços; um campo que falta na lista não sai e nada avisa")},
        {"type": "Dummy", "name": "Descartados", "after": "Activos con saldo",
         "what": T("recibe la rama falsa del filtro: los 4 que no cumplen",
                   "receives the false branch of the filter: the 4 that do not match",
                   "recebe o ramo falso do filtro: os 4 que não cumprem"),
         "why": T("no hace nada, pero en las métricas se ve cuántas filas descartamos",
                  "it does nothing, but the metrics show how many rows we dropped",
                  "não faz nada, mas nas métricas vê-se quantas linhas descartámos")},
    ],
}

# ---------------------------------------------------------------------------------------------- L2

L2 = {
    "archivo": "L2-agregar",
    "base": "03_ventas_por_producto.ktr",
    "title": T("Agregar sin mentir: Sort rows + Group by", "Aggregating honestly: Sort rows + Group by",
               "Agregar sem mentir: Sort rows + Group by"),
    "level": "intermediate",
    "intro": T(
        "Totales por producto a partir de las líneas de venta: importe = cantidad × precio, ordenar y agrupar. "
        "Group by solo junta filas consecutivas: sin ordenar, el resultado sale con exit 0 y mal.",
        "Totals per product from the sales lines: amount = quantity × price, sort and group. Group by only "
        "merges consecutive rows: without sorting, the result comes out with exit 0 and wrong.",
        "Totais por produto a partir das linhas de venda: montante = quantidade × preço, ordenar e agrupar. "
        "O Group by só junta linhas consecutivas: sem ordenar, o resultado sai com exit 0 e errado."),
    "outro": T(
        "Ejercicio: cambia el Group by por un Memory group by (sin el Sort) y compara los totales.",
        "Exercise: replace the Group by with a Memory group by (without the Sort) and compare the totals.",
        "Exercício: troca o Group by por um Memory group by (sem o Sort) e compara os totais."),
    "nombres": {
        "Leer ventas": T("Leer ventas", "Read sales", "Ler vendas"),
        "Calcular importe": T("Calcular importe", "Compute amount", "Calcular montante"),
        "Ordenar por producto": T("Ordenar por producto", "Sort by product", "Ordenar por produto"),
        "Totales por producto": T("Totales por producto", "Totals per product", "Totais por produto"),
        "Escribir totales": T("Escribir totales", "Write totals", "Escrever totais"),
    },
    "steps": [
        {"type": "CsvInput", "name": "Leer ventas",
         "what": T("lee datos/ventas.csv: 10 ventas con producto, cantidad y precio",
                   "reads datos/ventas.csv: 10 sales with product, quantity and price",
                   "lê datos/ventas.csv: 10 vendas com produto, quantidade e preço"),
         "why": T("las líneas de venta son el grano del cálculo", "the sales lines are the grain of the "
                  "calculation", "as linhas de venda são o grão do cálculo"),
         "watch_out": T("D3: precio con decimal vacío en un Windows en español se lee ×100 (2.50 → 250); "
                        "declara el decimal «.»", "D3: a price with an empty decimal symbol on a Spanish "
                        "Windows is read ×100 (2.50 → 250); declare the decimal \".\"",
                        "D3: preço com decimal vazio num Windows em espanhol lê-se ×100 (2.50 → 250); "
                        "declara o decimal «.»")},
        {"type": "Calculator", "name": "Calcular importe", "after": "Leer ventas", "preview_rows": 5,
         "what": T("importe = precio × cantidad (MULTIPLY)", "importe = price × quantity (MULTIPLY)",
                   "importe = preço × quantidade (MULTIPLY)"),
         "why": T("hay que sumar importes, no precios", "we must add amounts, not prices",
                  "temos de somar montantes, não preços"),
         "watch_out": T("D4: Integer × Number redondea al tipo del primer campo; el decimal va en field_a",
                        "D4: Integer × Number rounds to the type of the first field; the decimal goes in "
                        "field_a", "D4: Integer × Number arredonda para o tipo do primeiro campo; o decimal vai "
                        "em field_a")},
        {"type": "SortRows", "name": "Ordenar por producto", "after": "Calcular importe",
         "what": T("ordena por producto", "sorts by product", "ordena por produto"),
         "why": T("Group by necesita las filas del mismo producto juntas", "Group by needs the rows of the same "
                  "product together", "o Group by precisa das linhas do mesmo produto juntas"),
         "tip": T("ordena en memoria hasta sort_size filas; con más, usa archivos temporales (más lento)",
                  "sorts in memory up to sort_size rows; beyond that it uses temporary files (slower)",
                  "ordena em memória até sort_size linhas; com mais, usa ficheiros temporários (mais lento)")},
        {"type": "GroupBy", "name": "Totales por producto", "after": "Ordenar por producto", "preview_rows": 5,
         "what": T("por producto: SUM del importe, COUNT_ALL, AVERAGE y MAX",
                   "per product: SUM of the amount, COUNT_ALL, AVERAGE and MAX",
                   "por produto: SUM do montante, COUNT_ALL, AVERAGE e MAX"),
         "why": T("4 productos: Café 20.00, Medialuna 21.60, Té 14.40, Tostado 9.50",
                  "4 products: Café 20.00, Medialuna 21.60, Té 14.40, Tostado 9.50",
                  "4 produtos: Café 20.00, Medialuna 21.60, Té 14.40, Tostado 9.50"),
         "watch_out": T("D5: sin ordenar antes no falla: da un grupo por cada cambio de producto",
                        "D5: without sorting first it does not fail: it gives one group per change of product",
                        "D5: sem ordenar antes não falha: dá um grupo por cada mudança de produto"),
         "question": T("¿Qué pasa si el Group by no recibe las filas ordenadas? ¿Cuántos grupos salen?",
                       "What happens if the Group by does not get the rows sorted? How many groups come out?",
                       "O que acontece se o Group by não receber as linhas ordenadas? Quantos grupos saem?")},
        {"type": "TextFileOutput", "name": "Escribir totales", "after": "Totales por producto",
         "what": T("escribe salida/03_ventas_por_producto.csv", "writes salida/03_ventas_por_producto.csv",
                   "escreve salida/03_ventas_por_producto.csv"),
         "why": T("el informe que se entrega: una fila por producto", "the report we hand in: one row per "
                  "product", "o relatório que se entrega: uma linha por produto")},
    ],
}

# ---------------------------------------------------------------------------------------------- L3

L3A = {
    "archivo": "L3-unir-fuentes.a-lookup",
    "base": "04_ventas_con_cliente.ktr",
    "title": T("Unir fuentes (A): Stream lookup", "Joining sources (A): Stream lookup",
               "Juntar fontes (A): Stream lookup"),
    "level": "intermediate",
    "intro": T(
        "Cada venta con el nombre de su cliente, sin base de datos: dos CSV y un Stream lookup (un BUSCARV en "
        "memoria). Después, en la parte B, lo mismo con Merge join.",
        "Every sale with its customer's name, without a database: two CSV files and a Stream lookup (a "
        "VLOOKUP in memory). Then, in part B, the same with Merge join.",
        "Cada venda com o nome do seu cliente, sem base de dados: dois CSV e um Stream lookup (um PROCV em "
        "memória). Depois, na parte B, o mesmo com Merge join."),
    "outro": T(
        "Ejercicio: cambia el valor por defecto DESCONOCIDO por SIN CLIENTE y vuelve a ejecutar. ¿Cuántas "
        "filas cambian?",
        "Exercise: change the default value DESCONOCIDO to SIN CLIENTE and run again. How many rows change?",
        "Exercício: muda o valor por omissão DESCONOCIDO para SIN CLIENTE e volta a executar. Quantas linhas "
        "mudam?"),
    "nombres": {
        "Leer ventas": T("Leer ventas", "Read sales", "Ler vendas"),
        "Leer clientes": T("Leer clientes", "Read customers", "Ler clientes"),
        "Buscar cliente": T("Buscar cliente", "Look up customer", "Procurar cliente"),
        "Escribir ventas con cliente": T("Escribir ventas con cliente", "Write sales with customer",
                                         "Escrever vendas com cliente"),
    },
    "hops": [{"from": "Leer clientes", "to": "Buscar cliente"}],
    "steps": [
        {"type": "CsvInput", "name": "Leer ventas",
         "what": T("lee datos/ventas.csv: 10 ventas, cada una con su cliente_id",
                   "reads datos/ventas.csv: 10 sales, each with its cliente_id",
                   "lê datos/ventas.csv: 10 vendas, cada uma com o seu cliente_id"),
         "why": T("es el flujo principal: una fila por venta", "it is the main stream: one row per sale",
                  "é o fluxo principal: uma linha por venda")},
        {"type": "CsvInput", "name": "Leer clientes",
         "what": T("lee datos/clientes.csv: 8 clientes", "reads datos/clientes.csv: 8 customers",
                   "lê datos/clientes.csv: 8 clientes"),
         "why": T("es la tabla de búsqueda: id → nombre", "it is the lookup table: id → name",
                  "é a tabela de pesquisa: id → nome")},
        {"type": "StreamLookup", "name": "Buscar cliente", "after": "Leer ventas", "preview_rows": 10,
         "what": T("por cliente_id, añade el nombre; si no lo encuentra, DESCONOCIDO (venta 108)",
                   "by cliente_id, adds the name; if not found, DESCONOCIDO (sale 108)",
                   "por cliente_id, junta o nome; se não o encontrar, DESCONOCIDO (venda 108)"),
         "why": T("no multiplica filas: sigue habiendo 10 ventas", "it does not multiply rows: there are still "
                  "10 sales", "não multiplica linhas: continuam a ser 10 vendas"),
         "watch_out": T("C18: <from> tiene que ser el nombre exacto del step de búsqueda, y ese hop debe existir",
                        "C18: <from> must be the exact name of the lookup step, and that hop must exist",
                        "C18: <from> tem de ser o nome exato do step de pesquisa, e esse hop tem de existir"),
         "tip": T("el flujo de búsqueda entero va a memoria: para tablas grandes, Database lookup",
                  "the whole lookup stream goes to memory: for big tables, Database lookup",
                  "o fluxo de pesquisa inteiro vai para memória: para tabelas grandes, Database lookup")},
        {"type": "TextFileOutput", "name": "Escribir ventas con cliente", "after": "Buscar cliente",
         "what": T("escribe salida/04_ventas_con_cliente.csv: 10 filas", "writes salida/04_ventas_con_cliente.csv: "
                   "10 rows", "escreve salida/04_ventas_con_cliente.csv: 10 linhas"),
         "why": T("el resultado: cada venta con su cliente", "the result: every sale with its customer",
                  "o resultado: cada venda com o seu cliente")},
    ],
}

L3B = {
    "archivo": "L3-unir-fuentes.b-join",
    "base": "05_clientes_con_ventas.ktr",
    "title": T("Unir fuentes (B): Merge join", "Joining sources (B): Merge join", "Juntar fontes (B): Merge join"),
    "level": "intermediate",
    "intro": T(
        "Ahora al revés: cada cliente con sus ventas, también los que no compraron. Merge join es un JOIN de "
        "SQL entre dos flujos ordenados por la clave.",
        "Now the other way round: every customer with their sales, also those who bought nothing. Merge join "
        "is an SQL JOIN between two streams sorted by the key.",
        "Agora ao contrário: cada cliente com as suas vendas, também os que não compraram. Merge join é um "
        "JOIN de SQL entre dois fluxos ordenados pela chave."),
    "outro": T(
        "Para comparar: Stream lookup = flujo de búsqueda en memoria, sin ordenar, nunca multiplica filas. "
        "Merge join = dos flujos ordenados, INNER/LEFT/RIGHT/FULL, puede multiplicar. En base de datos: "
        "Database lookup o Database join. Ejercicio: cambia LEFT OUTER por INNER y cuenta las filas.",
        "To compare: Stream lookup = lookup stream in memory, no sorting, never multiplies rows. Merge join = "
        "two sorted streams, INNER/LEFT/RIGHT/FULL, may multiply. In a database: Database lookup or Database "
        "join. Exercise: change LEFT OUTER to INNER and count the rows.",
        "Para comparar: Stream lookup = fluxo de pesquisa em memória, sem ordenar, nunca multiplica linhas. "
        "Merge join = dois fluxos ordenados, INNER/LEFT/RIGHT/FULL, pode multiplicar. Em base de dados: "
        "Database lookup ou Database join. Exercício: muda LEFT OUTER para INNER e conta as linhas."),
    "nombres": {
        "Leer clientes": T("Leer clientes", "Read customers", "Ler clientes"),
        "Leer ventas": T("Leer ventas", "Read sales", "Ler vendas"),
        "Ordenar clientes": T("Ordenar clientes", "Sort customers", "Ordenar clientes"),
        "Ordenar ventas": T("Ordenar ventas", "Sort sales", "Ordenar vendas"),
        "Unir clientes y ventas": T("Unir clientes y ventas", "Join customers and sales",
                                    "Juntar clientes e vendas"),
        "Quitar clave duplicada": T("Quitar clave duplicada", "Drop duplicate key", "Tirar chave duplicada"),
        "Escribir clientes con ventas": T("Escribir clientes con ventas", "Write customers with sales",
                                          "Escrever clientes com vendas"),
    },
    "hops": [{"from": "Ordenar ventas", "to": "Unir clientes y ventas"}],
    "steps": [
        {"type": "CsvInput", "name": "Leer clientes",
         "what": T("lee datos/clientes.csv (8 clientes)", "reads datos/clientes.csv (8 customers)",
                   "lê datos/clientes.csv (8 clientes)"),
         "why": T("es el lado izquierdo del JOIN", "it is the left side of the JOIN", "é o lado esquerdo do JOIN")},
        {"type": "CsvInput", "name": "Leer ventas",
         "what": T("lee datos/ventas.csv (10 ventas)", "reads datos/ventas.csv (10 sales)",
                   "lê datos/ventas.csv (10 vendas)"),
         "why": T("es el lado derecho", "it is the right side", "é o lado direito")},
        {"type": "SortRows", "name": "Ordenar clientes", "after": "Leer clientes",
         "what": T("ordena por cliente_id", "sorts by cliente_id", "ordena por cliente_id"),
         "why": T("Merge join exige las dos entradas ordenadas por la clave", "Merge join needs both inputs "
                  "sorted by the key", "o Merge join exige as duas entradas ordenadas pela chave")},
        {"type": "SortRows", "name": "Ordenar ventas", "after": "Leer ventas",
         "what": T("ordena por cliente_id", "sorts by cliente_id", "ordena por cliente_id"),
         "why": T("el mismo orden y la misma clave que el otro lado", "the same order and key as the other side",
                  "a mesma ordem e a mesma chave que o outro lado")},
        {"type": "MergeJoin", "name": "Unir clientes y ventas", "after": "Ordenar clientes", "preview_rows": 10,
         "what": T("LEFT OUTER por cliente_id: cada cliente con cada una de sus ventas",
                   "LEFT OUTER on cliente_id: every customer with each of their sales",
                   "LEFT OUTER por cliente_id: cada cliente com cada uma das suas vendas"),
         "why": T("un cliente con 3 ventas da 3 filas; uno sin ventas, 1 fila con la venta vacía",
                  "a customer with 3 sales gives 3 rows; one with no sales, 1 row with an empty sale",
                  "um cliente com 3 vendas dá 3 linhas; um sem vendas, 1 linha com a venda vazia"),
         "watch_out": T("C19: step1/step2 tienen que ser los nombres exactos de los dos steps con hop. D6: sin "
                        "ordenar no falla, pierde filas", "C19: step1/step2 must be the exact names of the two "
                        "steps with a hop. D6: unsorted it does not fail, it loses rows",
                        "C19: step1/step2 têm de ser os nomes exatos dos dois steps com hop. D6: sem ordenar não "
                        "falha, perde linhas")},
        {"type": "SelectValues", "name": "Quitar clave duplicada", "after": "Unir clientes y ventas",
         "what": T("quita cliente_id_1, la clave repetida del lado derecho",
                   "removes cliente_id_1, the repeated key of the right side",
                   "tira cliente_id_1, a chave repetida do lado direito"),
         "why": T("Merge join copia los campos de los dos lados", "Merge join copies the fields of both sides",
                  "o Merge join copia os campos dos dois lados")},
        {"type": "TextFileOutput", "name": "Escribir clientes con ventas", "after": "Quitar clave duplicada",
         "what": T("escribe salida/05_clientes_con_ventas.csv: 10 filas, Fabián sin ventas",
                   "writes salida/05_clientes_con_ventas.csv: 10 rows, Fabián without sales",
                   "escreve salida/05_clientes_con_ventas.csv: 10 linhas, Fabián sem vendas"),
         "why": T("el resultado del JOIN", "the result of the JOIN", "o resultado do JOIN")},
    ],
}

# ---------------------------------------------------------------------------------------------- demo

DEMO_LECCION = {
    "archivo": "leccion-duplicados-y-rechazos",
    "ktr": os.path.join(REFERENCIA, "04_hechos_ventas.ktr"),
    "title": T("Duplicados y rechazos", None, None),
    "level": "intermediate",
    "intro": T("3000 líneas de ticket con formatos mezclados y repetidas. Antes de cargar nada: estandarizar, "
               "quitar duplicados y guardar lo descartado con su motivo, para poder contarlo.", None, None),
    "outro": T("Ejercicio: añade la regla CANTIDAD_NO_VALIDA (cantidad <= 0) con un Filter rows antes de "
               "«Líneas únicas» y envía esas filas a «Escribir rechazos» con su motivo.", None, None),
    "nombres": {},
    "hops": [{"from": "Eliminar duplicados", "to": "Marcar duplicado", "error": True}],
    "steps": [
        {"type": "CsvInput", "name": "Leer ventas (CSV)", "preview_rows": 5,
         "what": T("lee datos/ventas_2026_09.csv: 3000 líneas, todo como texto salvo linea y cantidad", None, None),
         "why": T("los datos vienen sucios: mejor leerlos tal cual y limpiar después", None, None),
         "watch_out": T("D3: precio_unitario trae 1.80 y 1,80; si se lee como número aquí, uno de los dos "
                        "sale mal sin avisar", None, None)},
        {"type": "StringOperations", "name": "Estandarizar textos", "after": "Leer ventas (CSV)",
         "what": T("quita espacios y pasa a mayúsculas id_tienda, código y método de pago", None, None),
         "why": T("«  efectivo» y «EFECTIVO» tienen que ser lo mismo", None, None)},
        {"type": "ReplaceString", "name": "Normalizar fecha y precio", "after": "Estandarizar textos",
         "preview_rows": 5,
         "what": T("dd/mm/aaaa → aaaa-mm-dd y la coma decimal del precio → punto", None, None),
         "why": T("dos formatos de fecha y dos de decimal en el mismo archivo", None, None)},
        {"type": "SortRows", "name": "Ordenar por ticket y línea", "after": "Normalizar fecha y precio",
         "what": T("ordena por id_ticket y linea", None, None),
         "why": T("Unique rows solo detecta duplicados consecutivos", None, None),
         "watch_out": T("D5: sin este orden, Unique rows deja pasar duplicados con exit 0", None, None)},
        {"type": "Unique", "name": "Eliminar duplicados", "after": "Ordenar por ticket y línea",
         "what": T("una fila por (id_ticket, linea); las repetidas se rechazan con la descripción DUPLICADO",
                   None, None),
         "why": T("una línea de ticket cargada dos veces duplica el importe", None, None)},
        {"type": "Constant", "name": "Marcar duplicado", "preview_rows": 5,
         "what": T("recibe las repetidas por el hop de error (rojo) y les pone motivo_rechazo = DUPLICADO",
                   None, None),
         "why": T("un rechazo sin motivo no se puede explicar ni contar", None, None),
         "tip": T("el hop de error (error=true en hops) es lo que separa las repetidas del flujo principal; "
                  "se dibuja en rojo", None, None)},
        {"type": "TextFileOutput", "name": "Escribir rechazos", "after": "Marcar duplicado",
         "what": T("escribe salida/rechazos_duplicados.csv: 60 líneas con su motivo", None, None),
         "why": T("lo descartado se guarda, no se tira: se cuenta y se revisa", None, None),
         "question": T("¿Cuántas filas llegan a «Líneas únicas»? (3000 leídas − 60 duplicadas)", None, None)},
        {"type": "Dummy", "name": "Líneas únicas", "after": "Eliminar duplicados",
         "what": T("recibe las 2940 líneas únicas", None, None),
         "why": T("aquí seguiría la carga (búsqueda de claves y hechos_ventas)", None, None)},
    ],
}


def renombrar(xml, nombres, lang):
    for es, t in nombres.items():
        xml = xml.replace(">" + es + "<", ">" + t[lang] + "<")
    return xml


def guion(spec, lang, ktr):
    nombres = spec["nombres"]

    def n(x):
        return nombres[x][lang] if x in nombres else x

    g = {"title": txt(spec["title"], lang), "level": spec["level"], "language": lang, "pace": "guided"}
    g["intro"] = txt(spec["intro"], lang)
    pasos = []
    for s in spec["steps"]:
        p = {"type": s["type"], "name": n(s["name"])}
        if s.get("after"):
            p["after"] = n(s["after"])
        if s["type"] != "Dummy":  # un Dummy no tiene configuración
            p["config_xml"] = renombrar(config(ktr, s["name"]), nombres, lang)
        for k in ("what", "why", "watch_out", "tip", "question"):
            if s.get(k):
                p[k] = txt(s[k], lang)
        if s.get("preview_rows"):
            p["preview_rows"] = s["preview_rows"]
        pasos.append(p)
    g["steps"] = pasos
    if spec.get("hops"):
        g["hops"] = [dict(h, **{"from": n(h["from"]), "to": n(h["to"])}) for h in spec["hops"]]
    g["outro"] = txt(spec["outro"], lang)
    return g


def ajustar_demo(g):
    """La demo no convierte el precio a número: los rechazos lo escriben como texto, a su propio archivo."""
    for p in g["steps"]:
        if p["name"] == "Escribir rechazos":
            x = p["config_xml"]
            x = x.replace("/../salida/rechazos_ventas<", "/../salida/rechazos_duplicados<")
            x = re.sub(r"(<name>precio_unitario</name>\s*<type>)Number(</type>\s*<format>)0\.00(</format>)",
                       r"\1String\2\3", x)
            p["config_xml"] = x
    return g


def salidas():
    out = {}
    for spec in (L1, L2, L3A, L3B):
        ktr = os.path.join(EJEMPLOS, spec["base"])
        for lang in ("es", "en", "pt"):
            out[os.path.join(LECCIONES, "%s.%s.json" % (spec["archivo"], lang))] = guion(spec, lang, ktr)
    out[os.path.join(DEMO, DEMO_LECCION["archivo"] + ".es.json")] = ajustar_demo(
        guion(DEMO_LECCION, "es", DEMO_LECCION["ktr"]))
    return out


def main():
    check = "--check" in sys.argv
    malos = []
    for ruta, g in salidas().items():
        texto = json.dumps(g, ensure_ascii=False, indent=2) + "\n"
        if check:
            if not os.path.exists(ruta) or open(ruta, encoding="utf-8").read() != texto:
                malos.append(ruta)
        else:
            os.makedirs(os.path.dirname(ruta), exist_ok=True)
            with open(ruta, "w", encoding="utf-8", newline="\n") as f:
                f.write(texto)
            print("ok", os.path.relpath(ruta, RAIZ), len(g["steps"]), "steps")
    if malos:
        print("out of date:", *malos, sep="\n  ")
        sys.exit(1)


if __name__ == "__main__":
    main()
