# 13 · Team projects: the lint profile, `pentaho-live init` and CI

Since 0.7.0 a project or a course can **share its conventions** in one file, `.pentaho-live/lint.yaml`, and
every check uses it with no setup: `lint_etl` and `apply_lint_fixes` (Claude), **IDC > Check quality…** in Spoon,
the `quality` section of the reports, the grader (`lint:profile`) and `pentaho-live lint` on the command line and in
GitHub Actions. `pentaho-live init` (or the `project_init` tool, or **IDC > Prepare project…**) prepares a team
project with that profile, the folders, `.gitignore`, a README and the CI workflow.

---

## 1. Where the profile is

The linter looks for `.pentaho-live/lint.yaml` (or `lint.yml`, `lint.json`) **going up the folders** from the
folder of the `.ktr`/`.kjb`. So one profile at the root of the repository (or of the course folder) covers every
file below it. A document that was never saved has no folder: no profile.

- `lint_etl` / `apply_lint_fixes`: argument `profile` = a profile file, or a folder to search from, or `"none"`
  (default rules only). Without it, the one found going up. The result says it:
  `profile: {name, path, min_score, passed}`, or `profile: {path, error}` if the file could not be read (the
  check goes on with the default rules: tell the person what is wrong in the file).
- `lint_profile` (`path` or `document`) returns the whole **effective profile**: rules on/off and severities,
  prefixes, required variables, team rules, warnings about unknown keys.
- In Spoon, the quality dialog shows **"Profile: <name> (<path>) · minimum 75: met"** under the score.

## 2. The format

```yaml
version: 1
name: Curso BD                 # shown as "Profile: Curso BD"
description: Conventions of the course
min_score: 75                  # 0-100; with one error the score never goes above 74

rules:                         # rule id (pentaho://lint/rules) -> off | on | error | warning | info
  absolute_path: error
  default_step_name: warning
  select_star: off
  db_output_without_error_handling: on   # an off-by-default rule turned on: it now counts

robustness: false              # true = also the robustness family (its own sub-score)

naming:                        # rule naming_convention
  steps:                       # prefix per step category
    input: IN_
    output: OUT_
    lookup: LKP_
    filter: FLT_
    by_type: { SortRows: ORD_ }   # a given step type id wins over its category
  entries:                     # job entries
    transformation: TR_
    job: JB_

step_description:              # rule step_without_description
  required: true
  except: [Dummy]              # step or entry type ids that need none

required_variables:            # rule required_variable
  paths: [DIR_DATOS, Internal.Entry.Current.Directory]   # a file path has to START with one of them
  hosts: [DB_HOST]             # a connection's host has to use one of them

team_rules:                    # rule team_pattern: patterns only, never code
  - id: no_tmp_folder
    title: Nothing in temporary folders
    message: "'{where}' uses a temporary folder ({match})"   # {where} = step or entry, {match}
    fix: Use ${DIR_DATOS} or salida/.
    severity: warning          # error | warning (default) | info
    where: path                # xml (default) | name | path | sql | description
    step_types: [TextFileOutput]   # optional: only these step (or entry) types
    pattern: '(?i)(^|[\\/])te?mp([\\/]|$)'   # Java regular expression, at most 500 characters
    must: not_match            # not_match (default: it must not appear) | match (it must appear)
```

- **Categories** of steps: `input`, `output` (bulk loaders and dimension lookups included), `lookup`, `filter`
  (Filter rows, Switch / case, Java filter), `join`, `transform`, `aggregate`, `flow`, `script`, `validation`,
  `utility`, `mapping`, and `other` for types without a category. Of entries: `transformation`, `job`, `sql`,
  `file`, `condition`, `mail`, `utility`, `other`. The category comes from the step **type id**, never from
  Spoon's translated category name, so the profile means the same on every computer.
- `on`/`off` without quotes are fine (YAML reads them as true/false). Unknown keys and rule ids are **warnings**
  (the profile still works, so an older Pentaho Live can read a newer profile); a wrong severity, a regex that does
  not compile or a `min_score` out of 0-100 is an **error** naming the key.
- Editors: `pentaho-live init` writes `.pentaho-live/lint.schema.json` (JSON Schema) and the first line of
  `lint.yaml` points to it, so VS Code (YAML extension) completes and checks the keys.
- `lint.json` is the same object in JSON.

## 3. The profile's rules

They are **off when there is no profile**, and run (and count in the score) when their section is set and the
profile does not say `off`. Like any rule, `rules.<id>` sets their severity.

| Rule | Default sev | What it flags | Fix |
|---|---|---|---|
| `naming_convention` | I | A step or entry whose name does not start with the prefix of its category (or `by_type`) | **rename** to `prefix + name` (a wrong prefix of the profile is replaced): automatic, one Ctrl+Z, references updated. A name that is a Spoon default is left to `default_step_name`, whose proposed name already carries the prefix |
| `step_without_description` | I | A step (or entry) with an empty description | by hand: right-click > Edit description |
| `required_variable` | W | A file path that does not start with a profile variable (references to other `.ktr`/`.kjb` and `%%java.io.tmpdir%%` do not count); a connection whose host does not use one | an absolute path: `${DIR_DATOS}/file` with the parameter `DIR_DATOS` = its folder (confirm); else a value. The host, by hand. With this section, `absolute_path` proposes the profile's variable too |
| `team_pattern` | per rule | Each team rule, on every step (or entry) of its types: the key is `team_pattern:step:<step>:<rule id>` | the rule's `fix` text |

