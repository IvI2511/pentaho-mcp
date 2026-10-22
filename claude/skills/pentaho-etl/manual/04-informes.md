# 04 · Reports: build them and write the narrative

Since 0.5.0 the Spoon plugin has a **Reports** perspective (*Informes* / *Relatórios* in Spanish and
Portuguese) and three MCP tools that use the same engine. The report is built automatically from the
transformation or job: no AI is needed for it. Your part is to choose what goes in, to export it, and,
if the user wants, to add a short **narrative** written from the real data.

---

## 1. Which tool

| The user wants… | Use |
|---|---|
| A quick Markdown overview to paste somewhere, or the text in the chat | `generate_report` (unchanged, Markdown + Mermaid) |
| The real report: to hand in, present, document or archive (HTML, PDF, Markdown with images, PowerPoint) | `build_report` |
| You need the numbers to write about the ETL (narrative, an email, a summary in the chat) | `get_report_data` |
| "Show it to me in Spoon" / "open the report" | `show_report` (only then: it changes what the user is looking at) |

`build_report` and `get_report_data` never switch tabs or perspectives. The editing tools (`add_step`,
`configure_step`…) bring the user back to the Data Integration perspective, as always.

## 2. The usual flow

1. `status` → which document (`T1`, `J1`…). Save first if it was never saved, or pass `output_dir`.
2. Ask what the user needs if it is not clear: formats, language, whether to add conclusions. Do not ask
   about things that have a good default.
3. If there will be a narrative: `get_report_data` with the same `sections` and `language` you will use.
4. Write the narrative (§5) from that data.
5. `build_report` with `formats`, `language`, `narrative` (and `title` if the user gave one).
6. Tell the user where the files are (`files[].path`), and mention any section that came out `partial`,
   `skipped` or `error` with its `reason`, plus the `warnings`. Do not paste the whole report in the chat.

```json
{
  "document": "T1",
  "formats": ["pdf", "pptx"],
  "language": "es",
  "title": "Ventas por producto - TP 2",
  "narrative": {
    "summary": "La transformación lee **ventas.csv** (400.000 filas), calcula el importe de cada venta y lo totaliza por producto.",
    "sections": { "metrics": "El paso más lento es «Leer ventas» (1,7 s de 2,7 s): el resto del flujo casi no pesa." },
    "conclusions": "- El flujo valida sin errores.\n- Para volúmenes mayores conviene ordenar en la base y no en memoria."
  }
}
```

## 3. Formats

| `formats` | What the user gets | Needs |
|---|---|---|
| `html` | One self-contained file (~2.6 MB), works offline, interactive diagrams | – |
| `html_light` | `<name>-light.html`, no JavaScript, 50–300 KB | Edge or Chrome for the diagrams (without them: boxes and tables) |
| `pdf` | A4 or Letter, page numbers, selectable text | Edge or Chrome. Without them you get a `.imprimir.html` to print from the browser, and a warning |
| `md` | `<name>.md` + `<name>_archivos/` (canvas PNG, diagram PNGs, chart SVGs); the Mermaid code of each diagram goes in a collapsed block | – |
| `pptx` | 16:9 deck with speaker notes; long diagrams split into parts, one per slide; the data-dictionary annex is left out (`pptx_appendix: true` adds it) | Edge or Chrome only for the target model (ER) diagram |
| `docx` | Word document with a clickable table of contents, numbered headings and page numbers | – |
| `xlsx` | Excel workbook: an index sheet and one sheet per report table | – |
| `images` | Folder `<name>_imagenes/` with a PNG and an SVG of every diagram and chart, to paste in slides | – |
| `json` | The full report model, images included | – |

- Files go to `output_dir`, or to the folder in the user's report settings, or to an `informes` folder
  next to the `.ktr` / `.kjb`. The base name is the file name of the document unless you pass `file_name`.
- **Several documents**: pass `documents` (ids or paths) instead of `document`, or
  `include_job_transformations: true` with a job to add the transformations it runs. Without `combine`
  each document gets its own files (same name: the second gets `-2`); with `combine: true` everything
  goes in one file per format (cover, two-level contents, one numbered chapter per document; `file_name`
  names it). Single-document options (`lineage_step`, `profile_steps`, `metrics: "run_copy"`) are an
  error with several documents.
- **Existing files with the same name are overwritten** (that keeps Obsidian links working). If the user
  wants to keep an earlier version, use another `file_name`.
- Author, institution, logo, colors and page size come from the user's settings (edited in the Reports
  panel, saved in `<KETTLE_HOME>/.kettle/pentaho-live/informes.json`). Do not edit that file unless the
  user asks you to.

## 4. Sections and what they cost

Without `sections`, the report has the sections the user marked as default that apply to the document.
Pass `sections` only to leave things out or to add `profile`.

