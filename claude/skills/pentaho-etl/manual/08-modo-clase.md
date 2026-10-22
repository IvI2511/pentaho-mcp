# 08 · Class mode: teaching a transformation step by step

Since 0.6.0 the Spoon plugin can **build a transformation slowly and explain it on the canvas**, for a projected
class or for someone learning alone. Claude sends the whole lesson once (`lesson_start`); Spoon plays it one step
at a time with a **«Next ▶»** button in a **Lesson** tab of the execution results panel. Every step appears in
its final place with a numbered note («N · Name (Type) / What: … / Why: …») and, when asked, a watch-out, a tip,
a question and a preview of the rows.

If `lesson_start` is not among the tools, the Spoon plugin is older than 0.6.0: build with `build_steps` and
explain in the chat instead.

Only **transformations** (`.ktr`). Jobs are not supported yet.

---

## 1. The five tools

| Tool | What it does |
|---|---|
| `lesson_start` | Validates the whole script and plans the layout **before touching anything** (like `build_steps`); then writes the title note (step 0, its own Ctrl+Z) and opens the Lesson tab. Returns `plan`, `beats`, `warnings` |
| `lesson_next` | Plays the next step from MCP (any pace). Returns `done` and the `preview` rows, so Claude can comment on them |
| `lesson_status` | Where the lesson is: `state`, `position`, `current`, `clicks`. With `wait_s` (≤ 120) it waits for a change: Claude can follow the class without spending tokens on every click |
| `lesson_control` | `pause`, `resume`, `back` (one Ctrl+Z of the last step), `goto`, `restart` (undo all, back to step 0), `stop` |
| `lesson_notes` | On the lesson notes only: `list`, `add` (a loose watch-out/tip/question), `hide`, `show`, `remove`, `restyle` (`font_size` for a projector), `relayout` — each one Ctrl+Z. Notes the user wrote are never touched |

States: `ready`, `waiting_next`, `playing`, `paused` (reason `panel_hidden`, `user`…), `out_of_sync` (someone
undid or deleted something: «Resume» re-plans from what is there), `done`, `stopped` (`document_closed`).

## 2. Before `lesson_start`

1. **A saved transformation** in a folder that has the data. The ready lessons read
   `${Internal.Entry.Current.Directory}/datos/*.csv`: copy `manual/ejemplos/datos/` next to the new `.ktr` and save
   it there first. Without a file that variable points nowhere and any preview fails; `lesson_start` refuses
   with an error and changes nothing.
2. **Empty canvas** for a lesson that builds from scratch. Existing steps can be annotated with `{"step": "Name"}`
   instead of `type`/`name`. Notes already on the canvas are stacked at the top left (`existing_notes:
   "stack_top"`, the default; the warning says so) or left where they are (`"keep"`).
3. **No `x`/`y`** in the steps: the lesson plan places every step (columns of 240 px, notes above/below the flow).
4. `preview_rows` (0–20) on an output step, or downstream of one, needs `allow_write: true` for the whole lesson,
   because the preview writes the file.

## 3. The script (the argument of `lesson_start`)

```json
{ "title": "Primer ETL: filtrar clientes", "level": "beginner", "language": "es", "pace": "guided",
  "intro": "Una transformación es una cadena de steps…",
  "steps": [
    { "type": "CsvInput", "name": "Leer clientes", "config_xml": "<filename>…</filename>…",
      "what": "lee datos/clientes.csv: 8 clientes", "why": "toda transformación empieza por una entrada",
      "watch_out": "C15: una ruta relativa sin variable…", "preview_rows": 5 },
    { "type": "FilterRows", "name": "Activos con saldo", "after": "Leer clientes", "config_xml": "…",
      "what": "…", "why": "…", "tip": "…", "question": "…" } ],
  "hops": [ { "from": "Leer clientes", "to": "Buscar cliente" },
            { "from": "Eliminar duplicados", "to": "Marcar duplicado", "error": true } ],
  "outro": "Ejercicio: …" }
```

- Per step, the fields of `build_steps` (`type`, `name`, `config_xml`, `after`, `output_mode`) **or** `step` (an
  existing one). A hop in `hops` is created in the step where its last end appears; `error: true` makes it the
  step's error hop.
- `watch_out`: your own text, `"auto"` (from the class glossary: silent errors, section D of
  `03-errores-comunes.md`, for beginners; C and D for intermediate) or nothing.
- `level`: `beginner` or `intermediate`. `language`: `en`, `es` or `pt` (the fixed labels — «What», «Why»,
  «Watch out!», «Lesson», «step» — come from the plugin; everything else is your text, in that language).
- `pace`: `guided` (default: waits for «Next ▶»), `auto` (`delay_ms` 1500–60000, default 6000, with pause) or
  `manual` (only `lesson_next` advances).
