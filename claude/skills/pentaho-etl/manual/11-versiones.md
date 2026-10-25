# 11 · Versions: semantic diff, normalizer and git

How to see **what changed by meaning** between two versions of a `.ktr` or `.kjb` (not as XML lines), and how
to set up a git repository so that diffs and commits of Kettle files are readable and stable. Three pieces:

- **`diff_documents`** (MCP tool), **IDC menu > Compare…** (Spoon, with badges on the canvas) and the
  **`changes` report section**: the semantic diff.
- **`pentaho-live normalize`**: the canonical XML, so two saves without real changes give zero diff.
- **`pentaho-live textconv`**: the git driver that makes `git diff` show the semantic view of each file.

Everything only reads the documents; nothing here modifies an open transformation or job.

## 1. When

- Before saving or committing: *what did I change?* (`compare_with=saved` shows the unsaved changes).
- Reviewing a teammate's change or a pull request: `compare_with=git:HEAD~1`, `git:main`…
- Class: *what did the student change from the template?* (`compare_with=<template.ktr>`).
- Writing a PR description or a review comment: `format=markdown`.
- The person asks *¿qué cambió?*, *compara con la versión anterior*, *diff*, *revisar cambios*.

## 2. `diff_documents`

```json
{"document": "T1", "compare_with": "saved"}                       // unsaved changes of the open document
{"document": "T1", "compare_with": "git:HEAD"}                    // against the last commit
{"path": "C:/etl/ventas.ktr", "compare_with": "git:HEAD~1"}       // a file, not open in Spoon
{"document": "T1", "compare_with": "plantilla.ktr", "format": "markdown", "language": "es"}
{"document": "T1", "compare_with": "git:main", "show_on_canvas": true}
```

