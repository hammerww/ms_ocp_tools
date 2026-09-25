# Herramienta 02 — Mapa operativo OCP

Fecha de vigencia: 2026-09-21  
Estado: `0.6.0` desplegada y operativa en `testing-pmx3`

## Objetivo

Dar visibilidad al equipo de Certificación sobre la relación entre casos de
prueba y Deployments de OpenShift, el estado operativo observado de esos
Deployments y el impacto potencial de apagarlos.

La herramienta no inicia, detiene, escala ni modifica recursos de OCP. Toda
acción sobre réplicas se realiza exclusivamente en la consola de OpenShift y
queda sujeta a los permisos del usuario autenticado allí.

## Ubicación en OCP Tools

Familia: `Visibilidad operativa`  
Nombre: `Mapa operativo OCP`

La Herramienta 01 permanecerá en la familia `Utilidades operativas`.

La Herramienta 02 conserva sus vistas internas y agrega el inventario completo:

1. `Casos de prueba`;
2. `Microservicios`;
3. `Sin flujo`;
4. `Impacto al apagar`.

El contrato visual vinculante se encuentra en
[`VISUAL_CONTRACT.md`](VISUAL_CONTRACT.md).

## Vista Casos de prueba

Permite:

- buscar por nombre del caso;
- buscar por elemento funcional o microservicio;
- revisar inicialmente una tarjeta compacta por caso, sin listar directamente
  todos sus microservicios;
- expandir un caso bajo demanda;
- abrir por separado `Elementos del caso` y `Microservicios del caso`;
- consultar sus Deployments asociados únicamente después de abrir el agrupador
  correspondiente;
- mostrar namespace, réplicas listas/deseadas, estado y marca `En pruebas`;
- abrir el Deployment en la consola de OpenShift.

La vista inicial prioriza localizar un caso. Ambos agrupadores comienzan
cerrados para evitar que un inventario grande desplace visualmente los casos
siguientes.

## Vista Microservicios

Muestra el inventario completo de Deployments de todos los namespaces visibles,
incluidos los asociados a casos y los que todavía no tienen relación. Reutiliza
los filtros por caso, elemento, nombre de Deployment, namespace, estado y marca
`En testing`, e informa cuántos resultados se muestran respecto del total.

## Vista Sin flujo

Muestra todos los Deployments descubiertos en los namespaces configurados que
no estén relacionados con ningún caso activo.

Reglas cerradas:

- un Deployment nuevo aparece automáticamente en esta vista;
- el inventario incluye todos los Deployments de cada namespace autorizado;
- no se filtran inicialmente por prefijo o convención de nombre;
- un Deployment que deja de aparecer se conserva como inactivo para mantener
  relaciones e historial;
- los inactivos no participan en rankings activos.
- un Deployment asociado únicamente a casos archivados vuelve a considerarse
  `Sin flujo`, porque no pertenece a ningún caso operativo activo.

## Vista Impacto al apagar

Presenta el análisis inverso:

- un registro por Deployment relacionado;
- orden descendente por cantidad de casos afectados;
- detalle de los casos dependientes;
- estado `Encendido`, `Apagado` o `Desconocido`;
- marca compartida `En pruebas`;
- enlace a la consola OCP.

La vista es informativa. La frase “Impacto al apagar” describe una simulación
de dependencias, no una acción ejecutable desde OCP Tools.

## Fuente inicial de casos y relaciones

Los seis casos y Deployments embebidos en el mockup se consideran datos
iniciales reales para la beta. Se actualizarán posteriormente con información
operativa más completa.

La primera entrega utilizó carga manual y seed versionado. La siguiente
evolución sustituirá ese mantenimiento operativo por la UI `/admin`, conservando
PostgreSQL como fuente persistente y las relaciones por namespace + nombre de
Deployment.

Los IDs `CP-001` a `CP-006`, títulos, elementos, metadatos y relaciones del
mockup constituyen el seed inicial.

## Administración web `/admin`

`/admin` se sirve desde la misma aplicación y la misma Route. En esta demo será
una pantalla abierta, sin autenticación adicional. Esta decisión se acepta por
el alcance interno en red privada/Citrix y no constituye un patrón para
producción.

