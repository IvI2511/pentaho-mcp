# {{NAME}}

ETL project with Pentaho Data Integration 9.4 and [Pentaho Live](https://github.com/IvI2511/pentaho-mcp). Set up by `pentaho-live init` (Pentaho Live {{VERSION}}).

## Folders

| Folder | What goes in it |
|---|---|
| `etl/` | Transformations (`.ktr`) and jobs (`.kjb`). |
| `datasets/` | Small input data to develop and test with (CSV, Excel, test SQLite). |
| `tests/` | Tests with fixed input data and their expected output. |
| `contracts/` | Data contracts of the outputs (columns, types, rules). |
| `env/` | Variables per environment: `dev.properties` is the shared example; your values and passwords go in `env/local.properties` (not committed). |
| `.pentaho-live/` | The lint profile (`lint.yaml`) and its schema. |
| `.github/workflows/` | Continuous integration: the linter on every pull request. |

## Conventions

`.pentaho-live/lint.yaml` sets them, and they are checked with no setup by Claude (`lint_etl`), Spoon's IDC menu "Check quality...", the Quality section of the reports, the grader and CI:

- **Minimum score**: every `.ktr`/`.kjb` has to reach the profile's `min_score`.
- **Names**: every step starts with the prefix of its category (`IN_` inputs, `OUT_` outputs, `LKP_` lookups, `FLT_` filters...). "Check quality..." fixes it for you: it renames, and one Ctrl+Z undoes it.
- **No paths or hosts written by hand**: paths start with `${DIR_DATOS}` or `${Internal.Entry.Current.Directory}`, and connection hosts use `${DB_HOST}`.
- **Passwords** never in the `.ktr`: in `env/local.properties` or in environment variables.
- **Team rules**: patterns in `team_rules` (for example, nothing in temporary folders).

## Check it on your computer

```sh
pentaho-live lint etl              # uses .pentaho-live/lint.yaml; exits with 1 if one is below the minimum
pentaho-live lint etl --format json
```

In Spoon: **IDC > Check quality...** shows "Profile: {{NAME}}" and the convention findings with their fix.

## Continuous integration

`.github/workflows/pentaho-live.yml` installs PDI CE 9.4 with Pentaho Live (cached) and lints all of `etl/` with the profile on every pull request. The steps marked `TODO` (golden-dataset tests and semantic diff) come with later Pentaho Live versions.

## Readable diffs in git

With Pentaho Live 0.7 or later, `pentaho-live git-setup` prints the lines that make `git diff` show the changes of a `.ktr` per step instead of as XML (see `.gitattributes`).