| Argument | Meaning |
|---|---|
| `document` | The open transformation or job (default: the active tab). It is the **after** side. |
| `path` | A `.ktr`/`.kjb` on disk instead of an open document (then `compare_with` defaults to `git:HEAD`). |
| `compare_with` | `saved` (default for an open document: its file on disk), `git:REV` (`git:HEAD`, `git:HEAD~1`, a branch, a commit; `HEAD` alone works too) or the path of another file (relative to the document's folder). |
| `ignore_positions` | Default `true`: moving boxes is not a change. `false` adds "moved" changes. |
| `format` | `json` (default: the changes as data plus `text`), `text` or `markdown`. |
| `language` | `en`, `es` or `pt` for the readable texts. |
| `show_on_canvas` | Also mark the changes on the canvas and open the list in Spoon. Only if the person wants to see them there. |

Both sides are read the way Spoon writes them (loaded with Kettle and written with `getXML()`), so a
hand-written file is not full of "changes" for the tags Kettle adds with their default value.

### Reading the result

Each change has a **kind** (`step`, `entry`, `hop`, `error_handling`, `connection`, `parameter`, `note`,
`document`) and a **change** with its symbol: `+ added`, `− removed`, `~ changed`, `» renamed`, `↔ moved`.

- **Properties** come as `before → after` with readable names: `condition: saldo >= 1000.0 AND activo = 'S' →
  saldo > 1500 AND activo = 'S'` (the whole Filter rows condition in one line), `fields › saldo › type: Number →
  Integer`, `fields › email: added (type=String, length=80)`, `fields › field · order: a, b, c → a, c, b` (only
  when the order of the columns that are in both changed), file names, `send_true_to`…
- **SQL, scripts and other multi-line values** come as a line diff (`- old line`, `+ new line`).
- **Renamed** steps are recognised by type, configuration, neighbours and position, and the steps or hops that
  only changed because they name the renamed step are **not** reported. A step replaced by another of a
  different type is "removed" + "added". Two empty Dummy steps in different places with different neighbours
  are not taken as a rename.
- **Hops**: added, removed, enabled/disabled, "now carries the error rows" (transformations), the condition
  `always / on success / on failure` (jobs).
- **Passwords and users are never shown**, not even encrypted: only `changed (hidden)`, `set (hidden)` or
  `cleared`. A value that is just a variable (`${DB_PASS}`) is shown. Do not try to recover them.
- `modified_user` and `modified_date` never count.

Tell the person the summary first (`summary_text`), then the changes that matter; quote properties exactly.

### In Spoon

**IDC menu > Compare…**: compare with the saved file, a git revision (the last commits of the file are
listed) or another file; *Ignore box positions* is on by default. Two views:

- **Badges on this canvas**: `+` green, `~` amber, `»` blue on each step and in the middle of each hop;
  removed steps are drawn as red dashed **ghosts** where they were, and removed hops as red dashed lines.
- **Two tabs**: the other version opens in a new tab (named `<name> [<where it comes from>]`, without a
  file, so it cannot overwrite anything), both with badges (`−` on what disappears).

The **list of changes** opens next to it; clicking a change selects its step (or both ends of a hop) on
the canvas. *Copy as Markdown* puts the diff in the clipboard. **Closing the list removes the badges** and
closes the other version's tab if nobody touched it. The badges are only drawing: the document is not
modified and `Ctrl+Z` has nothing to undo.

### In a report

`build_report` (or `get_report_data`) with `sections=["changes"]` and, optionally, `compare_with`
(same values as above; default: the saved file if there are unsaved changes, otherwise `git:HEAD`).
`compare_with` alone adds the section. It is never in the default selection.

## 3. Git: `pentaho-live` commands

The Windows installer puts `pentaho-live` in the PATH. These commands do not open Spoon: they run Pentaho
Live's jar with Java (the bundled one, or `PENTAHO_JAVA_HOME`, `JAVA_HOME`, the PATH). On macOS and Linux use
the `launcher/pentaho-live` script with `PDI_HOME` (or `PENTAHO_LIVE_JAR`) set.

```text
pentaho-live diff ventas.ktr                       # git HEAD against the file (exit 1 if they differ)
pentaho-live diff --rev HEAD~3 ventas.ktr
pentaho-live diff --rev origin/main --format markdown   # every .ktr/.kjb changed since origin/main (a PR's CI)
pentaho-live diff --format markdown antes.ktr despues.ktr
pentaho-live normalize etl/                        # rewrite every .ktr/.kjb canonically
pentaho-live normalize --check etl/                # CI: exit 1 if something is not normalized
pentaho-live textconv ventas.ktr                   # the semantic view of one file
pentaho-live git-setup                             # prints the lines below
```

In `cmd.exe` the caret is an escape character: write `HEAD~1`, not `HEAD^`.

### Readable `git diff`

In `.gitattributes` (committed, for the whole team):

```text
*.ktr diff=kettle
*.kjb diff=kettle
```

Once in each clone (it goes to `.git/config`):

```text
git config diff.kettle.textconv "pentaho-live textconv"
git config diff.kettle.cachetextconv true
```

From then on `git diff`, `git log -p` and `git show` show one property per line (`condition: saldo > 1000`)
instead of XML; positions are left out (`pentaho-live textconv --positions` keeps them). A file that is not
valid XML (for example with conflict markers) is shown as it is. GitHub's web view does not run textconv.

### The normalizer and the pre-commit hook

`normalize` writes the same document always the same way: 2-space indentation, LF, UTF-8, Kettle's own
escaping, without `modified_user`/`modified_date`, step and job-entry attributes and parameters in a stable
order. Steps, hops, connections, notes and fields keep their order (Kettle keeps it, and the order of the hops
decides the round-robin distribution). It is tested on every example and pattern of this skill: Kettle loads the
normalized file with exactly the same `getXML()` (except the two volatile fields), validation gives the same
remarks, and running them gives the same results and output files.

Optional hook, `.git/hooks/pre-commit` (executable; `launcher/git/pre-commit` in the repository):

```sh
#!/bin/sh
exec pentaho-live pre-commit
```

It normalizes the staged `.ktr`/`.kjb` and stages them again. If a file also has unstaged changes it does not
touch it and stops the commit: run `pentaho-live normalize <file>`, stage it and commit again.

## 4. Limits

- Merging two branches that touched the same `.ktr` is still git's line merge (a merge driver by step is
  planned for 0.8); normalizing on every commit makes those merges much smaller.
- The rename heuristic can miss a step that was renamed **and** heavily reconfigured **and** moved and rewired:
  it then shows as removed + added, which is still correct.
- Jobs with several copies of an entry name them `name#1`, `name#2`.
- The diff compares the files; it does not run them. To see what changes in the **data**, run both versions.
