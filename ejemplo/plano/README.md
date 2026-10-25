# El plano ETL de Grano de Oro

> **In English.** The design of the Grano de Oro load written *before* building it, as Pentaho Live 0.7
> does it: the plan file, its design document with the three diagrams, and the source-to-target mapping
> sheet exported from the finished `04_hechos_ventas.ktr`. Everything in this folder is in Spanish.

| Archivo | Qué es |
|---|---|
| `grano-de-oro.plan.yaml` | El plano: objetivo, grano, fuentes, destinos, reglas, 29 etapas en 5 transformaciones, el job, el entorno, riesgos y decisiones. Lo escribe `plan_create` y lo valida el esquema `docs/plano.schema.json` |
| `grano-de-oro.html`, `grano-de-oro.md` | El documento del plano (`plan_get formats=["html","md"]`): las tres vistas, las tablas y la revisión |
| `grano-de-oro_imagenes/` | Los tres diagramas en PNG y SVG: flujo de datos, actividad UML del job, componentes y despliegue |
| `04_hechos_ventas_mapeo.xlsx`, `04_hechos_ventas_mapeo.md` | La hoja de mapeo origen-destino de la transformación de hechos (`mapping_export`), una fila por campo destino |

Cómo se usa con Claude y Spoon abierto:

1. *«Antes de construir, hagamos el plano de la carga de Grano de Oro»*: Claude te entrevista y escribe el
   plano (`plan_create`), lo revisa (`plan_review`) y te pregunta lo que solo tú sabes.
2. *«Enséñame el plano»*: la perspectiva **Diseño** de Spoon (o el menú IDC › Diseño…) lo dibuja.
3. *«Construye el boceto de 04_hechos_ventas»*: `plan_build` deja un marcador por etapa con su nota
   PENDIENTE; Claude los rellena uno a uno con `fill_placeholder`.
4. *«¿Lo construido coincide con el plano?»*: `plan_check` compara el plano con
   `../solucion-referencia/` (la carpeta de `orchestration.folder`): las cinco transformaciones y el job están,
   y solo avisa de los steps auxiliares que el plano no nombra (ordenar, preparar el rechazo…).

La revisión del plano deja a propósito dos avisos: `productos.xlsx` y `tiendas.json` no tienen plan de
rechazos (¿qué pasa con una fila mala del catálogo?). Es la conversación que el plano sirve para tener.
