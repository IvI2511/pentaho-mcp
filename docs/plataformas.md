# Pentaho Live en macOS y Linux

Resultado de la prueba automática `.github/workflows/plataformas.yml` (rama `ci/plataformas`, EXT-445),
corrida en las máquinas reales de GitHub Actions el 20-oct-2026 con el **último release (0.4.1)**:
`instalar.sh --instalar-pdi` baja `pdi-ce-9.4-con-pentaho-mcp.zip`, Pan corre un ejemplo del manual,
se abre Spoon y un cliente MCP chico (`tools/ci/cliente_mcp.py`) hace `initialize`, `tools/list`,
`status`, `new_transformation`, `build_steps` (Generate rows → Add constants → Dummy), `validate`,
`preview_step`, `canvas_screenshot` y `save`; al final Pan corre el `.ktr` que guardó Spoon.

## Resumen

| Plataforma (runner) | Instala | Pan | Spoon + MCP | Ventana de Spoon |
|---|---|---|---|---|
| macOS 15, chip Apple M1 (`macos-15`), Rosetta + Java 21 x64 | sí | sí | **sí**, las 9 llamadas | **en blanco** salvo la barra de arriba (problema 5) |
| macOS 26, chip Apple (`macos-26`), Rosetta + Java 21 x64 | sí | sí | **sí** | igual que macOS 15 |
| macOS 15 Intel (`macos-15-intel`), Java 21 x64 | sí | sí | **sí** | igual que macOS 15 |
| Ubuntu 24.04 x64 (`ubuntu-24.04`), GTK 2 + Xvfb | sí | sí | **sí** | completa, con un aviso por `libwebkitgtk-1.0` |
| Ubuntu 26.04 x64 (`ubuntu-26.04`), GTK 2 + Xvfb | sí | sí | **sí** | igual que Ubuntu 24.04 |
| Chip Apple con Java **arm64** nativo | sí | `pan.sh` a secas corta; con `arch -x86_64 /bin/sh pan.sh`, sí | **no**: `spoon.sh` corta y SWT no carga | — |

«Sí» quiere decir controlado por el contenido, no solo por el código de salida: `/ping` contesta
`{"ok":true,"spoon":true,"tools":54}`, `tools/list` trae 54 herramientas, la vista previa devuelve 5 filas
con los campos `saludo`, `n` y `plataforma` (y el nombre de la plataforma adentro), la foto del lienzo es un
PNG con los tres steps, el `.ktr` guardado tiene los tres steps y Pan lo corre con
`Dummy.0 - Finished processing (I=0, O=0, R=5, W=5, U=0, E=0)`. El CSV del ejemplo 01 sale **idéntico byte
a byte** en las cinco plataformas (con «Ñandú» y «España» en UTF-8).

El plugin de Spoon y la skill no tienen nada específico de Windows: todo lo que hubo que resolver es de
PDI 9.4 (scripts, SWT) o del zip del release.

## Mac con chip Apple (M1–M4): pasos exactos

PDI 9.4 trae la librería gráfica de macOS (`libswt/osx64/swt.jar`, SWT 4.18) **solo para x86_64**, y
`spoon.sh` corta en arm64. La salida es correr Spoon entero bajo Rosetta 2, con un Java x64.

**Con el `instalar.sh` de la rama `ci/instalar-mac-apple`** (todavía no está en `main`, que es el que bajan
los usuarios), el script hace los pasos 1, 2 y 4 de abajo. En el paso 3 («Java») detecta el chip Apple,
busca un Java x64 (`PENTAHO_JAVA_HOME`, `~/pentaho/java-x64`, `JAVA_HOME`, `java_home -a x86_64`), ofrece
instalar lo que falta —Rosetta 2 con `softwareupdate` y Temurin 21 x64 en `<destino>/java-x64`, controlado
con el SHA-256 que Adoptium publica al lado— y deja `data-integration/spoon-rosetta.sh`, que abre Spoon con
`PENTAHO_JAVA_HOME` y `arch -x86_64`. «Done. Next» imprime ese comando en lugar de `spoon.sh`:

```bash
curl -fsSL https://raw.githubusercontent.com/IvI2511/pentaho-mcp/main/instalar.sh -o /tmp/instalar-pentaho-mcp.sh
bash /tmp/instalar-pentaho-mcp.sh --instalar-pdi     # en el paso 3 pregunta «Install what is missing now? [Y/n]»
~/pentaho/data-integration/spoon-rosetta.sh
```

Pregunta solo si tiene una terminal (`[ -t 0 ]`): con `curl … | bash` o desde un agente no la tiene, avisa,
no instala nada e indica volver a correrlo con `--rosetta`, que instala sin preguntar. Los pasos y los
códigos de salida son los de `instalar.ps1`: nada de esto corta la instalación (si Rosetta o el Java no se
pueden instalar, lo dice y sigue con el paso 4, como el «NO Java» de siempre). Probado con el job
`receta-mac-apple` (corrida 10).

A mano, o con el `instalar.sh` de `main` hasta que se publique:

```bash
# 1. Rosetta 2 (no viene instalada en un Mac nuevo; en el runner tampoco estaba)
softwareupdate --install-rosetta --agree-to-license

# 2. Java 21 x64 (Temurin) en una carpeta propia: no pisa un Java arm64 que ya tengas
mkdir -p ~/pentaho/java-x64
curl -fL https://api.adoptium.net/v3/binary/latest/21/ga/mac/x64/jdk/hotspot/normal/eclipse | tar -xz -C ~/pentaho/java-x64 --strip-components 1

# 3. PDI 9.4 con el plugin (y, si están, Claude Code y Codex)
curl -fsSL https://raw.githubusercontent.com/IvI2511/pentaho-mcp/main/instalar.sh -o /tmp/instalar-pentaho-mcp.sh
bash /tmp/instalar-pentaho-mcp.sh --instalar-pdi

# 4. Abrir Spoon bajo Rosetta con ese Java
PENTAHO_JAVA_HOME=~/pentaho/java-x64/Contents/Home arch -x86_64 /bin/sh ~/pentaho/data-integration/spoon.sh
```

Después: `curl http://127.0.0.1:8765/ping` → `{"ok":true,"spoon":true,"tools":54}`. Para no escribir el
paso 4 cada vez, en `~/.zshrc`:

```bash
alias spoon='PENTAHO_JAVA_HOME=~/pentaho/java-x64/Contents/Home arch -x86_64 /bin/sh ~/pentaho/data-integration/spoon.sh'
```

Por qué cada cosa:

- `arch -x86_64` hace que el shell corra bajo Rosetta: `uname -m` da `x86_64` y `spoon.sh` elige
  `libswt/osx64`. Sin eso corta con «I'm sorry, this Mac platform [arm64] is not yet supported!».
- El Java tiene que ser x64: un proceso arm64 no puede cargar el SWT x86_64. Con un Java arm64, aun
  pasando el control de `spoon.sh`, Spoon muere con
  `UnsatisfiedLinkError: ... libswt-cocoa-4940r23.jnilib ... incompatible architecture (have 'x86_64', need 'arm64e' or 'arm64')`.
- `PENTAHO_JAVA_HOME` gana sobre `JAVA_HOME` en `set-pentaho-env.sh`, así que no hace falta cambiar el
  Java del resto del sistema.
- La `.app` con doble clic no sirve en chip Apple: `JavaApplicationStub` termina en el mismo `spoon.sh` y
  corta igual. Desde la Terminal sí abre bajo Rosetta (probado en `macos-15` con `--env JAVA_HOME=…`,
  `/ping` con 54 herramientas; `PENTAHO_JAVA_HOME` sigue el mismo camino en `set-pentaho-env.sh` y tiene
  prioridad; con la 0.4.1 hace falta antes el `chmod` del problema 1):
  `open --arch x86_64 --env PENTAHO_JAVA_HOME=$HOME/pentaho/java-x64/Contents/Home ~/pentaho/data-integration/Data\ Integration.app`