- `font_size` for a projector (all lesson notes; -1 = Spoon's note font).
- Keep it to 12 steps or fewer: with more, `lesson_start` warns and suggests splitting it into stages.

### Writing good notes

- **What** = what this step does with *these* data, with a number when there is one («8 customers», «ids 1, 5,
  7 and 8»). **Why** = why it is here, one idea. Short: a step note shows about 6 lines of 220 px; anything
  longer is cut on the canvas and stays whole in the Lesson tab and the handout.
- Beginners: simple words and Excel/SQL analogies («a VLOOKUP in memory», «a GROUP BY»). Intermediate: how
  (`<from>`, order, keys) and the alternatives.
- In a column of the layout with 3 or more steps (many inputs or branches stacked one under another), each step
  only gets a **compact note** with its number next to the icon; the full text is in the Lesson tab and the
  handout. Keep it to 12 steps or fewer (split longer lessons into stages).
- Watch-outs quote the manual code (`C18`, `D5`…) so the handout can link them to `03-errores-comunes.md`.
- Write the numbers only after checking them with Pan or `preview_step`: a note that says «4 rows» while 5 come
  out ruins the class.

## 4. During the class

- The teacher clicks **«Next ▶»** in the Lesson tab; «◀ Back» is a real Ctrl+Z of the last step (it refuses if
  something else is on top of the undo history). **Pause / Resume**, **Restart**, **Finish** (asks first).
- **A-/A+ only changes the text size of the tab**, not the notes on the canvas (a restyle in the middle of a
  lesson would add an undo step that «Back» refuses). For bigger notes use `font_size` in `lesson_start`, or
  `lesson_notes action=restyle` after the lesson.
- If the results panel is closed, the lesson pauses (`panel_hidden`); when it is opened again the tab comes back
  by itself (a watcher checks every 500 ms while the panel is hidden — Spoon 9.4 never calls the `loadTab` hook)
  and the lesson resumes if that was the only reason for the pause.
- To follow along from the chat: `lesson_status wait_s=60` in a loop, and comment on the `preview` of each step.
- Every lesson step is one Ctrl+Z in Spoon.

## 5. After the class

| Want | Do |
|---|---|
| A clean canvas for the exercise | `lesson_notes action=hide` (the XML of the notes stays inside the `.ktr`: **hiding is not secret**) |
| The notes back to correct it | `lesson_notes action=show` (does not duplicate if a Ctrl+Z already brought them back) |
| Hand out the exercise without explanations | save a **copy** and `lesson_notes action=remove` on it |
| A handout (Word, PDF, Markdown…) | `build_report sections=["lesson"] formats=["pdf","docx"] language=es title="…"` |
| Notes moved after `arrange_canvas` | nothing: with lesson notes, `arrange_canvas` and `build_steps` re-place steps and notes together |

The **handout** has, in lesson order: title and goal, the canvas image with the notes, per step «N · Name (Type)»
with what, why, watch-out (with its manual code), tip, question and the glossary entry, the preview rows captured
in class, the exercise, and a table of the common errors of the step types used. The rows live in memory: after
restarting Spoon the handout is rebuilt from the notes and the registry, without rows or exercise (a note says
so). `lesson` is never a default section; pass `title`, otherwise the report heading is the transformation name.

## 6. Ready lessons (Big Data 1)

`lecciones/` has tested scripts in `es`, `en` and `pt` (`<id>.<lang>.json`, the exact argument of
`lesson_start`). Each one builds from scratch, over `manual/ejemplos/datos/`, a transformation whose solution is
an example in `manual/ejemplos/`. The numbers in the notes come from running them (`PruebaLeccionesListas` plays
every script to the end without Spoon and runs the result).

| Script | Level | Base example | Steps | Checked result |
|---|---|---|---|---|
| `L1-primer-etl` | beginner | `02_filtrar_clientes.ktr` | 5 | 8 customers in, 4 out (ids 1, 5, 7, 8), 4 discarded |
| `L2-agregar` | intermediate | `03_ventas_por_producto.ktr` | 5 | Café 20.00, Medialuna 21.60, Té 14.40, Tostado 9.50 |
| `L3-unir-fuentes.a-lookup` | intermediate | `04_ventas_con_cliente.ktr` | 4 | 10 sales, sale 108 with `DESCONOCIDO` |
| `L3-unir-fuentes.b-join` | intermediate | `05_clientes_con_ventas.ktr` | 7 | 10 rows (LEFT OUTER), Fabián without sales |

To give one: create and **save** an empty transformation in a folder, copy `manual/ejemplos/datos/` next to it,
read the script and call `lesson_start` with it (change `pace` or `font_size` if the teacher asks). L3 is two
lessons: part A, then part B in a new transformation in the same folder.

**L2's question** («what if Group by gets the rows unsorted?»), shown live after the lesson: `connect_steps`
Calcular importe → Totales por producto with `output_mode=copy`, `set_hop_enabled` Ordenar por producto →
Totales `false`, then `preview_step` Totales: 10 «groups» instead of 4. Two Ctrl+Z put it back. Disabling the
Sort hop alone does **not** show it: Group by is left without input and fails («Unknown error», exit 1).

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `lesson_start`: «… has no file: save it first …» | a step previews and reads `${Internal.Entry.Current.Directory}`: save the `.ktr` next to `datos/` first |
| `lesson_start`: «… already has lesson notes on the canvas» (or hidden ones) | one lesson per document: `lesson_notes action=remove` (after `show` if hidden), or work on a copy |
| `out_of_sync` | someone pressed Ctrl+Z, deleted or renamed a step: «Resume» re-plans from what is there, or `stop` |
| A note is no longer recognised (`edited` in `list`) | the user edited its text: it is theirs now and the cleanup leaves it (on purpose) |
| The preview of a Filter rows shows rows of both branches | the preview captures the step's output rowsets; trust the I/O/R/W/E of `run` for counts |
| The Lesson tab has no icon / the automatic watch-out is in English | known in 0.6.0 (the glossary titles come from the English manual headings) |
