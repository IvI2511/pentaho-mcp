"""Genera catalogo/INDICE.md a partir de steps.json y job-entries.json (salida de GenerarCatalogo).

Tambien cruza contra los .ktr/.kjb de samples/ para contar usos y detectar tipos que
aparecen en los ejemplos pero NO estan en el registro (p.ej. plugins OSGi/karaf ausentes).

Uso: python indice.py <dir_catalogo> [<dir_data-integration>]
"""
import collections
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET

CAT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..")
PDI = sys.argv[2] if len(sys.argv) > 2 else os.environ.get("PDI_HOME", "")

steps = json.load(open(os.path.join(CAT, "steps.json"), encoding="utf-8"))
jobs = json.load(open(os.path.join(CAT, "job-entries.json"), encoding="utf-8"))
ids_st = {s["id"] for s in steps} | {a for s in steps for a in s.get("ids_alternativos", [])}
ids_je = {s["id"] for s in jobs} | {a for s in jobs for a in s.get("ids_alternativos", [])}

uso_st, uso_je = collections.Counter(), collections.Counter()
falt = collections.defaultdict(set)
for f in glob.glob(os.path.join(PDI, "samples", "**", "*.ktr"), recursive=True):
    try:
        root = ET.parse(f).getroot()
    except ET.ParseError:
        continue
    for s in root.iter("step"):
        t = s.findtext("type")
        if t:
            uso_st[t] += 1
            if t not in ids_st:
                falt[("step", t)].add(os.path.relpath(f, PDI).replace("\\", "/"))
for f in glob.glob(os.path.join(PDI, "samples", "**", "*.kjb"), recursive=True):
    try:
        root = ET.parse(f).getroot()
    except ET.ParseError:
        continue
    for e in root.iter("entry"):
        t = e.findtext("type")
        if t:
            uso_je[t] += 1
            if t not in ids_je:
                falt[("job entry", t)].add(os.path.relpath(f, PDI).replace("\\", "/"))

karaf = os.path.join(PDI, "system", "karaf")
hay_karaf = os.path.isdir(karaf)


def celda(s):
    return (s or "").replace("|", "\\|").replace("\n", " ").strip()


def tabla(items, uso, carpeta):
    out = []
    porcat = collections.OrderedDict()
    orden = sorted(items, key=lambda x: ((x["categoria"] or "") == "Deprecated", (x["categoria"] or "").lower()))
    for it in orden:
        porcat.setdefault(it["categoria"] or "(sin categoria)", []).append(it)
    for cat, lst in porcat.items():
        cat_es = lst[0].get("categoria_es")
        titulo = cat + (f" / {cat_es}" if cat_es and cat_es != cat else "")
        out.append(f"\n### {titulo} ({len(lst)})\n")
        out.append("| `<type>` (id exacto) | Nombre (en) | Nombre en Spoon (es) | Que hace | Usos en samples | Origen |")
        out.append("|---|---|---|---|---|---|")
        for it in sorted(lst, key=lambda x: x["id"].lower()):
            alt = it.get("ids_alternativos")
            idc = f"[`{it['id']}`]({carpeta}/{it['xml'].split('/')[-1]})" if it.get("ok") else f"`{it['id']}` (ERROR)"
            if alt:
                idc += " (alias: " + ", ".join(f"`{a}`" for a in alt) + ")"
            es = it.get("nombre_es")
            es = es if es and es != it["nombre"] else "="
            n = uso.get(it["id"], 0) + sum(uso.get(a, 0) for a in (alt or []))
            out.append(f"| {idc} | {celda(it['nombre'])} | {celda(es)} | {celda(it['descripcion'])} | "
                       f"{n or ''} | {celda(it['origen']).replace('plugins/', '')} |")
    return "\n".join(out)


err_st = [s for s in steps if not s["ok"]]
err_je = [s for s in jobs if not s["ok"]]

md = []
md.append("# Catalogo de steps y job entries - PDI 9.4.0.0-343 (CE)\n")
md.append("Generado desde la propia instalacion con la API Java de Kettle (`generador/GenerarCatalogo.java`, "
          "regenerar con `bash generador/generar.sh`). No editar a mano: se pisa al regenerar.\n")