- El paso 3 del `instalar.sh` de `main` («Java») mira el `java` del PATH, no `PENTAHO_JAVA_HOME`: en un Mac
  sin otro Java dice «NO Java: Spoon will not open», y con el Java de `~/pentaho/java-x64` Spoon abre igual.
  El de `ci/instalar-mac-apple` mira el Java que va a usar `spoon.sh`, en el orden de `set-pentaho-env.sh`
  (`PENTAHO_JAVA_HOME`, `jre`/`java` junto a PDI, `JAVA_HOME`, `JRE_HOME`, PATH), y no toma el
  `/usr/bin/java` de macOS sin JDK (es un stub que pide instalar uno) por un Java.

Pan y Kitchen (sin ventana) sí pueden correr con un Java **arm64** nativo si el shell va bajo Rosetta:
`arch -x86_64 /bin/sh pan.sh ...` con `PENTAHO_JAVA_HOME` apuntando a un Java arm64 corrió el ejemplo 01
sin errores, y la JVM arrancó en ~2 s contra ~8 s bajo Rosetta. Es opcional: con el Java x64 de arriba
también andan (`arch -x86_64 /bin/sh ~/pentaho/data-integration/pan.sh -file=...`).

## Mac Intel

Cualquier Java 11+ de 64 bits (probado con Temurin 11, 17 y 21 x64): `bash instalar.sh --instalar-pdi` y
`~/pentaho/data-integration/spoon.sh`. Con la 0.4.1, la `.app` necesita antes el `chmod` del problema 1;
con eso abre (probado con `open`).

## Linux

`spoon.sh` exporta `SWT_GTK3=0` y el SWT de PDI 9.4 para Linux es el 4.9 (2018): **usa GTK 2**.

```bash
sudo apt install libgtk2.0-0t64      # Ubuntu 24.04 y 26.04 (en versiones viejas: libgtk2.0-0)
bash /tmp/instalar-pentaho-mcp.sh --instalar-pdi
~/pentaho/data-integration/spoon.sh
```

Al abrir sale un aviso modal: «Unfortunately, the libwebkitgtk-1.0 package is missing…». Se cierra con
**Close** y Spoon anda; lo que no anda es lo que usa el navegador embebido (página de bienvenida, ayuda).
`libwebkitgtk-1.0` ya no existe en Ubuntu desde la 18.10, así que no hay paquete que instalar. El aviso no
traba al MCP: la prueba armó, previsualizó y guardó con el aviso abierto. En el log queda
`Could not open a browser ... SWT on GTK 2.x detected`, que es lo mismo.

## Tiempos (corrida 2, en segundos)

| | instalar.sh (baja 368 MB) | Pan ejemplo 01 | Spoon hasta `/ping` | 9 llamadas MCP | job entero |
|---|---|---|---|---|---|
| macOS 15 M1, Rosetta | 15 | 12 | 26 | 3 | 96 |
| macOS 26 chip Apple, Rosetta | 7 | 13 | 28 | 4 | 91 |
| macOS 15 Intel | 12 | 13 | 41 | 11 | 117 |
| Ubuntu 24.04 | 13 | 5 | 8 | 1 | 54 |
| Ubuntu 26.04 | 8 | 3 | 6 | 1 | 40 |

En la corrida 1 los números fueron parecidos (Spoon hasta `/ping`: 40 s M1, 39 s Intel, 7 s Linux). El
grueso del Pan en Mac es el arranque de la JVM y de Kettle, no la transformación (0,1–0,5 s).

## Problemas encontrados

