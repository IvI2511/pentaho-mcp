# {{NAME}}

Proyecto de ETL con Pentaho Data Integration 9.4 y [Pentaho Live](https://github.com/IvI2511/pentaho-mcp). Lo preparó `pentaho-live init` (Pentaho Live {{VERSION}}).

## Carpetas

| Carpeta | Qué va |
|---|---|
| `etl/` | Las transformaciones (`.ktr`) y los jobs (`.kjb`). |
| `datasets/` | Datos de entrada pequeños para desarrollar y probar (CSV, Excel, SQLite de prueba). |
| `tests/` | Pruebas con datasets dorados (`*.test.json`): datos de entrada fijos y la salida esperada (`test_create` con Claude, o IDC > Pruebas… en Spoon; también `tests/` junto a una `.ktr`). |
| `contracts/` | Contratos de datos de las salidas (columnas, tipos, reglas). |
| `env/` | Variables por entorno: `dev.properties` es el ejemplo compartido; tus valores y contraseñas, en `env/local.properties` (no se sube). |
| `.pentaho-live/` | El perfil del linter (`lint.yaml`) y su esquema. |
| `.github/workflows/` | La integración continua: el linter, las pruebas y el diff semántico en cada pull request. |

## Convenciones

Las fija `.pentaho-live/lint.yaml` y las comprueban, sin configurar nada, Claude (`lint_etl`), «Revisar calidad…» del menú IDC de Spoon, la sección Calidad de los informes, el corrector y la CI:

- **Puntaje mínimo**: cada `.ktr`/`.kjb` tiene que llegar al `min_score` del perfil.
- **Nombres**: cada step empieza con el prefijo de su categoría (`IN_` entradas, `OUT_` salidas, `LKP_` búsquedas, `FLT_` filtros…). «Revisar calidad…» lo arregla solo: renombra y se deshace con un Ctrl+Z.
- **Nada de rutas ni hosts escritos a mano**: las rutas empiezan por `${DIR_DATOS}` o `${Internal.Entry.Current.Directory}`, y los hosts de las conexiones usan `${DB_HOST}`.
- **Contraseñas** nunca en el `.ktr`: en `env/local.properties` o en variables del entorno.
- **Reglas del equipo**: patrones en `team_rules` (por ejemplo, nada en carpetas temporales).

## Comprobarlo en tu ordenador

```sh
pentaho-live lint etl              # usa .pentaho-live/lint.yaml; sale con 1 si alguno no llega al mínimo
pentaho-live lint etl --format json
pentaho-live test --all            # cada *.test.json; sale con 1 si alguna falla
pentaho-live test --all --lang es  # lo mismo en español (en, es, pt; por defecto, el idioma del sistema)
pentaho-live diff --rev main --format markdown   # qué cambió en cada .ktr/.kjb desde main, por significado
```

En Spoon: menú **IDC > Revisar calidad…** muestra «Perfil: {{NAME}}» y los hallazgos de las convenciones con su arreglo.

## Integración continua

`.github/workflows/pentaho-live.yml` instala PDI CE 9.4 con Pentaho Live (con caché) y, en cada pull request y push a `main`, pasa el linter con el perfil a todo `etl/`, corre las pruebas con datasets dorados y escribe en el resumen del job el diff semántico de los `.ktr`/`.kjb` cambiados contra la rama base. El mismo diff puede ir como comentario en la pull request: pon `PENTAHO_LIVE_PR_COMMENT: "true"` y descomenta `pull-requests: write` en el workflow.

**Cómo leer una ejecución.** Abre la ejecución en la pestaña **Actions** del repositorio (o **Details** junto a la comprobación de la pull request). Cada hallazgo del linter y cada prueba que no pasa es una anotación (`::error` / `::warning`) en su archivo: las ves en el recuadro **Annotations** de la ejecución y, en una pull request, también en **Files changed** si el archivo forma parte de ella. La página **Summary** de la ejecución tiene tres secciones: la nota de lint de cada archivo, cada prueba con la primera diferencia que encontró (clave, columna, valor esperado y obtenido) y el diff semántico de los `.ktr`/`.kjb` cambiados. Una ✗ roja en el paso de las pruebas quiere decir que cambió una salida dorada: si el cambio era a propósito, vuelve a crear la prueba (`test_create` con `replace: true`) en la misma pull request. Más en [manual/13-proyecto.md](https://github.com/IvI2511/pentaho-mcp/blob/main/claude/skills/pentaho-etl/manual/13-proyecto.md#reading-a-ci-run).

## Diff legible en git

`.gitattributes` ya manda los `.ktr`/`.kjb` al driver de diff `kettle`; corre una vez en cada clon las líneas que imprime `pentaho-live git-setup` (`git config diff.kettle.textconv "pentaho-live textconv"`) y `git diff` muestra los cambios por step y no como XML. `pentaho-live normalize` escribe los `.ktr`/`.kjb` en forma canónica: dos guardados sin cambios reales no dan diff.