La vista operativa no mostrará botones, enlaces ni navegación hacia `/admin`,
ni mostrará la etiqueta `BETA`. Quien necesite editar deberá agregar manualmente
`/admin` a la URL de la Route.

La administración permite:

- crear, editar y clonar casos de prueba;
- cambiar código, nombre, descripción, anotación extensa, metadatos y orden del caso;
- archivar y restaurar casos, sin eliminación física;
- consultar una vista específica de archivados;
- administrar un catálogo global y reutilizable de elementos o plataformas,
  por ejemplo CRM, BUS, DITO y Aplicación Móvil;
- asociar y desasociar elementos globales a uno o varios casos;
- asociar y desasociar Deployments descubiertos a uno o varios casos;
- asociar un Deployment inactivo únicamente después de mostrar una advertencia;
- buscar elementos y Deployments sin perder la sección de seleccionados;
- editar un diagrama de flujo persistente por caso;
- mostrar la fecha de la última actualización de cada registro.

Al crear, editar o clonar un caso, los elementos y Deployments asociados se
muestran primero en un grupo `Seleccionados (N)` y el resto en
`Disponibles (N)`. La búsqueda de elementos, la búsqueda de Deployments y el
filtro por namespace se conservan sin ocultar asociaciones ya seleccionadas.

El código de un caso nuevo o clonado se precarga con el código del último caso
insertado en PostgreSQL. No se calcula el siguiente código: el usuario lo cambia
manualmente y continúa aplicándose la restricción única existente.

La clonación copia elementos, Deployments, metadatos, anotación y diagrama dentro
de una transacción; el usuario puede editar los campos antes de confirmar. El
nuevo caso queda activo.

### Anotaciones y diagramas de flujo — evolución `0.5.0`

- La anotación es texto extenso, editable desde `/admin` y visible en modo
  lectura desde la vista normal.
- Todos los casos permanecen plegados inicialmente en la vista normal.
- `Ver flujo` abre `/flow?caseId={id}` como página completa; la animación se
  inicia automáticamente y es estrictamente informativa.
- El editor vive en `/admin/flow?caseId={id}` y puede sugerir elementos asociados
  al caso o crear nodos libres no vinculados al catálogo.
- Las conexiones admiten pasos automáticos, manuales y retornos/reintentos. Cada
  retorno exige entre 1 y 10 repeticiones; no existen ciclos infinitos.
- La exportación de GIF o imagen está fuera de esta entrega.
- El favicon utiliza el logo entregado por el usuario.

No se agregará una categoría a los elementos. Tampoco se podrá registrar
manualmente un Deployment inexistente: el catálogo elegible procede
exclusivamente del inventario descubierto por el sensor OCP.

Reglas de archivado:

- archivar no borra relaciones ni datos;
- los casos archivados desaparecen de las vistas operativas normales;
- la vista `Archivados` permite revisar y restaurar;
- un Deployment relacionado solo con casos archivados aparece en `Sin flujo`;
- un elemento archivado se conserva para integridad de relaciones existentes y
  no se ofrece para nuevas asociaciones hasta que sea restaurado.

Los cambios se guardan sin exigir responsable ni comentario. Se persistirán
como mínimo `created_at`, `updated_at` y, cuando corresponda, `archived_at`. La
primera evolución no requiere historial completo de cada edición.

Aunque la gestión sea exclusivamente web, el navegador necesitará endpoints
HTTP modificatorios internos y de mismo origen. No se documentarán como API de
integración ni se considerarán una barrera de seguridad mientras `/admin`
permanezca abierto.

## Inventario OCP

Namespaces configurados para la evolución `0.5.0`:

```text
agendador-test
diagnosticador-test
pmx-test
security-test
testing
testing-diversificacion
testing-matrix
testing-pmx1
testing-pmx2
testing-pmx3
testing-pmx4
```

`testing-matrix` fue confirmado durante el dry-run real; el valor anterior
`matrix` era incorrecto y no debe reutilizarse.

`listanegra-test` se retiró de configuración y RBAC porque el usuario no puede
crear ni consultar RoleBindings allí. Podrá reincorporarse cuando exista el
permiso explícito correspondiente.

La lista es parametrizable y podrá crecer. Agregar un namespace requiere:

1. agregarlo a configuración;
2. conceder al ServiceAccount lectura en ese namespace;
3. validar una consulta exitosa;
4. iniciar su participación en el ciclo de sensado.