1. **El zip de la 0.4.1 no trae permisos Unix.** Leído del directorio central del zip publicado
   (`tools/ci/permisos_zip.py`, sin bajarlo entero): `spoon.sh`, `pan.sh`, `spoon.command` y
   `Data Integration.app/Contents/MacOS/JavaApplicationStub` están «creados en MS-DOS», `-rw-rw-rw-`.
   `instalar.sh` hace `chmod +x *.sh` y eso salva los scripts (en disco quedan `-rwxrwxrwx`), pero
   `JavaApplicationStub` queda `-rw-rw-rw-` y la `.app` no abre. Arreglo para la 0.4.1:
   `chmod +x ~/pentaho/data-integration/Data\ Integration.app/Contents/MacOS/JavaApplicationStub`, que el
   `instalar.sh` de `ci/instalar-mac-apple` ya hace solo (junto con `spoon.command`).
   Desde la 0.4.2 lo resuelve `tools/armar-zip-pdi.py`; la prueba lo va a confirmar sola cuando la 0.4.2
   sea el último release (el paso «Permisos en el zip del release» pasa a decir «ejecutable»).
2. **`spoon.sh` y `pan.sh` cortan en arm64 con código de salida 0.** Imprimen «not yet supported» y
   terminan con `exit` sin número: un script o un agente que mira solo el código cree que anduvo. Por eso
   la prueba lee la salida y no solo el código.
3. **SWT x86_64 en una JVM arm64**: `UnsatisfiedLinkError ... incompatible architecture`. No tiene arreglo
   dentro de PDI 9.4 (habría que cambiar el `swt.jar` por uno arm64 de una versión más nueva de SWT, que
   no está probado con Spoon 9.4).
4. **Linux necesita GTK 2** y muestra el aviso de `libwebkitgtk-1.0` (arriba).
5. **Ventana de Spoon en macOS: se abre en blanco** salvo el botón de perspectivas y «Connect», en las
   tres VMs de Mac (15 y 26 con chip Apple, 15 Intel), desde el primer momento (antes de cualquier llamada
   MCP) y también con la receta de arriba y un `~/.kettle` nuevo. **No traba nada de Pentaho Live**: las 9
   llamadas MCP andan, la vista previa trae las filas y la foto del lienzo sale bien porque se pinta fuera
   de pantalla. Pero un usuario no vería los nodos aparecer. Lo que se averiguó (job `ventana-macos` y
   corridas 3 a 8):
   - **Los controles existen y están bien ubicados.** El árbol de accesibilidad de macOS
     (`tools/ci/ventana.applescript`) muestra la barra con New file, Open file, Save…, el divisor, el panel
     izquierdo (262×828) con las pestañas View y Design y, después del MCP, la pestaña `ci_plataformas`
     en el área derecha (1171×828). Lo que falta es el pintado; además, el árbol de steps y el buscador
     del panel Design no aparecen en el árbol de accesibilidad.
   - **No es SWT ni la VM ni Java**: una ventana SWT mínima (`tools/ci/SwtPrueba.java`: SashForm, Tree,
     CTabFolder y un Canvas pintado con GC) con el mismo `swt.jar` y la misma JVM se dibuja completa,
     también con Java2D/AWT inicializado en el proceso. Spoon sale igual con Java 11, 17 y 21.
   - **No es cómo se abre ni el estado de la ventana**: igual con `spoon.sh` y con la `.app` (`open`, que
     la deja al frente), y no cambia al traerla al frente, cambiarle el tamaño, minimizar y restaurar ni
     en pantalla completa. Tampoco con `-Djava.awt.headless=true`. El log de Spoon no tiene errores.
   - En Spoon corren los hilos «Java2D Queue Flusher» y «Java2D Disposer» (Batik rasteriza los íconos
     SVG con Java2D), pero la prueba con AWT de arriba lo descarta como causa por sí solo.

   **Falta mirarlo una vez en un Mac de verdad**: las VMs de GitHub usan gráficos virtuales y puede ser
   eso, pero no está demostrado (la ventana SWT mínima se dibuja en esas mismas VMs). En un Mac: seguir la receta de
   arriba y mirar la ventana; si sale en blanco, correr
   `arch -x86_64 ~/pentaho/java-x64/Contents/Home/bin/java -XstartOnFirstThread -cp ~/pentaho/data-integration/libswt/osx64/swt.jar tools/ci/SwtPrueba.java 30000`
   (desde un clon del repo) y ver si esa ventana sí se dibuja.
