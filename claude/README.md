# Pentaho Live (unofficial)

Build ETLs in **Pentaho Data Integration 9.4 (Spoon)** by asking Claude. With Spoon open, Claude adds
steps, connects them, configures them, validates the transformation, previews real rows and runs it,
and you watch every node appear on the canvas. Every change can be undone with Ctrl+Z in Spoon.

This is an independent community project. It is not affiliated with, endorsed by, or supported by
Hitachi Vantara. Pentaho is a trademark of Hitachi Vantara.

## What's inside

- **Skill `pentaho-etl`**: how to write `.ktr` transformations and `.kjb` jobs that actually load,
  verified against a real PDI 9.4.0.0-343 install: the exact step ids for all 216 steps and 77 job
  entries with their default XML, an annotated guide to the ~45 most used steps, nine runnable examples
  and the errors Pan/Kitchen give (including the silent ones). It works on its own: without Spoon,
  Claude writes the file and checks it with Pan.
- **MCP connection `pentaho`**: 88 tools (create/open/save, add/configure/connect steps and job
  entries, list output fields, validate, preview rows, run, database connections, parameters, a
  quality check, reports: HTML, PDF, Markdown and PowerPoint with lineage, data dictionary, run
  metrics and technical quality, an exercise grader, class mode, robustness, golden-dataset tests, a
  semantic diff of versions, an ETL plan with a sketch on the canvas and team projects with CI).

## Quality

`lint_etl` reviews the open transformation or job for what Kettle lets through: Group by or Merge join
without sorting, the empty decimal symbol trap, Calculator Integer × Number, hardcoded paths, passwords
in the file, a failure branch that ends in Success, default step names… It gives a 0-100 technical
quality score that shows where every lost point comes from. `apply_lint_fixes` applies the fixes it
proposes as one Ctrl+Z (Claude asks you first about the ones that change what the ETL does), and
`suppress_lint` silences a rule where it is on purpose. On request, a deep mode reads the sources to
prove whether an unsorted input or a decimal symbol really changes the result.

## Grading exercises

For teachers: build an exercise **pack** from your solution (the data of a visible case and a hidden one,
the expected output, rules and a rubric), then grade a whole folder of submissions. Each submission runs
in a separate JVM, never inside Spoon, under a step policy that blocks code and network steps; its output
is compared row by row with the expected one. You get a grade with the failed checks, a hint for each
typical mistake, feedback for every student without the hidden case's values, and a gradebook for Excel.
Spoon gets a **Grader** perspective with the same, plus a wizard that builds the pack from the open
transformation. It is not a real sandbox: for submissions from strangers, use a virtual machine.

## Class mode

Claude can also *teach* an ETL: it builds the transformation slowly, one step per click on **Next ▶**
in a new **Lesson** tab, and explains every step with a numbered note on the canvas (what it does, why it
is there, the typical mistake), with the first rows after each step. Made for a projected class or for
learning alone; afterwards the notes can be hidden for the exercise and the lesson becomes a handout in
PDF or Word (`build_report` with the `lesson` section). Three tested lessons come with the skill in
English, Spanish and Portuguese.

## Robustness

Ask Claude to make a load tolerate dirty data. `lint_etl` with `robustness=true` finds what would stop
the load or lose rows; `harden_etl` shows the plan and applies it as one Ctrl+Z (every bad row goes to a
rejects file with the reason, plus required-field checks, dedupe and orphans; Claude asks you first about
the changes that alter the result); `stress_test` proves it: broken copies of your input files run against
a copy of the transformation in a sandbox, checking that every row read was loaded or rejected with a
reason and that running it twice duplicates nothing.

## Requirement: the Spoon-side plugin

The MCP tools talk to a small plugin that runs **inside Spoon** on the same computer. Install it once:
download `pentaho-mcp.zip` from the
[latest release](https://github.com/IvI2511/pentaho-mcp/releases/latest), unzip it into
`data-integration/plugins/` and restart Spoon. Or ask Claude: *"install IvI2511/pentaho-mcp"* and it
follows [INSTALL.md](https://github.com/IvI2511/pentaho-mcp/blob/main/INSTALL.md). If you don't have
PDI yet, the release also has PDI 9.4 CE with the plugin already in place.

Live editing therefore needs Claude Code (or the desktop app) on the computer where Spoon runs. In
claude.ai chat only the skill is useful.

## What it runs, sends and stores

- The MCP connection only goes to `http://127.0.0.1:8765/mcp` (your own computer; change it with the
  `mcp_url` option). Nothing is sent to any other server, and the plugin collects no data.
- The Spoon-side plugin listens on the loopback interface only and rejects browser requests from other
  origins. It edits the documents you have open, writes files only where you ask Claude to save, and
  runs transformations only when asked. Database passwords are never returned to Claude.
- The grader runs students' transformations only when you ask, each one in a separate process with its
  own empty Kettle settings, and writes results only to the folder you choose. Trust mode (letting
  dangerous steps run) can only be turned on in Spoon, never by Claude.
- The quality check does not run the transformation. Its deep mode, only when asked, reads up to 5000
  rows of the sources through previews limited to read-only steps, and its findings can quote values it
  read.
- The dirty-data test (`stress_test`) runs only when you ask, on a copy of the transformation in a
  separate Java process with its own Kettle settings, and only when every database it uses is a SQLite or
  H2 file (which it copies first). It never connects to any other database and writes only in a temporary
  folder (or the `export_variants` folder you choose). `harden_etl` adds steps that write rejects files,
  by default in a `rechazos` folder next to the `.ktr`.
- Class mode stores the lesson notes inside the `.ktr`, written when you save it.
- The skill may ask Claude to run Pan or Kitchen (PDI's command-line runners) on your computer to
  verify a file. Claude Code asks for permission before running commands, as usual.
- Source code of everything, including the Java plugin, is in the
  [repository](https://github.com/IvI2511/pentaho-mcp).

## Example prompts

- *"Build a transformation that reads ventas.csv, adds the city from clientes.csv, sums the amount per
  city and writes a CSV."*
- *"Why does this .ktr fail in Pan?"*
- *"Check this transformation for bad practices and fix what's safe."*
- *"Grade the submissions in C:\TP3\entregas with the pack C:\TP3\pack and tell me who failed and why."*
- *"Add a Filter rows after the CSV input that keeps rows with saldo > 1000 and preview the result."*

## Privacy

No data collection of any kind. Details: [privacy policy](https://github.com/IvI2511/pentaho-mcp/blob/main/PRIVACY.md).

## License

MIT. See [LICENSE](LICENSE).