| id | Applies to | Cost / side effects |
|---|---|---|
| `cover`, `summary`, `connections` | both | none (connections never include passwords) |
| `flow` / `job_flow` | transformation / job | paints the canvas |
| `lineage`, `dictionary` | transformation | computes the fields of every step; database steps may connect |
| `target_model` | transformation with Table output or Insert/Update | none; with `query_database=true` it connects to read primary and foreign keys (read-only) |
| `validation` | both | runs Spoon's check; database steps may connect |
| `quality` | both | the `lint_etl` score and findings (`06-calidad.md` §9); computes fields like `lineage`. With `quality_deep: true` it also reads up to 5000 rows of the sources through read-only previews (never writes) |
| `metrics` | both | `last_run` (default) reads the last run made in Spoon; nothing if it never ran |
| `profile` | transformation | **runs a copy** up to the step(s) in `profile_steps` (up to 10, one run; `profile_step` for one), reading `profile_rows` rows (default 5000) |
| `narrative` | both | only if you pass `narrative.conclusions` |
| `lesson` | transformation | none; the handout of the lesson played on this document (`08-modo-clase.md`). Never included by default; `skipped` when the transformation has no lesson |

**Ask before running anything.** `profile_steps` and `metrics: "run_copy"` execute a copy of the
transformation (or job), and **its output steps write their files and tables** like a normal run. Use
them only when the user agreed, and say which outputs will be written. `get_report_data` never runs the
transformation (it rejects `profile`).

The profile shows real values (most frequent values, minimum, maximum). If the data is personal, say so
before adding it to a report that will be shared.

## 5. Writing the narrative

The narrative is optional. The report already has an automatic one-sentence summary per section; yours
adds interpretation. Every text is shown with a discreet "written with AI assistance" label (unless the
user turned it off): do not add your own disclaimer.

**Shape** (each text at most 2,000 characters; `sections` keys are section ids):

| Key | Where it goes | Aim for |
|---|---|---|
| `summary` | top of the Summary section | 2–4 sentences: what the ETL does (sources → main operations → outputs), its size, the most important result |
| `sections.<id>` | top of that section | 1–3 sentences that explain what the tables and charts below show |
| `conclusions` | a closing *Conclusions* section | 3–6 `- ` bullets: what works, risks, concrete next steps |

**Format.** Plain text with a little Markdown: paragraphs (blank line), `- ` bullets and `**bold**`.
No headings, tables, links, images, code blocks or HTML (it is escaped and would show as text).

**Rules.**

- **Only facts from `get_report_data`.** Every number, step name, field name, file and table must
  appear there. Do not guess row counts, durations or data quality; if `metrics` is missing, `skipped` or
  has no run, do not talk about performance.
- Write in the report `language` (`en`, `es`, `pt`), with that language's number format (`400.000` and
  `2,7 s` in Spanish and Portuguese; `400,000` and `2.7 s` in English). Copy step names exactly, quoted
  the way the report does («Leer ventas» in es/pt, "Read sales" in en).
- Interpret, don't repeat. The tables are right below your text: explain what matters (the slowest step
  and why, where a key output field comes from, which validation warning needs fixing).
- Sections that are `partial`, `skipped` or `error`: either leave them out or state the `reason` plainly.
- Be specific in conclusions: "Sort rows sorts 400,000 rows in memory: sorting in the database query
  would avoid it" beats "performance could be improved".
- Academic hand-ins: neutral third person, no first person, no praise ("excellent design").

**Where the facts are** (keys of `get_report_data`):

| To talk about | Look at |
|---|---|
| What the ETL reads and writes, and its size | `summary` → indicators and `data` (`reads`, `writes`, `tables`, `steps`, `hops`) |
| Where an output field comes from | `lineage` → `data.fields[].origins` and the operation of each hop (`kind`: pass, rename, calc, aggregate, lookup, join…) |
| Fields and types | `dictionary` → tables per final output |
| Rows, durations, slowest step | `metrics` → indicators (duration, rows read and written, errors, slowest step), the per-step table and the chart series |
| Keys and target columns | `target_model` → `data` (tables, columns, primary and foreign keys) |
| Errors and warnings | `validation` → indicators and table |
| Good practices, the technical quality score | `quality` → `data` (the `lint_etl` result: `score`, `grade`, `penalties`, `findings`); call it technical quality, not the mark of the work |

`get_report_data` cuts long tables to `max_rows` (default 50); `rows_total` gives the full length.

## 6. When something goes wrong

| What you see | What to do |
|---|---|
| `... has no file yet: pass 'output_dir'` | The document was never saved: `save` it, or pass `output_dir` |
| A `pdf: ...` warning about Edge or Chrome | No browser for the PDF: the file is a printable HTML. Offer `html` or ask the user to print it to PDF |
| `The report did not finish in N s` | Ask for fewer sections (lineage, dictionary, validation and quality are the slow ones when steps use databases) |
| A section `partial` with a reason such as "needs the database" | Expected for Table input steps without a reachable connection; mention it |
| `The Reports perspective is not available` (`show_report`) | It is turned off (`PENTAHO_LIVE_REPORTS=off`) or the plugin is installed elsewhere: use `build_report` |
| `The 'profile' section needs 'profile_step'` | Ask which step to profile, and confirm it may run |