6. Java 21 avisa `WARNING: COMPAT locale provider will be removed in a future release` (por
   `-Djava.locale.providers=COMPAT,SPI` de `spoon.sh`). Es inofensivo con Java 21; con Java 23 o más nuevo
   ese proveedor ya no existe.
7. `ubuntu-latest` pasa a Ubuntu 26.04 entre el 19-oct y el 19-nov de 2026: la prueba fija `ubuntu-24.04`
   y `ubuntu-26.04`, y las dos pasan.

## Cómo se corre la prueba

Se dispara con cada push a una rama `ci/**` y a mano (Actions → Plataformas → Run workflow). Los runners
estándar son gratis en repos públicos; una corrida entera tarda ~2 min por job (más la cola de los
runners de Mac con chip Apple, que a veces es larga).

| Job | Qué hace |
|---|---|
| `probar` (5 plataformas) | lo de arriba; artifact `evidencia-<plataforma>` con `canvas.png`, `pantalla-*.png`, `cliente.log`, `ping.json`, `pan-*.log`, `spoon-salida.log`, `pdi-logs/`, `permisos-*.txt`, `tiempos.txt` |
| `macos-arm64-nativo` | Java arm64: `spoon.sh` y `pan.sh` cortan, Pan nativo con shell bajo Rosetta, Spoon con Java arm64 (falla de SWT). `continue-on-error`; verde si los cortes salen como dice este documento |
| `receta-mac-apple` | Mac con chip Apple como un usuario nuevo (sin `setup-java`, sin Rosetta preparada, sin `KETTLE_HOME` de prueba), solo con el `instalar.sh` de la rama: sin terminal (avisa, no instala), en una terminal de `script(1)` contestando «y» (Rosetta 2, Java x64, lanzador), con `--rosetta` cuando ya está todo (no vuelve a bajar el Java); abre Spoon con el comando de «Done. Next» y corre el cliente MCP. Hasta la corrida 9 hacía los cuatro pasos a mano con el `instalar.sh` de `main` |
| `ventana-macos` | diagnóstico de la ventana en blanco (problema 5): SWT mínima con y sin AWT, Spoon, geometría de la ventana, hilos, `java.awt.headless` |

`KETTLE_HOME` de prueba en `probar`: uno nuevo con `ShowTips=N`, `ShowWelcomePageOnStartup=N` y
`ShowRepositoriesAtStartup=N`, para que la primera apertura no dependa del diálogo de tips ni de la
página de bienvenida. `receta-mac-apple` no lo usa (primera apertura real).

Probar una versión sin publicar: hoy la prueba siempre baja el último release (es lo que hace
`instalar.sh`). Para probar la 0.4.2 antes de publicarla haría falta compilar el plugin en el runner
(necesita los jars de PDI, que ya baja) o subir el zip como artifact: queda para después.

## Corridas

Todas en https://github.com/IvI2511/pentaho-mcp/actions/workflows/plataformas.yml?query=branch%3Aci%2Fplataformas