### Configuración de namespaces y visibilidad

Las variables implementadas en `0.3.0`:

```text
OCP_MAP_NAMESPACES=agendador-test,diagnosticador-test,pmx-test,security-test,testing,testing-diversificacion,testing-matrix,testing-pmx1,testing-pmx2,testing-pmx3,testing-pmx4
OCP_MAP_VISIBLE_NAMESPACES=agendador-test,diagnosticador-test,pmx-test,security-test,testing,testing-diversificacion,testing-matrix,testing-pmx1,testing-pmx2,testing-pmx3,testing-pmx4
```

`OCP_MAP_NAMESPACES` controla qué namespaces recorre el sensor.
`OCP_MAP_VISIBLE_NAMESPACES` controla el subconjunto que participa en la vista
operativa y la API pública. `/admin` conserva acceso a todo el inventario
sensado y muestra si cada Deployment pertenece a un namespace visible.

La forma general es:

```text
OCP_MAP_NAMESPACES=<todos los namespaces que se sensan>
OCP_MAP_VISIBLE_NAMESPACES=<subconjunto mostrado en UI y API pública>
```

Reglas recomendadas:

- si `OCP_MAP_VISIBLE_NAMESPACES` no existe o está vacío, mostrar todos los
  namespaces configurados en `OCP_MAP_NAMESPACES` para conservar compatibilidad;
- la vista operativa, sus conteos, rankings, `Sin flujo` y la API pública solo
  consideran namespaces visibles;
- `/admin` puede listar todo el inventario sensado y marcar como `Oculto` lo que
  no participa en la vista operativa;
- cada namespace sensado necesita su Role y RoleBinding de lectura;
- ambas variables se leen al iniciar el pod porque llegan mediante `envFrom`;
  cambiar el ConfigMap requiere reiniciar/renovar el Deployment;
- con un namespace consultado cada cinco segundos, los once namespaces
  configurados completan una vuelta aproximadamente cada cincuenta y cinco
  segundos.

Identidad técnica definida en el manifiesto:

```text
system:serviceaccount:testing-pmx3:ms-ocp-tools
```

Permisos mínimos requeridos:

```text
apiGroup: apps
resource: deployments
verbs: get, list
```

No se solicitan permisos de escritura, escalamiento o lectura de Secrets.

## Semántica de estado

Regla cerrada:

```text
APAGADO      spec.replicas = 0
ENCENDIDO    spec.replicas > 0
DESCONOCIDO  nunca existió una consulta exitosa
```

La disponibilidad se muestra por separado:

```text
readyReplicas / replicasDeseadas
```

Ejemplo:

```text
Estado: Encendido
Réplicas: 0/1
```

Esto evita clasificar como apagado un Deployment configurado con réplicas pero
temporalmente no disponible.

## Sensado periódico

### Implementación vigente en código

En vez de consultar Deployment por Deployment, el backend utiliza una llamada
`list deployments` por namespace. Esa respuesta contiene todos los Deployments
y sus campos de estado, reduciendo carga y latencia.

Ciclo implementado:

```text
arranque
  → exponer inmediatamente el último estado persistido
  → consultar namespace 1
  → esperar 5 segundos
  → consultar namespace 2
  → esperar 5 segundos
  → consultar namespace 3
  → esperar 5 segundos
  → repetir desde el primero
```

Con tres namespaces, cada uno se actualiza aproximadamente cada 15 segundos.
El intervalo entre consultas será parametrizable.

Esta estrategia sustituye la idea inicial de consultar cinco microservicios
individuales cada cinco segundos. La implementación está terminada; aún debe
validarse en ejecución contra la API real con el ServiceAccount creado.

No se utilizará `watch` de Kubernetes en la primera beta.

### Reglas ante éxito y error

- Una consulta exitosa actualiza de forma transaccional el inventario y estado
  de ese namespace.
- Cada Deployment conserva la fecha de su última observación exitosa.
- El namespace conserva último intento, último éxito y último error.
- Una falla no sobrescribe el último estado bueno con `Apagado`.
- Si nunca hubo éxito, el estado es `Desconocido`.
- La UI muestra textos relativos como `Actualizado hace 2 min` o
  `Última actualización exitosa hace 3 días`.
