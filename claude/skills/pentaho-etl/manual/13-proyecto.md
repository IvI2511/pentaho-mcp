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
| `.gitattributes` | `.ktr`/`.kjb` as text, binaries, and the readable-diff driver `diff=kettle` for `pentaho-live textconv` (each clone runs the two `git config` lines of `pentaho-live git-setup` once; `manual/11-versiones.md`) |
| `.pentaho-live/lint.yaml` | the default **course profile**: `min_score: 75`, `absolute_path` and `password_in_file` as errors, `default_step_name` as a warning, `IN_`/`OUT_`/`LKP_`/`FLT_` and `TR_`/`JB_`, `${DIR_DATOS}`/`${Internal.Entry.Current.Directory}` and `${DB_HOST}`, one team rule |
| `.pentaho-live/lint.schema.json` | the JSON Schema of the profile |
| `README.md` | the folders, the conventions and how to check them |
| `.github/workflows/pentaho-live.yml` | the CI (§5) |

`project_init` takes `folder` (absolute), `profile_name`, `language`, `dry_run`, and returns `created` and
`existing`. **Ask the person before creating files in a folder they did not name.** In Spoon, **IDC > Prepare
project…** shows the same list (what will be created, what is already there) before creating anything.

## 5. `pentaho-live lint`, `test`, `diff` and CI

```sh
pentaho-live lint etl                       # every .ktr/.kjb under etl/, each with the profile found above it
pentaho-live lint a.ktr b.kjb --min-score 80 --lang es
pentaho-live lint etl --format json         # the lint_etl result of every file
pentaho-live lint etl --format github --summary "$GITHUB_STEP_SUMMARY"
pentaho-live profile [folder]               # the effective profile (JSON)
pentaho-live test --all                     # every *.test.json under the current folder (manual/12-pruebas.md)
pentaho-live test etl/ventas.ktr --format github --summary "$GITHUB_STEP_SUMMARY"
pentaho-live diff --rev origin/main --format markdown   # every .ktr/.kjb changed since that revision
```

