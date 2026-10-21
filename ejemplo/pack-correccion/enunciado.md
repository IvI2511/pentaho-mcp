# Grano de Oro · Ventas por tienda y categoría

**Herramienta:** Pentaho Data Integration 9.4 (Spoon) · **Entrega:** un solo fichero `.ktr` ·
**Corrección:** automática, con el corrector de ejercicios de Pentaho Live

## Contexto

**Grano de Oro** es una cadena de cuatro cafeterías de Madrid (Malasaña, Chamberí, Salamanca y
Retiro). La dirección quiere una tabla con lo que vendió cada tienda en cada categoría de producto en
septiembre de 2026, calculada directamente desde los ficheros que llegan cada mes, sin pasar por una
hoja de cálculo.

## Datos

Los mismos ficheros de la práctica, en la carpeta `datos/visible/` de este ejercicio:

| Fichero | Formato | Contenido |
|---|---|---|
| `ventas_2026_09.csv` | CSV en UTF-8, separador `;`, con cabecera | líneas de ticket |
| `productos.xlsx` | Excel 2007+, hoja `Productos` | catálogo de productos |
| `tiendas.json` | JSON anidado (`$.tiendas[*]`) | las tiendas |

Columnas de las ventas: `id_ticket`, `linea`, `fecha`, `id_tienda`, `codigo_producto`, `cantidad`,
`precio_unitario`, `metodo_pago`. Una línea de ticket se identifica por el par `(id_ticket, linea)`.

## Qué se pide

Una transformación que lea los tres ficheros y escriba, con un **Text file output**, un CSV con una
fila por cada combinación de tienda y categoría:

| Columna | Tipo | Qué es |
|---|---|---|
| `tienda` | texto | el nombre de la tienda (`Malasaña`, `Chamberí`…), sacado de `tiendas.json` |
| `categoria` | texto | la categoría del producto como en el catálogo: `Bebidas`, `Bollería`, `Café`, `Infusiones` o `Salado` |
| `unidades` | entero | la suma de `cantidad` |
| `importe` | número | la suma de `precio_unitario × cantidad`, con 2 decimales y **punto decimal** |

Condiciones:

1. Una línea de ticket cuenta **una sola vez**, aunque el fichero la repita (mismo `id_ticket` y
   `linea`).
2. **No cuentan** las líneas sin tienda, las de cantidad cero o negativa ni las de un producto que no
   está en el catálogo.
3. El precio llega con **punto o con coma** decimal (`2.50` o `2,50`): los dos valen lo mismo.
4. En el Excel, las categorías están escritas de varias maneras (`café`, `CAFÉ`, ` salado `): en el
   resultado van como en la tabla de arriba.
5. Las rutas de los ficheros, **relativas a la transformación** (`${Internal.Entry.Current.Directory}`),
   no a una carpeta de tu ordenador.

## Cómo se corrige

- Tu transformación se ejecuta con estos datos y **con otro juego de datos que no se publica** (un
  caso oculto con otros tickets). Una salida copiada a mano para estos datos no pasa el oculto.
- El corrector lee el CSV que escribe tu Text file output y lo compara, fila a fila, con el resultado
  esperado: no importa el orden de las filas ni el de las columnas, y un importe vale si no se aleja
  más de un céntimo.
- Corre en un proceso aparte y con una política de steps: los de código (JavaScript, Java) y los que
  usan la red o una base de datos no se ejecutan en este ejercicio.

| Criterio | Puntos |
|---|---:|
| Corre sin errores | 1 |
| Columnas correctas | 1 |
| Cantidad de filas | 1 |
| Valores correctos | 4 |
| Agrupa con un Group by (cuenta solo si corre) | 1 |
| Quita los duplicados con Unique rows (cuenta solo si corre) | 1 |
| Sin JavaScript ni Java | 0,5 |
| Rutas relativas a la transformación (ninguna ruta absoluta) | 0,5 |
| **Total** | **10** |

La devolución dice qué criterios fallaron, con ejemplos de las filas que no coinciden (solo del caso
visible) y una pista para cada error típico.