- Un error de un namespace no bloquea la lectura de los demás.
- Timeout máximo por consulta: 5 segundos.

El estado persistido permite mostrar información inmediatamente después de un
reinicio mientras comienza un nuevo ciclo.

## Persistencia

Decisión cerrada: desplegar PostgreSQL como dependencia separada con
almacenamiento persistente.

No usar como persistencia operativa:

- H2 embebido;
- SQLite dentro del contenedor;
- `emptyDir`;
- ConfigMap como base compartida.

La aplicación conserva su único Deployment. PostgreSQL tendrá recursos de
infraestructura propios y un PVC.

El datasource PostgreSQL será distinto del datasource Informix de la
Herramienta 01.

### Modelo físico implementado

```text
ocp_namespace
  name PRIMARY KEY
  last_attempt_at
  last_success_at
  last_error

ocp_deployment
  id
  namespace FK
  name
  desired_replicas
  ready_replicas
  available_replicas
  active
  first_seen_at
  last_seen_at
  missing_since
  UNIQUE(namespace, name)

test_case
  id
  code UNIQUE
  name
  description
  display_order

test_case_element
  id
  test_case_id
  name
  display_order

test_case_deployment
  test_case_id
  deployment_id
  PRIMARY KEY(test_case_id, deployment_id)

testing_mark
  deployment_id PRIMARY KEY
  responsible
  note
  marked_at
  expires_at

testing_mark_history
  id
  deployment_id
  action
  responsible
  note
  occurred_at
  expires_at
```

### Evolución de modelo contratada

La siguiente migración deberá evolucionar el modelo sin perder el seed ni las
relaciones existentes:

```text
catalog_element
  id
  name UNIQUE
  description
  display_order
  created_at
  updated_at
  archived_at

test_case
  + metadata estructurada y ordenada
  + created_at
  + updated_at
  + archived_at

test_case_element
  test_case_id
  catalog_element_id
  display_order
  PRIMARY KEY(test_case_id, catalog_element_id)

test_case_deployment
  test_case_id
  deployment_id
  created_at
  updated_at
  PRIMARY KEY(test_case_id, deployment_id)
```

Los elementos dejan de ser texto privado de cada caso y pasan a ser un catálogo
global muchos-a-muchos. Los metadatos visibles del caso se editan como una lista
estructurada y ordenada, no como una cadena única opaca.

La implementación podrá ajustar nombres y tipos, pero debe conservar:

- identidad compuesta namespace + Deployment;
- relación muchos a muchos entre casos y Deployments;
- estado actual sin copias históricas periódicas;
- auditoría de marcas;
- frescura y último error por namespace.

No se inserta una copia completa del estado cada cinco segundos. Cada lectura
exitosa actualiza el estado corriente y sólo los cambios manuales de la marca
`En pruebas` generan historial.

## Retención

No se almacena historial de estados ni una tabla por ejecución de sensado. Se
persiste únicamente el inventario vigente, la frescura por namespace, el
catálogo y la auditoría de cambios manuales de `En pruebas`. La primera beta no
elimina automáticamente esa auditoría; una política de depuración podrá
añadirse cuando exista una necesidad real de volumen o cumplimiento.

## Marca compartida `En pruebas`

Es una modificación funcional de OCP Tools, no una modificación de OCP.

Datos acordados:

```text
responsable o equipo: obligatorio
nota: opcional
válido hasta: opcional
fecha de inicio: automática
```

Reglas:

- una sola marca activa por Deployment;
- visible en las cuatro vistas;
- puede renovarse o retirarse;
- cada cambio genera historial;
- una marca vencida se identifica visualmente y deja de considerarse activa;
- marcar o desmarcar no cambia réplicas ni recursos OCP.

No habrá autenticación de usuarios en esta fase. El responsable será declarado
por el usuario y no tendrá verificación criptográfica.

## Enlaces a la consola

Formato:

```text
{OCP_CONSOLE_BASE_URL}/k8s/ns/{namespace}/deployments/{deployment}
```

Base inicial:

```text
https://console-openshift-console.apps.ocpnprod7.gp.inet
```

Reglas:

- base externalizada en ConfigMap;
- abrir en otra pestaña con `noopener noreferrer`;
- si el navegador tiene sesión, la consola abre el recurso;
- si no tiene sesión, la consola solicita login manual;
- OCP Tools no captura credenciales de la consola.

