---
title: 'Hoja de mapeo · 04_hechos_ventas'
date: 2026-10-25
tags: [pentaho, etl, informe]
source: '../solucion-referencia/04_hechos_ventas.ktr'
generator: 'Pentaho Live 0.6.2'
---

# Hoja de mapeo · 04_hechos_ventas

**Transformación · 04_hechos_ventas**  
Informe de la transformación 04_hechos_ventas  
*Generado el 25 de octubre de 2026, 23:26*

## Hoja de mapeo origen-destino

*18 campos destino en 2 destinos (hechos_ventas, rechazos_ventas.csv).*  

| Origen | Destino | Tipo | Regla | Por defecto | Validación | Estado | Responsable | Notas |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| ventas_2026_09.csv.id_ticket | hechos_ventas.id_ticket | String | sin cambios |  | filas con error de «Eliminar duplicados» apartadas; filtro «¿Línea válida?» | Borrador |  |  |
| ventas_2026_09.csv.linea | hechos_ventas.linea | Integer | sin cambios |  | filas con error de «Eliminar duplicados» apartadas; filtro «¿Línea válida?» | Borrador |  |  |
| dim_fecha.sk_fecha | hechos_ventas.sk_fecha | Integer(9) | creado por Búsqueda en base de datos en «Buscar fecha» |  | filtro «¿Línea válida?» | Borrador |  |  |
| dim_tienda.sk_tienda | hechos_ventas.sk_tienda | Integer(9) | creado por Búsqueda en base de datos en «Buscar tienda» |  | filtro «¿Línea válida?» | Borrador |  |  |
| dim_producto.sk_producto | hechos_ventas.sk_producto | Integer(9) | creado por Búsqueda en base de datos en «Buscar producto» |  | filtro «¿Línea válida?» | Borrador |  |  |
| ventas_2026_09.csv.cantidad | hechos_ventas.cantidad | Integer | sin cambios |  | filas con error de «Eliminar duplicados» apartadas; filtro «¿Línea válida?» | Borrador |  |  |
| ventas_2026_09.csv.precio_unitario | hechos_ventas.precio_unitario | Number | a Number (\#.\#\#) en «Convertir precio a número» |  | filas con error de «Eliminar duplicados» apartadas; filtro «¿Línea válida?» | Borrador |  |  |
| ventas_2026_09.csv.precio_unitario; ventas_2026_09.csv.cantidad | hechos_ventas.importe | Number | a Number (\#.\#\#) en «Convertir precio a número» → ROUND_STD_2(MULTIPLY(precio_unitario, cantidad), 2) en «Calcular importe»; ROUND_STD_2(MULTIPLY(precio_unitario, cantidad), 2) en «Calcular importe» |  | filas con error de «Eliminar duplicados» apartadas; filtro «¿Línea válida?» | Borrador |  |  |
| ventas_2026_09.csv.metodo_pago | hechos_ventas.metodo_pago | String | sin cambios |  | filas con error de «Eliminar duplicados» apartadas; filtro «¿Línea válida?» | Borrador |  |  |
| ventas_2026_09.csv.id_ticket | rechazos_ventas.csv.id_ticket | String | sin cambios |  | filas con error de «Eliminar duplicados» apartadas; filtro «¿Línea válida?» | Borrador |  |  |
| ventas_2026_09.csv.linea | rechazos_ventas.csv.linea | Integer | sin cambios |  | filas con error de «Eliminar duplicados» apartadas; filtro «¿Línea válida?» | Borrador |  |  |
| ventas_2026_09.csv.fecha | rechazos_ventas.csv.fecha | String | sin cambios |  | filas con error de «Eliminar duplicados» apartadas; filtro «¿Línea válida?» | Borrador |  |  |
| ventas_2026_09.csv.id_tienda | rechazos_ventas.csv.id_tienda | String | sin cambios |  | filas con error de «Eliminar duplicados» apartadas; filtro «¿Línea válida?» | Borrador |  |  |
| ventas_2026_09.csv.codigo_producto | rechazos_ventas.csv.codigo_producto | String | sin cambios |  | filas con error de «Eliminar duplicados» apartadas; filtro «¿Línea válida?» | Borrador |  |  |
| ventas_2026_09.csv.cantidad | rechazos_ventas.csv.cantidad | Integer | sin cambios |  | filas con error de «Eliminar duplicados» apartadas; filtro «¿Línea válida?» | Borrador |  |  |
| ventas_2026_09.csv.precio_unitario | rechazos_ventas.csv.precio_unitario | Number | a Number (\#.\#\#) en «Convertir precio a número» |  | filas con error de «Eliminar duplicados» apartadas; filtro «¿Línea válida?» | Borrador |  |  |
| ventas_2026_09.csv.metodo_pago | rechazos_ventas.csv.metodo_pago | String | sin cambios |  | filas con error de «Eliminar duplicados» apartadas; filtro «¿Línea válida?» | Borrador |  |  |
|  | rechazos_ventas.csv.motivo_rechazo | String | constante «DUPLICADO» en «Marcar duplicado»; creado por Janino en «Clasificar línea» |  | filtro «¿Línea válida?» | Borrador |  | Se crea en «Marcar duplicado». Se crea en «Clasificar línea». |

*Origen y destino como recurso.campo. Estado: Borrador / Revisado / Aprobado.*  

---

*Generado con Pentaho Live · IDC Studios*