md.append("## Lo importante\n")
md.append("- **El valor de `<type>` en un `.ktr` (dentro de `<step>`) o en un `.kjb` (dentro de `<entry>`) es el ID del plugin**, "
          "la primera columna de estas tablas: `CsvInput`, `SelectValues`, `FilterRows`, `TextFileOutput`, `TableInput`... "
          "**No** es el nombre en pantalla (\"CSV file input\", \"Filtrar filas\") ni el nombre de la clase. Es sensible a mayusculas.")
md.append("- Algunos ids no se parecen al nombre: `ScriptValueMod` = Modified Java Script Value, `Constant` = Add constants, "
          "`Sequence` = Add sequence, `Unique` = Unique rows, `UniqueRowsByHashSet` = Unique rows (HashSet), "
          "`Janino` = User defined Java expression, `TypeExitExcelWriterStep` = Microsoft Excel writer, "
          "`ExcelInput` = Microsoft Excel input, `getXMLData` = Get data from XML, `SystemInfo` = Get system info, "
          "`ExecSQL` = Execute SQL script, `DBLookup` = Database lookup, `DimensionLookup` = Dimension lookup/update, "
          "`SplitFieldToRows3` = Split field to rows, `FieldSplitter` = Split fields, `IfNull` = If field value is null, "
          "`Denormaliser`/`Normaliser` (con *s*), `RowGenerator` = Generate rows, `DataGrid` = Data grid.")
md.append("- Job entries: ids en MAYUSCULAS en su mayoria (`TRANS`, `JOB`, `SQL`, `SUCCESS`, `ABORT`, `WRITE_TO_LOG`...). "
          "**START y DUMMY no tienen id propio**: son `<type>SPECIAL</type>` con `<start>Y</start>` (START) o `<dummy>Y</dummy>` (Dummy).")
md.append("- `steps/<id>.xml` contiene el bloque `<step>` **completo** que produce `StepMeta.getXML()` tras `setDefault()`: "
          "`name`, `type`, `distribute`, `copies`, `partitioning`, luego **el fragmento propio del step** "
          "(lo que devuelve `StepMetaInterface.getXML()`), y al final `attributes`, `cluster_schema`, `remotesteps`, `GUI`. "
          "Igual `job-entries/<id>.xml` con el `<entry>` completo de `JobEntryCopy.getXML()`.")
md.append("- Los valores por defecto son los de `setDefault()`; en muchos steps eso deja listas vacias (`<fields></fields>`) "
          "o valores a completar. Para ejemplos realistas ver `steps-comunes.md`.")
md.append("- `nombre_es` = lo que muestra Spoon con locale es_ES (el de este equipo). \"=\" significa que no hay traduccion.\n")

md.append("## Numeros\n")
md.append(f"- Steps: **{len(steps)}** registrados, {len(steps) - len(err_st)} con XML por defecto, {len(err_st)} con error.")
md.append(f"- Job entries: **{len(jobs)}** registrados, {len(jobs) - len(err_je)} con XML por defecto, {len(err_je)} con error.")
o = collections.Counter("nativo" if s["origen"].startswith("nativo") else "plugins/" for s in steps)
md.append(f"- Origen de los steps: {o['nativo']} nativos (kettle-engine, `kettle-steps.xml`) + {o['plugins/']} de `plugins/` (anotacion `@Step`).")
md.append(f"- Steps distintos usados en `samples/*.ktr`: {len(uso_st)}; job entries distintos en `samples/*.kjb`: {len(uso_je)}.")
for e in err_st + err_je:
    md.append(f"  - ERROR `{e['id']}`: {e.get('error')}")

md.append("\n## Plugins OSGi (karaf)\n")
if hay_karaf:
    md.append("`system/karaf` existe en esta instalacion; los steps OSGi no se cargan con `KettleEnvironment.init()` "
              "(solo arrancan con el contenedor karaf de Spoon) y por eso no estan en este catalogo.")
else:
    md.append("**Esta instalacion no tiene `system/` (ni `system/karaf`)**. "
              "Por lo tanto **no hay ningun step OSGi**: lo que hay en `plugins/` + nativos es exactamente lo que Spoon puede "
              "abrir en este equipo. Lo que en un PDI 9.4 completo llega por karaf (plugin *pentaho-big-data*: "
              "Hadoop file input/output, Parquet/ORC input/output, HBase, MapReduce, Sqoop, Oozie, Pig, Spark submit, "
              "Hadoop copy files...) **no existe aqui**: un `.ktr` que los use abrira con el step marcado como plugin faltante.")
