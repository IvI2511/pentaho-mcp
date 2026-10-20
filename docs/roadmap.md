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

- **Linter de ETL** — buenas prácticas más allá de `validate`: rutas fijas, contraseñas a la vista,
  Group by / Merge join sin ordenar, salidas sin manejo de errores, steps con nombre por defecto, la
  trampa del decimal con locale español, conversión perezosa… Con puntaje y cómo arreglar cada cosa.
- **Corrector de ejercicios** — el docente deja la salida esperada (y opcionalmente reglas); el plugin
  corre la transformación del alumno, compara filas y arma la nota con lo que falló.
- **Modo clase** — Claude construye despacio y deja una nota en el lienzo junto a cada step
  explicando qué hace y por qué.

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