## API funcional implementada

```http
GET  /api/v1/ocp-map/snapshot
PUT  /api/v1/ocp-map/deployments/{namespace}/{name}/testing-mark
POST /api/v1/ocp-map/deployments/{namespace}/{name}/testing-mark/remove
```

El snapshot reúne casos, inventario completo, inventario sin flujo, dependencias
y frescura por namespace. La UI calcula las cuatro vistas desde la misma
fotografía consistente.

Los endpoints de lectura no deben disparar un sensado completo por solicitud;
deben responder desde el estado persistido. El scheduler actualiza ese estado
en segundo plano.

## API JSON pública de solo lectura — implementada en `0.3.0`

La evolución ofrece un contrato JSON para explotación desde herramientas
externas:

```http
GET /api/v1/ocp-map/deployments
GET /api/v1/ocp-map/test-cases
GET /api/v1/ocp-map/elements
GET /api/v1/ocp-map/export
GET /api/v1/ocp-map/test-cases/{id}/flow
```

Reglas cerradas:

- respuestas exclusivamente JSON;
- sin endpoints públicos de escritura;
- lectura desde PostgreSQL, sin disparar consultas OCP por request;
- casos y elementos activos por defecto, con opción explícita de incluir
  archivados;
- Deployments vigentes por defecto, con opción explícita de incluir inactivos;
- `export` consolida fecha de generación, frescura de namespaces, casos,
  elementos, Deployments y relaciones;
- las operaciones usadas por `/admin` son internas a la UI y quedan fuera de
  este contrato público.

## Observabilidad

Registrar como mínimo por consulta de namespace:

```text
correlationId
namespace
action=LIST_DEPLOYMENTS
result
deploymentsFound
durationMs
startedAt
finishedAt
```

Para marcas:

```text
deployment
namespace
action=TESTING_MARK_SET | TESTING_MARK_REMOVED | TESTING_MARK_RENEWED
responsible
result
timestamp
```

No almacenar respuestas completas de la API si no son necesarias.

## Casos de prueba mínimos

1. Inventario exitoso en los once namespaces configurados.
2. Namespace vacío.
3. Namespace inaccesible sin perder el último estado bueno.
4. Timeout de cinco segundos y continuación con el siguiente namespace.
5. Deployment con `0` réplicas: `Apagado`.
6. Deployment con réplicas deseadas y `0` listas: `Encendido`, `0/N`.
7. Deployment nuevo: aparece en `Sin flujo`.
8. Deployment desaparecido: se conserva inactivo y sale del ranking activo.
9. Relación muchos a muchos caso/Deployment.
10. Ranking descendente y desempate estable por nombre.
11. Marca `En pruebas` compartida, renovada, vencida y retirada.
12. Reinicio de aplicación: mostrar último estado y reanudar sensado.
13. Enlace correcto y apertura en nueva pestaña.
14. Regresión completa de la Herramienta 01.
15. Vista inicial de casos compacta, sin microservicios expandidos.
16. Expansión independiente de elementos y microservicios del caso.
17. Alta y edición de casos, incluida modificación del código.
18. Archivado, vista de archivados y restauración sin pérdida de relaciones.
19. Catálogo global de elementos reutilizados por varios casos.
20. Asociación solo con Deployments descubiertos por OCP.
21. Asociación de Deployment inactivo con advertencia.
22. Deployment relacionado solo con casos archivados visible en `Sin flujo`.
23. Fecha de última actualización visible después de guardar en `/admin`.
24. API JSON de solo lectura y export consolidado sin sensado bajo demanda.
25. Edición administrativa con elementos y Deployments seleccionados primero,
    separación visual, contadores y filtros sin perder asociaciones ocultas.
26. Inventario completo de microservicios en la vista normal y filtros combinados.
27. Último código precargado sin cálculo automático del siguiente.
28. Clonación transaccional con relaciones, anotación y flujo.
29. Anotación extensa editable en administración y de solo lectura en público.
30. Editor de flujo con nodos de catálogo/libres, retorno limitado y guardado.
31. Visor público sin enlace administrativo, autoejecutable y sin efecto real.
32. Búsqueda de Deployment aplicada también a los casos que lo contienen y
    combinada con namespace, estado y marca `En testing`.