A team rule's regex has a time limit (250 ms per text): one that backtracks forever is stopped and the step is
listed under `not_evaluated` with code `timeout`.

## 4. `pentaho-live init`, `project_init`, «Prepare project…»

```sh
pentaho-live init [folder] [--name "Curso BD"] [--lang en|es|pt] [--dry-run]
```

Creates **only what is missing**, never overwrites or edits a file:

| Path | What |
|---|---|
| `etl/`, `datasets/`, `tests/`, `contracts/` | with a `.gitkeep` (git does not keep empty folders) |
| `env/dev.properties` | the shared example of the environment variables (`DIR_DATOS`, `DB_HOST`…), no secrets |
| `.gitignore` | `env/local.properties` and `*.secret.properties`, `kettle.properties`, `salida/`, logs, `runs.sqlite`, `*.sqlite` except under `datasets/` and `tests/` |
| `.gitattributes` | `.ktr`/`.kjb` as text, binaries, and the readable-diff driver `diff=kettle` (commented while this Pentaho Live has no `pentaho-live textconv`) |
| `.pentaho-live/lint.yaml` | the default **course profile**: `min_score: 75`, `absolute_path` and `password_in_file` as errors, `default_step_name` as a warning, `IN_`/`OUT_`/`LKP_`/`FLT_` and `TR_`/`JB_`, `${DIR_DATOS}`/`${Internal.Entry.Current.Directory}` and `${DB_HOST}`, one team rule |
| `.pentaho-live/lint.schema.json` | the JSON Schema of the profile |
| `README.md` | the folders, the conventions and how to check them |
| `.github/workflows/pentaho-live.yml` | the CI (§5) |

`project_init` takes `folder` (absolute), `profile_name`, `language`, `dry_run`, and returns `created` and
`existing`. **Ask the person before creating files in a folder they did not name.** In Spoon, **IDC > Prepare
project…** shows the same list (what will be created, what is already there) before creating anything.

## 5. `pentaho-live lint` and CI

```sh
pentaho-live lint etl                       # every .ktr/.kjb under etl/, each with the profile found above it
pentaho-live lint a.ktr b.kjb --min-score 80 --lang es
pentaho-live lint etl --format json         # the lint_etl result of every file
pentaho-live lint etl --format github --summary "$GITHUB_STEP_SUMMARY"
pentaho-live profile [folder]               # the effective profile (JSON)
```

Exit code: **0** every file reaches the minimum (`--min-score`, else the profile's `min_score`, else none);
**1** a file is below it, could not be loaded, or its profile could not be read; **2** a usage error or a
`--profile` that does not exist. Kettle's own log goes to stderr, so `--format json` can be piped. No Spoon is
needed: the launcher runs `io.github.ivi2511.pentahomcp.ProyectoMain` with PDI's `lib/` and the plugin's jar.
On macOS/Linux, `launcher/pentaho-live` does the same with `PDI_HOME` (default `~/pentaho/data-integration`).

The workflow that `init` writes (GitHub Actions, `ubuntu-24.04`):

1. Java 11 (Temurin); PDI CE 9.4 **with the plugin of the same version** from the release
   (`pdi-ce-9.4-con-pentaho-mcp.zip`), kept in `actions/cache` (about 400 MB, downloaded once per version);
2. `ProyectoMain lint etl --format github --summary $GITHUB_STEP_SUMMARY`: each finding becomes an annotation on
   the file, a table goes to the job summary, and the job **fails** if a file is below the profile's `min_score`;
3. two `TODO(...)` steps, commented: golden-dataset tests in `tests/` and the semantic diff of the changed
   `.ktr`/`.kjb` as a pull-request comment, for when those commands exist.

To try the same check before pushing: `pentaho-live lint etl`.

## 6. The grader: `lint:profile`

A pack can carry the course profile at `<pack>/.pentaho-live/lint.yaml` (only there: never searched upwards).
The rubric line `lint:profile` passes when the score of the submission with that profile reaches its `min_score`
(75 if it has none). Like every `lint:` line: suppressions are ignored, no deep mode, no rules that depend on the
computer. Without a profile in the pack the line is `evaluated: false`.

## 7. When something goes wrong

| Symptom | Cause and fix |
|---|---|
| `profile.error` / "Profile: … could not be read" | YAML or a value is wrong: the message names the key and the line. `lint_profile` with `path` shows it too |
| No "Profile" line | The document is not saved, or there is no `.pentaho-live/` above its folder: `lint_profile` says where it searched |
| `naming_convention` flags a step of a type with no category | Use `naming.steps.by_type` for that type id, or `other` |
| `required_variable` on a path that already has a variable | It must **start** with one of `required_variables.paths`: add that variable to the list or change the path |
| CI: `ClassNotFoundException: ...ProyectoMain` | The PDI zip of that version has a plugin older than 0.7.0: set `PENTAHO_LIVE_VERSION` in the workflow to a release with it |
| CI is slow on the first run | It downloads PDI once; later runs use the cache until `PENTAHO_LIVE_VERSION` changes |
