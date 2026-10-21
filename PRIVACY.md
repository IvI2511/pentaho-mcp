# Privacy policy — Pentaho Live (unofficial)

_Last updated: 2026-10-21_

Pentaho Live is made of a Claude Code plugin (a skill and an MCP connection) and a plugin that runs
inside Pentaho Data Integration (Spoon) on your own computer. This policy covers both.

## What we collect

Nothing. The project has no servers, no accounts, no analytics and no telemetry. The author never
receives any of your data.

## What happens to your data

- **Everything stays on your computer.** The MCP connection goes only to `http://127.0.0.1:8765/mcp`
  (or the address you set in the `mcp_url` option), which is served by the Spoon plugin on the same
  machine. The Spoon plugin listens on the loopback interface only and rejects requests from web pages
  of other origins.
- **What Claude sees.** When you ask Claude to work on a transformation, the tools return to Claude what
  is needed for the task: the steps and their configuration, field names, validation messages, logs
  and, when you ask for a preview or a query, sample rows from your own files or databases. That content
  becomes part of your conversation with Claude and is handled under Anthropic's terms and privacy
  policy, like anything else you share in Claude.
- **Passwords.** Database passwords are masked before any XML or connection detail is returned to
  Claude. If you choose to give Claude a password to create a connection, Spoon stores it in the
  transformation the same way it does when you type it in Spoon's own dialog; the recommended way is to
  type passwords yourself in Spoon.
- **Files.** The plugin writes files only when you ask Claude to save a transformation or job, to the
  path you choose. Running a transformation writes whatever that transformation's own output steps write.
- **Reports.** The Reports perspective and `build_report` write the report files only to the folder you
  choose (by default an `informes` folder next to the `.ktr`/`.kjb`), plus previews in `%TEMP%\plive`,
  which are deleted after a day. A report can contain host names, file paths, parameter values and, only
  if you add the data profile or the deep quality check, real values from your data (most frequent values,
  minimum and maximum; keys and numbers that the deep check quotes; set `"mostrar_valores": false` to leave
  them out of both). It never contains passwords. To print the PDF and draw
  the diagrams, the plugin runs the Edge or Chrome already installed on your computer in headless mode,
  with a temporary profile, on local files only.
- **Quality check.** `lint_etl` and the reports' technical quality section read the transformation or job
  (and, like listing a step's fields, may connect to its databases to learn the field types); they never
  run it. Only when you ask for the deep mode do they read up to 5000 rows of the sources, through previews
  limited to steps that only read, and then the findings can quote values they read (a key, a number);
  in a report, `"mostrar_valores": false` leaves those values out. The deep mode writes no log tables.
  Silencing a rule stores a note inside the `.ktr` / `.kjb`, written when you save it.
- **Exercise grader.** `create_exercise_pack`, `grade_exercise`, `grade_submissions` and the Grader
  perspective run transformations (the teacher's solution, the students' submissions) only when you ask,
  each one in a separate Java process with its own empty Kettle settings, on copies of the data inside a
  temporary folder; they never modify a submission. They write the pack, the results, the students'
  feedback and the gradebook (student names, grades and your comments) only to the folders you choose.
  Grades and names stay on your computer. Running a submission is running code someone else wrote: the
  step policy blocks code, network and file-deletion steps unless you enable trust mode in Spoon, but it is
  not a sandbox. What the tools return to Claude (names, values, logs of a submission) becomes part of your
  conversation, like any other content.
- **Nothing is stored** by the plugins beyond what Spoon itself keeps (your open documents, recent files)
  and your report settings (author, institution, colors and logo), saved in
  `<KETTLE_HOME>\.kettle\pentaho-live\` when you click "Save as default".

## Children

Not directed at people under 18.

## Contact

Questions or issues: https://github.com/IvI2511/pentaho-mcp/issues