Exit code: **0** every file reaches the minimum (`--min-score`, else the profile's `min_score`, else none);
**1** a file is below it, could not be loaded, or its profile could not be read; **2** a usage error or a
`--profile` that does not exist. Kettle's own log goes to stderr, so `--format json` can be piped; since 0.7.1
without the hundreds of "Unexpected error registering jar plugin file" / `ClassNotFoundException: ...Dialog` lines a
PDI without Spoon used to print (only real errors are left). No Spoon is needed: the launcher runs
`io.github.ivi2511.pentahomcp.ProyectoMain` with PDI's `lib/` and the plugin's jar, Java 8 to 21 (the
`--add-opens` Kettle needs on 17+ go with `-XX:+IgnoreUnrecognizedVMOptions`, which Java 8 skips). On
macOS/Linux, `launcher/pentaho-live` does the same with `PDI_HOME` (default `~/pentaho/data-integration`).

`pentaho-live test [--all] [tests, .ktr files or folders]` runs the golden-dataset tests like `test_run_all`, in a
separate JVM with its own empty Kettle settings: with no paths (or `--all`) every `*.test.json` under the current
folder (hidden folders such as `.git` skipped); a `.ktr` runs the tests of its `tests/` folders. `--format text`
(default), `json` (the `test_run` result of each test) or `github` (an `::error` annotation on the file of each test
that does not pass, plus the text); `--summary file.md` appends a table; `--timeout` seconds for the whole run
(default 1800). Exit code: **0** every test passed, or there are none; **1** one failed, had an error or did not
finish in time; **2** a usage error. The text, the annotations and the summary are in English, Spanish or
Portuguese (`--lang en|es|pt`, by default the system's language, like `lint` and `diff`); each failing output
names its first difference (`first: [key] column expected "x", got "y"`, or the first missing or extra row).
`--format json` keeps the English codes of `test_run` (`passed`, `failed`…), for tools.

`pentaho-live diff --rev REV [folders]` (no single file) compares every `.ktr`/`.kjb` that changed between the git
revision `REV` (default `HEAD`) and the working folder, by meaning, as text, `--format markdown` or `json`; new and
removed files get one line. Exit code: 0 nothing changed, 1 something changed, 2 an error (an unknown revision: in
a shallow CI checkout, fetch the base branch first).

The workflow that `init` writes (GitHub Actions, `ubuntu-24.04`, actions on Node 24: `checkout@v7`,
`setup-java@v6`, `cache@v6`):

1. Java 11 (Temurin); PDI CE 9.4 **with the plugin of the same version** from the release
   (`pdi-ce-9.4-con-pentaho-mcp.zip`), kept in `actions/cache` (about 400 MB, downloaded once per version: about
   10 s on GitHub's runners; the whole job takes about half a minute);
2. a `pentaho-live` command on the `PATH` (the same as `launcher/pentaho-live` on Linux, with that PDI);
3. `pentaho-live lint etl --format github --summary $GITHUB_STEP_SUMMARY`: each finding becomes an annotation on
   the file, a table goes to the job summary, and the job **fails** if a file is below the profile's `min_score`;
4. `pentaho-live test --all --format github --summary $GITHUB_STEP_SUMMARY`: the golden-dataset tests, also when
   the lint step failed; a test that does not pass is an annotation on its `.test.json` and fails the job;
5. on a pull request, `git fetch` of the base branch and `pentaho-live diff --rev origin/<base> --format markdown`
   into the job summary: what changed in each `.ktr`/`.kjb` by meaning, never passwords;
6. optional, off by default: the same diff as a comment on the pull request (`gh pr comment`, it edits its own
   comment on later pushes). Turn it on with `PENTAHO_LIVE_PR_COMMENT: "true"` and `pull-requests: write` in the
   job's permissions; on pull requests from forks the token is read-only and the comment is skipped.

To try the same checks before pushing: `pentaho-live lint etl`, `pentaho-live test --all` and
`pentaho-live diff --rev main --format markdown`.

### Reading a CI run

Open the run from the repository's **Actions** tab, or with **Details** next to the `pentaho-live` check of the
pull request. Three places tell what happened:

- **The steps.** "Lint with the project profile", "Tests with golden datasets" and "Semantic diff against the base
  branch" each turn red on their own; the tests and the diff run even when the lint step failed, so one push shows
  everything. Their logs are the same text as on your computer (`ok`/`FAIL` per file or test and a total).
- **The annotations** (`::error` / `::warning`), in the run's **Annotations** box and, on a pull request, in
  **Files changed** when the file is part of it: a lint finding of `warning` or `error` severity on its
  `.ktr`/`.kjb`, a file below `min_score` ("lint score"), and a test that does not pass on its `.test.json`, for
  example:

  ```
  Test kpi_tienda_categoria failed: 1 of 1 outputs differ: 'Redondear importe' 20 changed rows
  output "Redondear importe": 20 changed (rows expected 20, got 20); first: [Chamberí | Bebidas] unidades expected "91", got "37"
  ```

- **The Summary page** of the run (the job summary): "Pentaho Live lint" (score, errors, warnings and result of
  each file, and the profile), "Pentaho Live tests" (each test, its result, seconds and the first difference) and,
  on a pull request, "Pentaho Live: semantic diff against origin/<base>" (each changed step, setting, hop and
  connection, e.g. `condition: … cantidad > 0 → … cantidad > 1`). With `PENTAHO_LIVE_PR_COMMENT: "true"` the
  diff is also the pull request's comment from `github-actions`, edited on every push.

A red test after a change on purpose means the golden output is old: create the test again (`test_create` with
`replace: true`, `manual/12-pruebas.md`) in the same pull request, and the reviewer sees both the diff and the new
golden CSV. A red test you did not expect is the point of the test: the annotation says which output, which key
and which column.

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
| CI: "Node.js 20 actions are deprecated" | The workflow was written by `init` 0.7.0 (`checkout@v4`, `setup-java@v4`, `cache@v4`): change them to `@v7`, `@v6` and `@v6` |
| CI log full of `ClassNotFoundException: ...Dialog` | Pentaho Live 0.7.0 printed PDI's plugin-registration noise without Spoon; 0.7.1 filters it (set `PENTAHO_LIVE_VERSION` to it). Harmless either way |
| CI: `unknown git revision 'origin/main'` in the diff step | The checkout is shallow: the workflow fetches the base branch first; keep that `git fetch` line if you edit the step |
| CI: a test passes on your computer and fails in CI | The test runs with its `environment` (locale, time zone, encoding) in both places; look for paths or variables only your computer has (`test_list` shows its datasets and tweaks) |
