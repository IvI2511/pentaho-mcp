"""Generates catalogo/INDICE.md from steps.json and job-entries.json (output of GenerarCatalogo).

It also cross-checks against the .ktr/.kjb files in samples/ to count uses and to detect types that
appear in the samples but are NOT in the registry (e.g. missing OSGi/karaf plugins).

Usage: python indice.py <dir_catalogo> [<dir_data-integration>]
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
        porcat.setdefault(it["categoria"] or "(no category)", []).append(it)
    for cat, lst in porcat.items():
        cat_es = lst[0].get("categoria_es")
        titulo = cat + (f" / {cat_es}" if cat_es and cat_es != cat else "")
        out.append(f"\n### {titulo} ({len(lst)})\n")
        out.append("| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |")
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
md.append("# Catalog of steps and job entries - PDI 9.4.0.0-343 (CE)\n")
md.append("Generated from the installation itself with the Kettle Java API (`generador/GenerarCatalogo.java`; "
          "regenerate with `bash generador/generar.sh`). Do not edit by hand: it is overwritten on regeneration.\n")
md.append("## Key points\n")
md.append("- **The value of `<type>` in a `.ktr` (inside `<step>`) or in a `.kjb` (inside `<entry>`) is the plugin ID**, "
          "the first column of these tables: `CsvInput`, `SelectValues`, `FilterRows`, `TextFileOutput`, `TableInput`... "
          "It is **not** the display name (\"CSV file input\"; Spanish UI: \u00abFiltrar filas\u00bb) nor the class name. It is case-sensitive.")
md.append("- Some ids do not resemble the name: `ScriptValueMod` = Modified Java Script Value, `Constant` = Add constants, "
          "`Sequence` = Add sequence, `Unique` = Unique rows, `UniqueRowsByHashSet` = Unique rows (HashSet), "
          "`Janino` = User defined Java expression, `TypeExitExcelWriterStep` = Microsoft Excel writer, "
          "`ExcelInput` = Microsoft Excel input, `getXMLData` = Get data from XML, `SystemInfo` = Get system info, "
          "`ExecSQL` = Execute SQL script, `DBLookup` = Database lookup, `DimensionLookup` = Dimension lookup/update, "
          "`SplitFieldToRows3` = Split field to rows, `FieldSplitter` = Split fields, `IfNull` = If field value is null, "
          "`Denormaliser`/`Normaliser` (with an *s*), `RowGenerator` = Generate rows, `DataGrid` = Data grid.")
md.append("- Job entries: ids are mostly in UPPERCASE (`TRANS`, `JOB`, `SQL`, `SUCCESS`, `ABORT`, `WRITE_TO_LOG`...). "
          "**START and DUMMY have no id of their own**: they are `<type>SPECIAL</type>` with `<start>Y</start>` (START) or `<dummy>Y</dummy>` (Dummy).")
md.append("- `steps/<id>.xml` contains the **complete** `<step>` block produced by `StepMeta.getXML()` after `setDefault()`: "
          "`name`, `type`, `distribute`, `copies`, `partitioning`, then **the step's own fragment** "
          "(what `StepMetaInterface.getXML()` returns), and finally `attributes`, `cluster_schema`, `remotesteps`, `GUI`. "
          "Likewise `job-entries/<id>.xml` with the complete `<entry>` from `JobEntryCopy.getXML()`.")
md.append("- The default values are those of `setDefault()`; in many steps that leaves empty lists (`<fields></fields>`) "
          "or values still to be filled in. For realistic examples see `steps-comunes.md`.")
md.append("- `nombre_es` = what Spoon shows with the es_ES locale (the one on this machine). \"=\" means there is no translation.\n")

md.append("## Numbers\n")
md.append(f"- Steps: **{len(steps)}** registered, {len(steps) - len(err_st)} with default XML, {len(err_st)} with errors.")
md.append(f"- Job entries: **{len(jobs)}** registered, {len(jobs) - len(err_je)} with default XML, {len(err_je)} with errors.")
o = collections.Counter("nativo" if s["origen"].startswith("nativo") else "plugins/" for s in steps)
md.append(f"- Origin of the steps: {o['nativo']} native (kettle-engine, `kettle-steps.xml`) + {o['plugins/']} from `plugins/` (`@Step` annotation).")
md.append(f"- Distinct steps used in `samples/*.ktr`: {len(uso_st)}; distinct job entries in `samples/*.kjb`: {len(uso_je)}.")
for e in err_st + err_je:
    md.append(f"  - ERROR `{e['id']}`: {e.get('error')}")

md.append("\n## OSGi plugins (karaf)\n")
if hay_karaf:
    md.append("`system/karaf` exists in this installation; OSGi steps are not loaded by `KettleEnvironment.init()` "
              "(they only start with Spoon's karaf container), which is why they are not in this catalog.")
else:
    md.append("**This installation has no `system/` (nor `system/karaf`)**. "
              "Therefore **there is no OSGi step at all**: what is in `plugins/` + the native steps is exactly what Spoon can "
              "open on this machine. What a full PDI 9.4 gets through karaf (plugin *pentaho-big-data*: "
              "Hadoop file input/output, Parquet/ORC input/output, HBase, MapReduce, Sqoop, Oozie, Pig, Spark submit, "
              "Hadoop copy files...) **does not exist here**: a `.ktr` that uses them will open with the step flagged as a missing plugin.")
md.append("\nTypes used in `samples/` that are **not** in the registry:\n")
if falt:
    md.append("| Kind | `<type>` | Sample |")
    md.append("|---|---|---|")
    for (k, t), fs in sorted(falt.items()):
        md.append(f"| {k} | `{t}` | {', '.join(sorted(fs))} |")
else:
    md.append("(none)")
md.append("\nKnown karaf ids in PDI 9.4 CE (from memory, **not verifiable in this installation**): steps "
          "`HadoopFileInputPlugin`, `HadoopFileOutputPlugin`, `ParquetInput`, `ParquetOutput`, `OrcInput`, `OrcOutput`, "
          "`HBaseInput`, `HBaseOutput`, `HBaseRowDecoder`, `HadoopEnterPlugin`/`HadoopExitPlugin` (MapReduce input/output); "
          "job entries `HadoopCopyFilesPlugin`, `HadoopTransJobExecutorPlugin`, `HadoopJobExecutorPlugin`, "
          "`HadoopPigScriptExecutorPlugin`, `SqoopImport`, `SqoopExport`, `OozieJobExecutor`, `SparkSubmit` "
          "(this last one is confirmed by `samples/jobs/Spark Submit/Spark submit.kjb`). Do not use them in ETLs for this installation.")

md.append("\n## Building steps from XML via the API (notes for the Spoon plugin)\n")
md.append("- From a complete `<step>`: `new StepMeta(Node stepnode, transMeta.getDatabases(), transMeta.getMetaStore())` "
          "-- looks up the plugin by `<type>` in the `PluginRegistry` (throws `KettlePluginLoaderException` if the id does not exist) and calls "
          "`StepMetaInterface.loadXML(...)`. Then `transMeta.addStep(sm)`, `sm.setParentTransMeta(transMeta)`.")
md.append("- From id + fragment: `PluginInterface p = registry.findPluginWithId(StepPluginType.class, id)`; "
          "`StepMetaInterface m = (StepMetaInterface) registry.loadClass(p)`; `m.loadXML(nodo, databases, metaStore)`; "
          "`StepMeta sm = new StepMeta(id, nombre, m)` and `m.setParentStepMeta(sm)`.")
md.append("- Steps that name other steps (`<from>`, `<send_true_to>`, `<step1>`, `<target_step>`...) only store the name when loaded: "
          "**after adding all the steps** call `sm.getStepMetaInterface().searchInfoAndTargetSteps(transMeta.getSteps())` "
          "on each one (TransMeta does not expose a public method for that). Hops: `transMeta.addTransHop(new TransHopMeta(from, to))`.")
md.append("- `getXML()` of some steps (SortRows, BlockingStep, ZipFile, Avro, Kafka) throws an NPE if the meta is not attached to "
          "`StepMeta` + `TransMeta`; `JobEntryCopy.getXML()` throws an NPE without `setLocation(x, y)`.")
md.append("- Shortcut in Spoon: `Spoon.getInstance().pasteXML(transMeta, xml, new Point(x, y))` accepts the clipboard format "
          "`<transformation-steps><steps><step>...</step></steps><order><hop>...</hop></order><notepads/><step_error_handling/></transformation-steps>` "
          "(the same thing Ctrl+C produces in Spoon) and takes care of duplicate names, hops, undo and refresh. For jobs the "
          "clipboard uses `<job-jobentries>`. After manual changes: `spoon.refreshGraph()`, `spoon.refreshTree()`, "
          "`spoon.addUndoNew(transMeta, new StepMeta[]{sm}, new int[]{transMeta.indexOfStep(sm)})`.")

md.append("\n## Steps (transformations, `.ktr`)\n")
md.append("Grouped by Spoon palette category (English / Spanish UI). `Deprecated` last: they still work, but do not use them in new ETLs.")
md.append(tabla(steps, uso_st, "steps"))
md.append("\n## Job entries (jobs, `.kjb`)\n")
md.append(tabla(jobs, uso_je, "job-entries"))
md.append("")

with open(os.path.join(CAT, "INDICE.md"), "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(md))
print(f"INDICE.md: {len(steps)} steps, {len(jobs)} job entries, {len(falt)} sample types not in the registry")