33. Nombres largos y detalle de nodo ajustados dentro de la caja del flujo.
34. Consulta anidada `A → B → C`, retorno automático `C → B → A` y continuación
    de A únicamente después de recibir la respuesta.
35. Orden de operaciones editable sin mostrar números o grupos en el lienzo.
36. Importación y exportación JSON sin persistencia hasta pulsar `Guardar flujo`.

## Gates de despliegue y validación real

### Gate 1 — ServiceAccount (completo)

- permisos del usuario para crear ServiceAccount, Roles y RoleBindings:
  confirmados como `yes` en los namespaces iniciales;
- ServiceAccount dedicado creado;
- `get/list deployments` concedido en los namespaces iniciales;
- lectura real demostrada con la identidad técnica;
- ausencia de verbos de modificación demostrada con pruebas negativas.

### Gate 2 — PostgreSQL (infraestructura operativa)

- imagen: `openshift/postgresql:15-el8`, fijada por digest en el manifiesto;
- StorageClass: `ocs-storagecluster-ceph-rbd`, RWO, Filesystem;
- PVC: `1Gi`, expandible;
- recursos: request `50m/256Mi`, límite `400m/512Mi`;
- Secret, Service, PVC y StatefulSet de una réplica creados, sin Route;
- PostgreSQL confirmado `1/1 Running` y aceptando conexiones;
- conectividad y migraciones validadas por la demo `ms-ocp-tools:0.2.0`.

La capacidad libre exacta del pool Ceph no pudo consultarse porque el usuario
no puede leer `cephclusters.ceph.rook.io`. El usuario aceptó continuar con 1 Gi;
el PVC quedó aprovisionado y PostgreSQL inició correctamente sobre él.

## Pendientes concretos

- validación interactiva del usuario en `/admin` de clonación, anotaciones,
  búsquedas y guardado de un primer flujo con datos reales;
- definir mecanismo y frecuencia de backup;
- fijar valor predeterminado de vencimiento para `En pruebas`, si se desea;
- actualizar el seed cuando se entregue el inventario real corregido.

## Artefactos de desarrollo

- migraciones Flyway: `src/main/resources/db/ocp-map/`;
- backend y scheduler: `src/main/java/com/ocptools/ocpmap/`;
- API: `src/main/java/com/ocptools/api/OcpMapResource.java`,
  `OcpMapCatalogResource.java` y `OcpMapAdminResource.java`;
- UI integrada: `src/main/resources/META-INF/resources/`;
- PostgreSQL y RBAC: `ocp/postgresql-*.yaml` y `ocp/ocp-map-rbac.yaml`.

Las migraciones `V1` y `V2` corresponden a la demo `0.2.0` ya desplegada y no
se reescriben. `V3__administrable_catalog.sql` incorpora el catálogo global,
metadatos editables y archivado. `V4__test_case_notes_and_flows.sql` es
estrictamente aditiva: crea tablas uno-a-uno para anotaciones y definiciones
JSONB de flujo, con borrado en cascada solo si se eliminara físicamente su caso.

## Verificación de desarrollo realizada

Completado localmente:

- sintaxis JavaScript con `node --check`;
- configuración de los once namespaces y RBAC declarativo; la autorización real
  de `diagnosticador-test` continúa como gate;
- UI compacta, selector global y administración `/admin`;
- endpoints JSON públicos y endpoints internos de administración.

La evolución `0.5.0-SNAPSHOT` compiló localmente con JDK 21 y target Java 17.
Maven ejecutó 24 pruebas sin fallos y generó correctamente el paquete Quarkus.
El paquete arrancó en modo local: `/`, `/admin`, `/flow`, `/admin/flow`, favicon
y liveness respondieron `200`; el snapshot devolvió el `503` controlado esperado
al no existir un PostgreSQL local. También se completaron:

- parseo de los 11 manifiestos YAML;
- parseo XML del `pom.xml`;
- sintaxis de los tres archivos JavaScript con `node --check`;
- smoke visual de vista normal, administración, clonación, anotación,
  microservicios y ambos modos de flujo;
- 24 pruebas unitarias totales, incluidas cuatro para validar flujos.