| # | Corrida | Qué mostró |
|---|---|---|
| 1 | [39461748726](https://github.com/IvI2511/pentaho-mcp/actions/runs/39461748726) | macOS 15 M1 (Rosetta), macOS 15 Intel y Linux pasan enteros a la primera; la ventana de Spoon en Mac sale en blanco. El job de arm64 nativo se cortó por un `bash -e` del propio workflow (corregido) |
| 2 | [39462432891](https://github.com/IvI2511/pentaho-mcp/actions/runs/39462432891) | 6 de 6 en verde: se suman macOS 26 y Ubuntu 26.04; arm64 nativo documenta los dos cortes, Pan nativo y la falla de SWT |
| 3 | [39463261549](https://github.com/IvI2511/pentaho-mcp/actions/runs/39463261549) | ventana: igual con Java 11, 17 y 21; la ventana SWT mínima se dibuja. (El job nativo falló por GitHub: «not acquired by Runner») |
| 4 | [39465396482](https://github.com/IvI2511/pentaho-mcp/actions/runs/39465396482) | todo en verde; la `.app` abre en Intel con `open` y en chip Apple con `open --arch x86_64`; los remedios de ventana no cambian nada |
| 5 | [39466039172](https://github.com/IvI2511/pentaho-mcp/actions/runs/39466039172) | todo en verde, incluida `receta-mac-apple` (los 4 pasos tal cual en macOS 26, 83 s el job) |
| 6 | [39466461042](https://github.com/IvI2511/pentaho-mcp/actions/runs/39466461042) | todo en verde; posición y tamaño de cada control de la ventana |
| 7 | [39467208277](https://github.com/IvI2511/pentaho-mcp/actions/runs/39467208277) | hilos de Java2D en Spoon; con `java.awt.headless` sigue en blanco (cancelada después: lo que quedaba eran jobs arm64 repetidos, en cola) |
| 8 | [39467778493](https://github.com/IvI2511/pentaho-mcp/actions/runs/39467778493) | la ventana SWT mínima con Java2D/AWT inicializado también se dibuja (cancelada después, igual que la 7) |
| 9 | [39468686139](https://github.com/IvI2511/pentaho-mcp/actions/runs/39468686139) | **la de referencia**, con el workflow final: 8 de 8 en verde (5 plataformas, arm64 nativo, receta del documento y diagnóstico de la ventana) |

Evidencia en los artifacts de cada corrida (`gh run download <id> -R IvI2511/pentaho-mcp`).

## Texto propuesto para INSTALL.md y los README

No se editaron (los está cambiando la sesión de la 0.4.2). Propuesta, que da por hecho que el
`instalar.sh` de `ci/instalar-mac-apple` ya está en `main` (si no, el párrafo del chip Apple tiene que
volver a los cuatro pasos a mano y el de los permisos, a pedir el `chmod` de la `.app`). En la sección de
agentes, sumar `--rosetta` a las opciones de `instalar.sh` («Mac with an Apple chip: install Rosetta 2 and
an x64 Java without asking»); los códigos de salida no cambian.

### INSTALL.md (en)

En «Requirements», agregar: *On macOS with an Apple chip (M1–M4), Spoon runs under Rosetta 2 with an x64
Java (see "macOS and Linux" below).*

Nueva sección, antes de «What the script returns»:

````markdown
## macOS and Linux

Tested on GitHub Actions (macOS 15 and 26 with an Apple chip, macOS 15 Intel, Ubuntu 24.04 and 26.04):
install, Pan, opening Spoon and every MCP call work. Details: [`docs/plataformas.md`](docs/plataformas.md).

**Mac with an Apple chip (M1–M4).** PDI 9.4 only ships the x86_64 graphics library for macOS, and
`spoon.sh` stops on arm64 with *"this Mac platform [arm64] is not yet supported"* (exit code 0, so check
the output). Spoon has to run under Rosetta 2 with an x64 Java. In step 3 the script offers to install
both (Rosetta 2, and Temurin 21 x64 into `~/pentaho/java-x64`) and leaves `spoon-rosetta.sh` next to
`spoon.sh`, which opens Spoon that way; "Done. Next" prints it:
```bash
bash /tmp/instalar-pentaho-mcp.sh --instalar-pdi      # step 3 asks "Install what is missing now? [Y/n]"
~/pentaho/data-integration/spoon-rosetta.sh
```
It only asks when it runs in a terminal. Without one (an agent, `curl | bash`) it installs nothing and says
so: rerun with `--rosetta` to install them without asking. By hand, the same thing is:
```bash
softwareupdate --install-rosetta --agree-to-license
mkdir -p ~/pentaho/java-x64
curl -fL https://api.adoptium.net/v3/binary/latest/21/ga/mac/x64/jdk/hotspot/normal/eclipse | tar -xz -C ~/pentaho/java-x64 --strip-components 1
PENTAHO_JAVA_HOME=~/pentaho/java-x64/Contents/Home arch -x86_64 /bin/sh ~/pentaho/data-integration/spoon.sh
```
An arm64 Java fails to load SWT (`incompatible architecture`). Double-clicking `Data Integration.app`
stops the same way; from Terminal it opens with
`open --arch x86_64 --env PENTAHO_JAVA_HOME=$HOME/pentaho/java-x64/Contents/Home ~/pentaho/data-integration/Data\ Integration.app`.

**Mac with an Intel chip.** Any 64-bit Java 11+, then `~/pentaho/data-integration/spoon.sh`.

**Linux.** PDI 9.4's SWT uses GTK 2: `sudo apt install libgtk2.0-0t64` (older releases: `libgtk2.0-0`).
At startup Spoon warns that `libwebkitgtk-1.0` is missing: click **Close**; only the built-in browser
(welcome page, help) is unavailable, and that package no longer exists in current Ubuntu.

**Releases up to 0.4.1:** the PDI zip lost its Unix permissions; `instalar.sh` gives them back to the
`.sh` scripts, `spoon.command` and the `.app`.
````

En «Troubleshooting», dos filas:

```markdown
| `spoon.sh` prints "this Mac platform [arm64] is not yet supported" | Mac with an Apple chip | Rosetta 2 + x64 Java + `arch -x86_64` (see "macOS and Linux") |
| Linux: Spoon won't open, GTK errors | PDI 9.4's SWT needs GTK 2 | `sudo apt install libgtk2.0-0t64` |
```

### README.md (en), tabla «Compatibility»

```markdown
| macOS | Tested on macOS 15 and 26 (GitHub Actions): Apple chip under Rosetta 2 with an x64 Java, and Intel. Install, Pan and every MCP call work; Spoon's window still needs a check on a real Mac. [Steps](INSTALL.md#macos-and-linux) |
| Linux | Tested on Ubuntu 24.04 and 26.04 (GitHub Actions); needs GTK 2 (`libgtk2.0-0t64`) |
```

### README.es.md, tabla «Compatibilidad»

```markdown
| macOS | Probado en macOS 15 y 26 (GitHub Actions): chip Apple bajo Rosetta 2 con un Java x64, e Intel. Instalación, Pan y todas las llamadas MCP andan; falta mirar la ventana de Spoon en un Mac de verdad. [Pasos](INSTALL.md#macos-and-linux) |
| Linux | Probado en Ubuntu 24.04 y 26.04 (GitHub Actions); necesita GTK 2 (`libgtk2.0-0t64`) |
```

### README.pt.md, tabla «Compatibilidade»

```markdown
| macOS | Testado no macOS 15 e 26 (GitHub Actions): chip Apple via Rosetta 2 com um Java x64, e Intel. Instalação, Pan e todas as chamadas MCP funcionam; falta conferir a janela do Spoon num Mac de verdade. [Passos](INSTALL.md#macos-and-linux) |
| Linux | Testado no Ubuntu 24.04 e 26.04 (GitHub Actions); precisa do GTK 2 (`libgtk2.0-0t64`) |
```

Mientras no se mire la ventana en un Mac de verdad (problema 5), conviene no anunciar macOS como
«probado» sin esa salvedad. En INSTALL.md, al final de la sección nueva, la misma nota en inglés:
*In GitHub's macOS VMs Spoon's main window opens blank (toolbar only) while the MCP keeps working; if
that happens on your Mac, please [open an issue](https://github.com/IvI2511/pentaho-mcp/issues).*
