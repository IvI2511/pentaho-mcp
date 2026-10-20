# Práctica de ETL · Almacén de datos de ventas de «Grano de Oro»

**Herramienta:** Pentaho Data Integration 9.4 (Spoon, Pan y Kitchen) · **Destino:** SQLite ·
**Trabajo:** individual · **Datos:** carpeta `datos/` (ficticios)

## 1. Contexto

**Grano de Oro** es una cadena de cuatro cafeterías de especialidad en Madrid: **Malasaña**,
**Chamberí**, **Salamanca** y **Retiro**. Cada tienda tiene su propio terminal de punto de venta (TPV)
y hasta ahora nadie ha cruzado los datos de las cuatro: la dirección quiere saber cuánto vende cada
tienda, qué categorías de producto tiran más y cuál es el ticket medio, y quiere poder repetir el
cálculo cada mes sin hacerlo a mano en una hoja de cálculo.

Los datos llegan de tres sitios distintos y en tres formatos:

- las **ventas** salen de los TPV en un **CSV**; hay dos modelos de TPV y cada uno exporta a su
  manera, y además el fichero se ha reenviado en parte, así que hay líneas repetidas;
- el **catálogo de productos** lo mantiene el departamento de compras en un **Excel** escrito a mano;
- los datos de las **tiendas** salen de la web de la cadena, en **JSON**.

Para que la práctica sea manejable, la dirección ha extraído una muestra aleatoria de tickets de
**septiembre de 2026** (aproximadamente uno de cada diez).

## 2. Datos de partida

| Fichero | Formato | Contenido |
|---|---|---|
| `datos/ventas_2026_09.csv` | CSV en UTF-8, separador `;`, con cabecera | 3000 líneas de ticket |
| `datos/productos.xlsx` | Excel 2007+, hoja `Productos` | catálogo de 28 productos |
| `datos/tiendas.json` | JSON anidado | las 4 tiendas |

Columnas de las ventas: `id_ticket`, `linea`, `fecha`, `id_tienda`, `codigo_producto`, `cantidad`,
`precio_unitario`, `metodo_pago`. Una línea de ticket se identifica por el par `(id_ticket, linea)`.

Problemas conocidos de los datos (no se dice cuántos hay de cada uno: encontrarlos es parte del
trabajo):

- **Fechas en dos formatos**: `2026-09-14` y `14/09/2026`.
- **Precios con coma decimal** en algunas líneas: `2,50` en lugar de `2.50`.
- **Método de pago escrito de varias maneras**: `TARJETA`, `tarjeta`, `Tarjeta `, ` tarjeta`…
- **Líneas duplicadas**: copias exactas de otras líneas del mismo fichero.
- **Cantidades negativas o cero**.
- **Códigos de producto que no existen** en el catálogo.
- **Líneas sin tienda** (`id_tienda` vacío).
- En el Excel, **nombres con espacios sobrantes** y **categorías escritas de varias maneras**
  (`café`, `CAFÉ`, ` Café`…).

La descripción completa de cada fichero está en `datos/LEEME.md`.

## 3. Qué se pide

### 3.1 Modelo estrella

Un almacén SQLite en `salida/grano_de_oro_dw.sqlite` con un modelo en estrella cuyo **grano es una
línea de ticket**:

| Tabla | Columnas | Notas |
|---|---|---|
| `dim_tienda` | `sk_tienda`, `id_tienda`, `nombre`, `barrio`, `distrito`, `codigo_postal`, `fecha_apertura`, `superficie_m2`, `plazas` | clave natural `id_tienda` |
| `dim_producto` | `sk_producto`, `codigo_producto`, `nombre`, `categoria`, `precio_venta` | clave natural `codigo_producto` |
| `dim_fecha` | `sk_fecha`, `fecha`, `anio`, `mes`, `dia`, `dia_semana`, `nombre_dia`, `es_fin_de_semana` | los 30 días de septiembre de 2026 |
| `hechos_ventas` | `id_ticket`, `linea`, `sk_fecha`, `sk_tienda`, `sk_producto`, `cantidad`, `precio_unitario`, `importe`, `metodo_pago` | clave primaria `(id_ticket, linea)` |