La imagen Linux/amd64 `0.5.0` fue publicada y desplegada. Flyway validó las
cuatro migraciones y aplicó V4 sobre PostgreSQL 15.8. Los conteos previos de 17
casos, 26 elementos y 96 relaciones caso-deployment se conservaron; el
inventario pasó de 481 a 491 deployments por los diez descubiertos en
`diagnosticador-test`. Los once namespaces terminaron una vuelta de sensado sin
errores y el pod quedó `1/1 Ready`, sin reinicios.

Antes del rollout, `scripts/backup-ocp-map-db.ps1` creó un dump de 51 357 bytes,
validado con `pg_restore`, junto a sus conteos y manifiesto. SHA-256:
`955ab34cd91d9b268f366f45de433183ecd9f92b12f7232f15cb4ed86f36bc2c`.

### Mejora visual `0.5.2`

La evolución `0.5.2` no incorpora migraciones ni cambios de backend. Mantiene
la estructura de `0.5.0` y agrega:

- alineación estable de las acciones de cada caso;
- `Anotación` visible incluso cuando está vacía y `Sin flujo` cuando no existe
  diagrama;
- visor y editor en popup casi a pantalla completa, con rutas completas como
  fallback, sin perder búsqueda, filtros, scroll ni casos expandidos al cerrar;
- lienzo navegable mediante zoom, paneo, ajuste al flujo y minimapa;
- panel de propiedades superpuesto y ocultable;
- selección de conexiones desde la línea, etiqueta o icono de edición, con área
  de clic ampliada y resaltado también para retornos.

La validación final ejecutó 24 pruebas Maven y smoke tests de navegador tanto
con datos controlados como contra la Route real. El pod quedó `1/1 Ready`, sin
reinicios, con digest
`sha256:a25ee3afd67ffebc9e03103e2e60ec8f67da12ed59631e411756288288bc062f`.
Antes y después del rollout se conservaron 17 casos, 26 elementos, 96
relaciones caso-deployment, 28 relaciones caso-elemento y 2 flujos. El
inventario observado era de 493 deployments al finalizar la comprobación y los
once namespaces configurados completaron lecturas exitosas.

### Versión `0.6.0`

La implementación conserva la estructura de tablas de `0.5.2`; no agrega
migraciones ni modifica las filas existentes. El JSON de flujo evoluciona a
`schemaVersion: 2`, mientras el backend y el navegador siguen aceptando el
esquema 1 para poder abrir los diagramas ya guardados.

Incluye:

- el filtro de Deployment también limita la pestaña `Casos de prueba` a los
  casos que contienen al menos un Deployment coincidente;
- nombres de nodos ajustados a dos líneas y detalle descriptivo editable;
- conexiones `Continuación` y `Consulta con respuesta`, sin reintentos ni
  etiquetas visibles `AUTO`, `MANUAL`, `RETURN`, números o grupos;
- consultas anidadas con retorno automático y continuación secuencial una vez
  recibida la respuesta;
- orden de operaciones salientes mediante controles subir/bajar;
- opacidad reducida para circuitos ajenos o ya completados durante la
  simulación;
- importación y exportación JSON desde el editor; importar solo prepara los
  cambios y requiere `Guardar flujo` para persistirlos.

La verificación ejecutó `node --check`, las 25 pruebas Maven sin fallos y smoke
tests de navegador con datos controlados y contra la aplicación real. En la
Route se comprobaron 18 casos, búsqueda por Deployment con casos asociados,
apertura del visor en popup conservando el filtro, 15 nodos cargados y los
controles JSON del editor.

El rollout quedó en la revisión 9, con el pod `1/1 Ready`, sin reinicios, y la
imagen `ms-ocp-tools:0.6.0` fijada al digest
`sha256:3e16eab02b22401a05d5a238a06a605b1bececcfaead502eff2cefb4f09368d3`.
Flyway validó las cuatro migraciones existentes sin aplicar cambios. Después
del despliegue se conservaron 18 casos, 26 elementos, 99 relaciones
caso-Deployment, 28 relaciones caso-elemento, 2 anotaciones y 4 flujos; el
inventario observado contiene 496 Deployments y 11 namespaces.

Antes del rollout se creó el respaldo
`artifacts/database-backups/ocp-map-before-v4-20260921-221540.dump`, de 58 019
bytes, con SHA-256
`55fcacb7b767360720a94f5b44993e2eeedc2cf71d83f0efd16ddb0b5cf1dc5d`.
