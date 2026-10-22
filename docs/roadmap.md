# Hoja de ruta

Lo que viene después de la 0.4.0, en el orden en que se va a hacer. Cada fase es una versión.
Decidido con Iván el 20-oct-2026. **El seguimiento vive en Linear** (team Externos): projects
«Pentaho Live 0.4.1» (P-EXT-34) y «Pentaho Live 0.5.0» (P-EXT-35); las ideas de 0.6 y 0.7 están en
Triage (EXT-447 a EXT-454). Este archivo es el resumen; lo que hay que hacer está en Linear.

## 0.4.1 · Instalador — publicada el 20-oct-2026

Salió sin esperar Informes (decidido el 20-oct-2026), junto con el autor «Iván Castaño · IDC Studios».

- **Instalador de Windows** (Inno Setup 7, convención 04 del portafolio): completo (PDI 9.4 CE +
  plugin + Java Temurin 21) o solo el plugin en un PDI existente; accesos directos con el ícono, sin
  admin, en inglés/español/portugués; comando `pentaho-live` en el PATH para que un agente pueda
  abrir Spoon (con permiso del usuario). El zip sigue disponible para quien no quiera un `.exe` sin
  firmar.

## 0.4.2 · Arreglos del instalador

- Lo que Iván encontró con la 0.4.1: los accesos abren el splash del lanzador de IDC mientras carga
  Spoon, sin el aviso de seguridad de `Spoon.bat` (PDI de un zip bajado), y la carpeta de instalación
  ya no puede quedar dentro de un PDI (la 0.4.1 instalada ahí se muda sola). Diseño:
  `docs/lanzador-042-diseno.md`.

## 0.5.0 · Informes

- **Informes** — una pestaña propia en Spoon («Reports», junto a «Data Integration») que arma sola,
  sin IA, el informe de una transformación o un job: portada, resumen, diagrama (la imagen real del
  lienzo + Mermaid), linaje de campos, diccionario de datos, perfil de una muestra, métricas de la
  última ejecución (barras + Gantt), flujo de jobs, modelo de las tablas destino, validación,
  conexiones y parámetros. Vista previa adentro de Spoon y exportación a **HTML** (un archivo,
  offline, para presentar), **PDF**, **Markdown** (Obsidian/GitHub) y **PowerPoint**. Estilo neutro
  con el logo, los colores y los datos de quien lo usa (facultad, empresa), y una firma discreta
  «Pentaho Live · IDC Studios» con el símbolo de las celdas como marca de agua. Narrativa opcional
  escrita por Claude vía MCP. Diseño: `docs/informes-diseno.md`.

## 0.6.0 · Calidad y clases

- **Linter de ETL** — **hecho** (21-oct-2026, rama `feat/calidad`, sin publicar). Buenas prácticas más
  allá de `validate`: rutas fijas, contraseñas a la vista, Group by / Merge join sin ordenar, steps con
  nombre por defecto, la trampa del decimal con locale español… Con puntaje y cómo arreglar cada cosa.
  Quedó así: `lint_etl` (28 reglas encendidas, puntaje 0-100 y modo profundo que lee las fuentes con
  previews de solo lectura), `apply_lint_fixes` y `suppress_lint` (61 herramientas), el recurso
  `pentaho://lint/rules` y la sección «Calidad técnica» de los Informes. Salidas sin manejo de errores
  quedó como regla apagada (para el corrector y el modo clase) y la conversión perezosa se podó (sin daño
  demostrado en 9.4). Falta: clic en un hallazgo de la vista previa para seleccionar el step y un botón
  «Revisar calidad». Diseño: `docs/linter-diseno.md`; integración: `docs/linter-integracion.md`.
- **Corrector de ejercicios** — el docente deja la salida esperada (y opcionalmente reglas); el plugin
  corre la transformación del alumno, compara filas y arma la nota con lo que falló.
- **Modo clase** — **hecho** (22-oct-2026, rama `feat/modo-clase`, sin publicar). Claude construye despacio
  y deja una nota en el lienzo junto a cada step explicando qué hace y por qué. Quedó así: `lesson_start`
  (guion completo validado y planificado antes de tocar nada), `lesson_next`, `lesson_status` (con
  `wait_s`), `lesson_control` y `lesson_notes` (66 herramientas); la pestaña «Lección» en el panel de
  resultados con «Siguiente ▶», «◀ Atrás» (un Ctrl+Z real), pausa y vista previa; ritmos guiado, automático
  y manual; notas en bandas sin choques (en columnas de 3 o más steps, una nota compacta con el número; las
  notas lejanas se acercan); glosario de clase en/es/pt (`pentaho://glossary`); la guía para repartir
  como sección `lesson` de los Informes; el manual `08-modo-clase.md` y tres lecciones probadas (L1-L3,
  es/en/pt) más la de la demo. Desviación: Spoon 9.4 no llama al gancho `loadTab`, así que la pestaña
  vuelve con un vigilante mientras el panel está cerrado.
  Falta: jobs, un botón «Guía…» en la pestaña, reproducir una lección guardada sin IA y que el relayout
  justo después de una lección no mueva notas de más. Diseño: `docs/modo-clase-diseno.md`.

## 0.7.0 · Trabajo en equipo y datos

- **Diff visual** entre dos versiones de una transformación (agregado / borrado / cambiado, marcado en
  el lienzo).
- **Pruebas de regresión** — datos de entrada fijos y salida esperada; se corren todas con un botón o
  con Pan en CI.
- **Generador de datos «sucios»** para practicar limpieza (nulos, duplicados, fechas y decimales mal
  escritos).
- **Catálogo de una carpeta** — qué job llama a qué transformación, qué tablas lee y escribe cada una.
- **Patrones listos** — carga incremental por fecha, dimensión SCD tipo 2, deduplicación,
  CSV → staging.