- `sk_tienda` y `sk_producto` son **claves subrogadas**: enteros consecutivos que empiezan en 1 y
  que asigna la ETL (el orden concreto no se evalúa).
- `sk_fecha` es una clave «inteligente» con el formato `aaaammdd` (`20260914`). `dim_fecha` no sale
  de ningún fichero: hay que generarla. `dia_semana` va de 1 (lunes) a 7 (domingo), `nombre_dia` en
  minúsculas (`lunes`…) y `es_fin_de_semana` vale `S` o `N`.
- SQLite no tiene tipo fecha: `fecha` y `fecha_apertura` se guardan como texto `aaaa-MM-dd`.
- La carga es **completa** y tiene que poder repetirse: ejecutarla dos veces deja el mismo resultado.

### 3.2 Limpieza y estandarización

- Todas las fechas pasan a `aaaa-MM-dd`.
- Los precios se leen como número con **punto decimal**, venga escrito con punto o con coma. El
  resultado no puede depender del idioma del equipo en el que se ejecute la ETL.
- `metodo_pago` queda en mayúsculas y sin espacios: solo `TARJETA`, `EFECTIVO` o `BIZUM`.
- `id_tienda` y `codigo_producto` sin espacios y en mayúsculas.
- En el catálogo: nombres sin espacios sobrantes y una sola forma de escribir cada categoría, con
  inicial mayúscula (`Bebidas`, `Bollería`, `Café`, `Infusiones`, `Salado`).

### 3.3 Deduplicación

Si un mismo `(id_ticket, linea)` aparece más de una vez, se carga una sola vez y las copias se
rechazan con el motivo `DUPLICADO`.

### 3.4 Validación y rechazos

Cada línea que no se pueda cargar va a **`salida/rechazos_ventas.csv`** (separador `;`, con cabecera)
con las columnas de la venta ya normalizadas más una columna **`motivo_rechazo`**. Si una línea
incumple varias reglas, cuenta la **primera** de esta lista:

| Orden | Motivo | Regla |
|---:|---|---|
| 1 | `DUPLICADO` | segunda copia y siguientes de un mismo `(id_ticket, linea)` |
| 2 | `TIENDA_VACIA` | `id_tienda` vacío |
| 3 | `CANTIDAD_NO_VALIDA` | `cantidad` menor o igual que 0 |
| 4 | `TIENDA_DESCONOCIDA` | `id_tienda` que no está en `dim_tienda` |
| 5 | `PRODUCTO_DESCONOCIDO` | `codigo_producto` que no está en `dim_producto` |
| 6 | `FECHA_NO_VALIDA` | fecha que no está en `dim_fecha` |

Todas las líneas del fichero tienen que acabar en un sitio u otro: **hechos + rechazos = líneas del
CSV**.

### 3.5 Hechos y búsqueda de claves

- Las claves subrogadas de cada línea se obtienen **buscando en las dimensiones ya cargadas**
  (`id_tienda` → `sk_tienda`, `codigo_producto` → `sk_producto`, `fecha` → `sk_fecha`).
- `importe = precio_unitario × cantidad`, redondeado a 2 decimales.

### 3.6 Indicadores (KPI)

Calculados a partir del modelo estrella (no de los ficheros de entrada), con `tienda` = `nombre` de
`dim_tienda`:

| Tabla del almacén y CSV en `salida/` | Columnas |
|---|---|
| `kpi_ventas_tienda_categoria` | `tienda`, `categoria`, `unidades`, `importe` |
| `kpi_ticket_medio_tienda` | `tienda`, `importe_total`, `num_tickets`, `ticket_medio` |