md.append("\nTipos usados en `samples/` que **no** estan en el registro:\n")
if falt:
    md.append("| Clase | `<type>` | Sample |")
    md.append("|---|---|---|")
    for (k, t), fs in sorted(falt.items()):
        md.append(f"| {k} | `{t}` | {', '.join(sorted(fs))} |")
else:
    md.append("(ninguno)")
md.append("\nIds de karaf conocidos en PDI 9.4 CE (de memoria, **no verificables en esta instalacion**): steps "
          "`HadoopFileInputPlugin`, `HadoopFileOutputPlugin`, `ParquetInput`, `ParquetOutput`, `OrcInput`, `OrcOutput`, "
          "`HBaseInput`, `HBaseOutput`, `HBaseRowDecoder`, `HadoopEnterPlugin`/`HadoopExitPlugin` (MapReduce input/output); "
          "job entries `HadoopCopyFilesPlugin`, `HadoopTransJobExecutorPlugin`, `HadoopJobExecutorPlugin`, "
          "`HadoopPigScriptExecutorPlugin`, `SqoopImport`, `SqoopExport`, `OozieJobExecutor`, `SparkSubmit` "
          "(este ultimo si confirmado por `samples/jobs/Spark Submit/Spark submit.kjb`). No usarlos en ETLs para esta instalacion.")

md.append("\n## Construir steps desde XML via API (notas para el plugin de Spoon)\n")
md.append("- Desde un `<step>` completo: `new StepMeta(Node stepnode, transMeta.getDatabases(), transMeta.getMetaStore())` "
          "-- busca el plugin por `<type>` en el `PluginRegistry` (lanza `KettlePluginLoaderException` si el id no existe) y llama a "
          "`StepMetaInterface.loadXML(...)`. Luego `transMeta.addStep(sm)`, `sm.setParentTransMeta(transMeta)`.")
md.append("- Desde id + fragmento: `PluginInterface p = registry.findPluginWithId(StepPluginType.class, id)`; "
          "`StepMetaInterface m = (StepMetaInterface) registry.loadClass(p)`; `m.loadXML(nodo, databases, metaStore)`; "
          "`StepMeta sm = new StepMeta(id, nombre, m)` y `m.setParentStepMeta(sm)`.")
md.append("- Los steps que nombran a otros (`<from>`, `<send_true_to>`, `<step1>`, `<target_step>`...) solo guardan el nombre al cargar: "
          "**despues de anadir todos los steps** llamar `sm.getStepMetaInterface().searchInfoAndTargetSteps(transMeta.getSteps())` "
          "en cada uno (TransMeta no expone un metodo publico para eso). Hops: `transMeta.addTransHop(new TransHopMeta(from, to))`.")
md.append("- `getXML()` de algunos steps (SortRows, BlockingStep, ZipFile, Avro, Kafka) da NPE si la meta no cuelga de "
          "`StepMeta` + `TransMeta`; `JobEntryCopy.getXML()` da NPE sin `setLocation(x, y)`.")
md.append("- Atajo en Spoon: `Spoon.getInstance().pasteXML(transMeta, xml, new Point(x, y))` acepta el formato del portapapeles "
          "`<transformation-steps><steps><step>...</step></steps><order><hop>...</hop></order><notepads/><step_error_handling/></transformation-steps>` "
          "(lo mismo que produce Ctrl+C en Spoon) y se encarga de nombres duplicados, hops, undo y refresco. Para jobs el "
          "portapapeles usa `<job-jobentries>`. Tras cambios manuales: `spoon.refreshGraph()`, `spoon.refreshTree()`, "
          "`spoon.addUndoNew(transMeta, new StepMeta[]{sm}, new int[]{transMeta.indexOfStep(sm)})`.")

md.append("\n## Steps (transformaciones `.ktr`)\n")
md.append("Agrupados por categoria de la paleta de Spoon (en / es). `Deprecated` al final: siguen funcionando pero no usarlos en ETLs nuevos.")
md.append(tabla(steps, uso_st, "steps"))
md.append("\n## Job entries (trabajos `.kjb`)\n")
md.append(tabla(jobs, uso_je, "job-entries"))
md.append("")

with open(os.path.join(CAT, "INDICE.md"), "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(md))
print(f"INDICE.md: {len(steps)} steps, {len(jobs)} job entries, {len(falt)} tipos de samples fuera del registro")
