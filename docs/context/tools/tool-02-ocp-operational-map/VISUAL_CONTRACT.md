# Contrato visual — Mapa operativo OCP

Fecha de referencia: 2026-09-10

## Baseline visual desplegado

- Mockup autónomo y reproducible:
  [`mockups/control-ocp-impacto.html`](mockups/control-ocp-impacto.html)
- Fuente editable:
  [`mockups/control-ocp-impacto.source.html`](mockups/control-ocp-impacto.source.html)

El HTML autónomo documenta el baseline visual implementado en `0.2.0`. La
fuente editable contiene HTML, CSS, JavaScript y el seed inicial del prototipo.

La evolución descrita a continuación modifica de manera explícita el baseline.
Mientras el nuevo mockup se encuentra en revisión, este documento prevalece en
los puntos donde el mockup `control-ocp-impacto` muestre directamente todos los
microservicios o no incluya `/admin`.

## Evolución visual acordada

### Vista operativa compacta

- No muestra la etiqueta `BETA`.
- No muestra botones, enlaces ni navegación hacia `/admin`.
- El usuario que necesite administrar debe agregar manualmente `/admin` a la
  Route de la aplicación.
- La tarjeta inicial del caso muestra código, nombre, metadatos resumidos y
  estado agregado.
- No lista directamente los microservicios del caso.
- Cada caso puede abrirse y contiene dos agrupadores independientes:
  `Elementos del caso` y `Microservicios del caso`.
- Ambos agrupadores comienzan cerrados.
- Todos los casos comienzan plegados, incluso el primero.
- La pestaña `Microservicios` muestra el inventario completo y reutiliza los
  filtros existentes sin sustituir las vistas previas.
- Un caso con diagrama muestra `Ver flujo`; uno con anotación muestra
  `Anotación`. Son acciones de lectura y no revelan acceso administrativo.
- Los microservicios conservan nombre, namespace, réplicas, estado, marca
  `En pruebas` y enlace a la consola cuando se abre su agrupador.

### Administración `/admin`

- Reutiliza la identidad visual, cabecera y Route de OCP Tools.
- Se accede escribiendo `/admin` directamente; no se anuncia desde la vista
  operativa.
- Secciones: `Casos de prueba`, `Elementos / plataformas` y `Archivados`.
- Casos: crear, clonar, editar código/nombre/detalle/anotación/metadatos,
  ordenar, asociar elementos, asociar Deployments, editar el flujo y archivar.
- Elementos: catálogo global sin categoría, reutilizable y archivable.
- Deployments: únicamente inventario descubierto por OCP; no existe alta manual.
- La selección de un Deployment inactivo muestra una advertencia antes de
  guardar la relación.
- Archivados: lista separada con opción de restaurar.
- Cada fila o formulario muestra la fecha de última actualización.
- Guardar no solicita responsable ni comentario en esta demo.
- Al editar un caso, tanto `Elementos del caso` como `Microservicios del caso`
  separan y muestran primero `Seleccionados (N)` y después `Disponibles (N)`.
- La búsqueda de microservicios y el filtro por namespace se mantienen; los
  contadores corresponden al subconjunto visible y las asociaciones fuera del
  filtro no se pierden.
- La búsqueda de elementos ofrece la misma separación visual entre
  `Seleccionados` y `Disponibles`.
- En un caso nuevo o clonado, el código muestra el último código insertado; el
  usuario lo modifica manualmente antes de guardar.
- El editor de flujo es una página completa. Sugiere elementos asociados y
  también permite nodos libres. El visor público es una página completa,
  autoanima el flujo y no ofrece edición ni acceso a `/admin`.
- No se ofrece exportación de GIF o imagen.

## Alcance que debe conservarse

- Navegación entre `Casos de prueba`, `Microservicios`, `Sin flujo` e
  `Impacto al apagar`.
- Buscadores separados por nombre del caso, elemento y microservicio.
- Búsqueda tolerante a mayúsculas, tildes y errores menores.
- Listado de escenarios y panel de detalle.
- Microservicios largos organizados por namespace dentro de su agrupador.
- Réplicas y estado como información.
- Marca compartida `En pruebas`.
- Estado explícito `Encendido`, `Apagado` o `Desconocido`.
- Cantidad y detalle de casos potencialmente afectados.
- Enlace por Deployment a la consola OpenShift, abierto en otra pestaña.
- Ranking inverso por cantidad de casos impactados.
- Vista de Deployments todavía no asociados a un flujo.
- Estados vacíos y mensajes de actualización/error controlados.
- Jerarquía, distribución, espaciado, colores, tipografía, responsive e
  interacciones actuales.

La implementación `0.4.0` desplegada conecta la vista compacta y `/admin` con el
catálogo PostgreSQL. La evolución `0.5.0` se monta sobre esa estructura y no debe
introducir una regresión del baseline visual.

## Integración con la plataforma

La cabecera y navegación global de OCP Tools podrán envolver el módulo. Dentro
de la Herramienta 02 deben conservarse la identidad `Mapa operativo OCP`, las
cuatro subpestañas y la composición visual del prototipo.

La Herramienta 02 pertenece a `Visibilidad operativa`. La Herramienta 01 aparece
separada bajo `Utilidades operativas`.

La ruta `/admin` no agrega otro producto ni otra navegación global: es una vista
de gestión del mismo módulo dentro de OCP Tools.

## Reglas de seguridad visual

- No mostrar botones para encender, apagar, escalar o modificar réplicas.
- El checkbox `En pruebas` solo modifica la marca compartida en PostgreSQL.
- El estado OCP es informativo.
- Los enlaces utilizan:
  `target="_blank"` y `rel="noopener noreferrer"`.

Formato de enlace:

```text
https://console-openshift-console.apps.ocpnprod7.gp.inet/
  k8s/ns/{namespace}/deployments/{deployment}
```

La base efectiva se externaliza en configuración.

## Datos del prototipo

Para la primera beta, los seis casos y las relaciones embebidas se usarán como
seed inicial. Los estados de pods y marcas que aparecen en el HTML son ejemplos
visuales y deberán ser sustituidos por datos persistidos/reales.

## Dependencias externas del mockup

El HTML autónomo contiene recursos externos para poder reproducir el prototipo.
La aplicación desplegada no tiene Internet y no debe copiar esa dependencia.
Los iconos y recursos necesarios se servirán localmente o se reemplazarán por
alternativas embebidas.

## Estados adicionales requeridos al implementar

Sin alterar el diseño, deben contemplarse:

- carga inicial;
- datos persistidos con sensado en curso;
- namespace nunca consultado;
- estado desactualizado con tiempo relativo;
- error de un namespace sin ocultar los demás;
- marca `En pruebas` vencida;
- ausencia de coincidencias;
- Deployment inactivo o desaparecido.
- caso o elemento archivado y restaurado;
- advertencia al asociar Deployment inactivo;
- error de guardado administrativo sin perder los datos editados;
- confirmación de guardado con fecha de última actualización;
- catálogo vacío de elementos o ausencia de Deployments elegibles.