`num_tickets` cuenta los **tickets distintos** con al menos una línea válida y
`ticket_medio = importe_total / num_tickets`. Importes con 2 decimales (redondeo con la mitad hacia
arriba) y, en los CSV, punto decimal y separador `;`.

### 3.7 Orquestación

Un **job** que lo ejecute todo en orden: comprobar que existen los ficheros de entrada, crear el
modelo, cargar **primero las dimensiones y después los hechos** (que necesitan sus claves) y, al
final, los KPI. Si cualquier paso falla, el job debe dejar un mensaje claro en el log y **terminar
con error** (código de salida distinto de 0 en Kitchen), no seguir como si nada.

### 3.8 Documentación

Un informe de la ETL que permita entenderla sin abrir Spoon: diagrama de cada transformación y del
job, qué hace cada paso, campos de salida, conexiones y parámetros, y los recuentos de la última
ejecución. Puede generarse automáticamente (por ejemplo, con la pestaña **Informes** de Pentaho Live)
o redactarse a mano.

## 4. Requisitos técnicos

- PDI 9.4. El driver de SQLite ya viene incluido; no hace falta instalar ninguna base de datos.
- Rutas **relativas** a la carpeta de cada fichero, con `${Internal.Entry.Current.Directory}`: la
  carpeta entera tiene que funcionar en otro ordenador sin tocar nada.
- Nombres de paso descriptivos (nada de «Table output 2»).
- Debe ejecutarse sin Spoon, con `Kitchen` sobre el job, y terminar con código de salida 0.

## 5. Entregables

1. Las transformaciones (`.ktr`) y el job (`.kjb`).
2. El informe de la ETL (apartado 3.8), en PDF, HTML o Markdown.
3. Una tabla con los recuentos obtenidos: filas de cada dimensión, filas de hechos, rechazos por
   motivo y los dos KPI. Se pueden comprobar con `python comprobar_resultados.py`, que los compara con
   el resultado esperado de `datos/LEEME.md`.

## 6. Rúbrica (10 puntos)

| Criterio | Puntos | Para la nota máxima |
|---|---:|---|
| Lectura de las fuentes | 0,5 | Lee el CSV, el Excel (`.xlsx`) y el JSON anidado con los tipos correctos. |
| Limpieza y estandarización | 1,5 | Fechas, precios, método de pago, códigos y categorías normalizados; los precios no dependen del idioma del equipo. |
| Deduplicación | 1 | Una sola fila por `(id_ticket, linea)`; las copias van a rechazos como `DUPLICADO`. |
| Validación y rechazos | 1,5 | Reglas en el orden pedido; fichero de rechazos con motivo; los recuentos por motivo cuadran con el resultado esperado. |
| Dimensiones y claves subrogadas | 1,5 | Tres dimensiones correctas, `dim_fecha` generada, claves subrogadas asignadas por la ETL, carga repetible. |
| Hechos | 1,5 | Claves obtenidas con búsquedas en las dimensiones, importe correcto, `hechos + rechazos = líneas del CSV`. |
| KPI | 1 | Los dos indicadores, en tabla y en CSV, cuadran al céntimo con el resultado esperado. |
| Job | 1 | Dimensiones antes que hechos; camino de éxito y de error; termina con error si algo falla. |
| Documentación | 0,5 | El informe permite entender la ETL sin abrir Spoon. |

Se resta hasta 1 punto por rutas absolutas, pasos con nombre por defecto o una ETL que solo
funciona en el ordenador de quien la hizo.

## 7. Pistas

- *Group by* y *Unique rows* solo agrupan filas **consecutivas**: ordena antes por las mismas claves.
- Un campo numérico leído de texto sin símbolo decimal explícito usa el del idioma del equipo; en
  español, `2.50` se lee como 250 sin dar ningún error.
- Un paso con dos salidas reparte las filas entre ellas por defecto; si las dos deben recibirlas
  todas, hay que elegir «copiar».
- Antes de dar la práctica por buena, comprueba que **hechos + rechazos = 3000**.
