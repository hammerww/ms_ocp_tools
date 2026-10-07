# Herramienta 04 — Servicios externos

Fecha de vigencia: 2026-10-06
Estado desplegado: `0.8.0` operativa en `testing-pmx3`
Estado del código: `0.8.0`

## Objetivo

Centralizar en OCP Tools el monitoreo de backends que no son Deployments del
clúster: APIs, puertos TCP y bases de datos. La ejecución se origina en el pod,
aprovechando la conectividad de OpenShift, y el estado se persiste en el
PostgreSQL existente.

La prueba de concepto Node ubicada en `modulo_monitor_elementos_externos` es
solo fuente de migración. Node.js, sus archivos JSON/JSONL, el scheduler del
navegador no forman parte de la arquitectura final. `0.7.2` admite de forma
explícita certificados internos sin validar cadena ni nombre, manteniendo el
canal HTTPS cifrado. Desde `0.7.3`, esa excepción permanece acotada al
transporte del monitor externo y continúa siendo configurable.

## Alcance de la primera entrega

- pestaña pública inicial `Servicios externos` dentro de Mapa operativo OCP;
- filtros propios por texto, ambiente y estado;
- objetivos, hosts, puertos y URLs visibles en la vista pública;
- secretos nunca expuestos por la API pública ni por el HTML;
- servicios con varias pruebas ordenadas, obligatorias o informativas;
- pruebas `TCP`, `HTTP`, `TOKEN_HTTP` y `DATABASE`;
- dependencia secuencial de una prueba respecto de otra anterior;
- obtención de token HTTP y reutilización efímera por una prueba dependiente;
- motores JDBC Oracle, PostgreSQL, SQL Server y MySQL;
- ejecución programada configurable, inicialmente cada 10 minutos, y ejecución
  manual protegida;
- KPIs por servicio, no por prueba;
- vista pública histórica con rangos de 1 h, 6 h, 24 h, 7 días, 30 días o un
  rango personalizado de hasta 90 días; línea de tiempo de hasta 40 bloques,
  disponibilidad, media, p95, advertencias y caídas;
- exportación ZIP del rango/servicio/grupo seleccionado con el resumen, el
  detalle crudo de ejecuciones y los reportes de downtime;
- persistencia de ejecuciones, resultados e incidentes;
- gestión de servicios, grupos y credenciales;
- exportación KDBX agrupada `Ambiente → Tipo → Sistema`;
- importador repetible de configuración e historial de la prueba de concepto;
- archivado lógico de servicios y credenciales, listado de archivados y
  desarchivado protegido.

Quedan fuera de esta entrega: ICMP/ping, autenticación personal, auditoría de
rotación de clave maestra, notificaciones y despliegue automático.

## Navegación y contrato visual

Orden de pestañas:

```text
Servicios externos | Casos de prueba | Microservicios | Sin flujo | Impacto
```

Los filtros de OCP continúan siendo transversales a sus cuatro pestañas. La
pestaña externa conserva filtros independientes. Las tarjetas de frescura de
namespaces se agrupan en un bloque plegable y se ocultan cuando se visualizan
servicios externos.

Cada servicio se muestra contraído. Al abrirlo se ven sus pruebas, destino,
criticidad, último resultado, duración y mensaje. La UI pública puede ver toda
la topología técnica aprobada, pero no usuario, password, token, client secret
ni valores de headers sensibles.

La edición utiliza un diálogo amplio para evitar perder búsqueda, filtros,
scroll o elementos expandidos. Incluye datos generales, lista ordenada de
pruebas, dependencias, credencial y parámetros específicos del protocolo.

## Seguridad y sesiones de gestión

No hay login web en esta fase. Las acciones modificatorias requieren ingresar
la clave maestra compartida:

```text
Agregar / editar / duplicar / archivar servicio
Gestionar credenciales
Ejecutar ahora
Exportar KDBX
Importar historial
```

La validación se realiza en el servidor. Una validación correcta produce un
token aleatorio guardado solo en memoria del navegador y del pod, con vigencia
de 15 minutos. No se guarda en `localStorage`, cookie o base de datos. Cinco
fallos dentro de cinco minutos bloquean nuevos intentos durante 15 minutos para
la dirección observada por la aplicación. Con una sola réplica `Recreate`, la
sesión en memoria es consistente; un reinicio la invalida.

Este mecanismo limita operaciones accidentales, pero no aporta identidad ni
autorización individual. Cualquier persona que conozca la clave maestra tiene
las mismas facultades. Es un riesgo aceptado para esta fase.

## Protección de credenciales

La tabla `external_credential` es la fuente de verdad. El payload sensible se
cifra con AES-256-GCM y un IV aleatorio por registro. Se usan dos secretos
distintos:

```text
EXTERNAL_CREDENTIAL_ENCRYPTION_KEY  Base64 de 32 bytes; cifra la BD
EXTERNAL_KDBX_MASTER_PASSWORD       desbloquea gestión y archivo KDBX
```

Nunca reutilizar una clave como la otra. Ambas llegan mediante el Secret
`tools`; no se colocan en ConfigMap, SQL, logs, frontend, documentación o
artefactos. La rotación de la clave de cifrado requiere un proceso de
recifrado; cambiarla directamente vuelve ilegibles los registros existentes.
La clave KDBX puede rotarse actualizando el Secret y reiniciando el pod; las
sesiones vigentes se pierden.

Headers como `Authorization`, `Cookie` o API keys no se aceptan como texto de
configuración. Deben provenir de una credencial. El body HTTP puede referir
marcadores `${username}`, `${password}`, `${token}`, `${clientId}` y
`${clientSecret}`; se sustituyen solo en memoria durante la ejecución.

## Modelo de pruebas

### TCP

Conexión a host y puerto, sin enviar payload. Es la prueba principal de
conectividad y no requiere credencial.

### HTTP

Método declarativo, URL, headers no sensibles, body opcional, códigos esperados
y texto que debe aparecer. El texto esperado usa `String.contains`: es una
coincidencia literal y sensible a mayúsculas/minúsculas; no interpreta
comodines, SQL LIKE ni expresiones regulares. Vacío valida sólo el código HTTP.
Usa `HttpURLConnection`/`HttpsURLConnection`; no ejecuta `curl` ni shell. No sigue redirecciones automáticamente, para evitar reenviar
autenticación hacia otro destino; un `3xx` puede declararse como respuesta
esperada. Los métodos permitidos son `GET`, `HEAD` y `POST`; se excluyen los
verbos típicamente mutatorios. Limita la respuesta a 1 MiB. En testing,
`EXTERNAL_MONITOR_TLS_VERIFY=false`: TLS continúa cifrando el tráfico, pero no
se valida la cadena ni el nombre del certificado del destino. Esta excepción
se aplica únicamente al transporte HTTPS del monitor externo; no cambia el
cliente SOAP CMS.

Desde `0.7.3`, cada intento usa una conexión nueva y envía `Connection: close`
para no reutilizar sockets persistentes degradados. Un timeout sin respuesta
HTTP produce exactamente un segundo intento, también con conexión nueva. No se
reintentan códigos HTTP, errores de contenido, DNS, conexión rechazada ni TLS.
El tiempo máximo de una prueba que agote ambos intentos puede aproximarse a dos
veces su timeout configurado. Los endpoints `POST` usados para monitoreo deben
ser idempotentes: un timeout no garantiza que el destino no haya recibido la
primera solicitud.

El resultado persiste y muestra `responseCode` cuando el servidor alcanzó a
responder. Los fallos sin respuesta se distinguen por fase:
`HTTP_CONNECT_TIMEOUT`, `HTTP_WRITE_TIMEOUT`, `HTTP_RESPONSE_TIMEOUT`,
`HTTP_TLS`, `HTTP_DNS`, `HTTP_CONNECT` o `HTTP_IO`. La UI antepone, por ejemplo,
`HTTP 200` o `HTTP 400` al detalle de la comprobación manual o programada.

### TOKEN_HTTP

Es una prueba HTTP que extrae un valor de una ruta JSON simple, por ejemplo
`access_token`. El valor solo existe durante esa ejecución y puede alimentar
como Bearer a una prueba posterior dependiente. El token no se persiste.

### DATABASE

Usa JDBC y ejecuta una consulta explícita o el valor seguro por defecto:

```text
Oracle:                      SELECT 1 FROM DUAL
PostgreSQL/SQL Server/MySQL: SELECT 1
```

La prueba requiere credencial. Antes puede declararse una prueba TCP y hacer
que DATABASE dependa de ella. Los seis destinos de BD de la prueba de concepto
son Oracle; su compatibilidad real con el driver Java y servidores legados es
un gate de despliegue. La consulta se restringe a una sola sentencia
`SELECT`/`WITH`, rechaza verbos de escritura y debe ejecutarse con un usuario de
solo lectura.

## Estados e incidentes

Estados de servicio:

```text
UP       todas las pruebas obligatorias pasaron
WARNING  primer fallo obligatorio pendiente de confirmación, o fallo informativo
DOWN     dos o más fallos programados obligatorios consecutivos
UNKNOWN  aún no existe una ejecución programada
```

Reglas cerradas:

1. primer fallo obligatorio programado: incidente `PENDING`, servicio `WARNING`;
2. segundo fallo obligatorio consecutivo: incidente `OPEN`, servicio `DOWN`;
3. primer éxito programado posterior: incidente `RECOVERED`, racha en cero;
4. una ejecución manual se persiste, pero no modifica racha, estado corriente
   ni incidente; la UI la identifica expresamente como comprobación manual y
   conserva visible el estado programado oficial;
5. una prueba informativa fallida puede dejar el servicio en `WARNING`, pero no
   abre una racha de caída.

## KPIs

La cabecera operativa conserva los conteos por estado. Las tarjetas de
disponibilidad y p95 se recalculan con el rango histórico y servicio elegidos.
El rango predeterminado es 24 horas; el personalizado usa `America/Lima` y no
puede superar 90 días. El backend elige la agregación para no dibujar más de 40
bloques horizontales por servicio.

El ZIP del rango contiene `resumen.csv` (total y resumen por servicio) y
`ejecuciones.csv` (cada ejecución programada con estado, duración y fechas).
Las ejecuciones manuales se excluyen de todos los archivos y de los KPI.

## Evolución 0.8.0 desplegada

La vista `Historial y KPI` contiene tres hojas con un mismo rango y filtros por
grupo o servicio:

1. `Sensado`: conserva los bloques de estado, alineados al extremo derecho para
   que la posición más reciente sea consistente aunque un servicio tenga menos
   muestras;
2. `Downtime`: muestra los intervalos confirmados dentro del horario laboral;
3. `Incidentes`: hace accesibles los estados `PENDING`, `OPEN` y `RECOVERED` y
   sus clasificaciones.

El cálculo de downtime parte exclusivamente de incidentes generados por
ejecuciones programadas. Se conserva la hora del primer fallo, pero el reporte
solo incorpora incidentes que llegaron a confirmarse. Una comprobación manual
no abre, confirma, recupera ni acorta un incidente oficial.

Cada grupo puede tener un horario con zona IANA, días laborables, hora inicial,
hora final y excepciones por fecha. Un servicio puede sobrescribir el horario
de su grupo. Sin configuración se aplica `America/Lima`, lunes a viernes de
08:00 a 19:00. Solo la intersección entre incidente, rango consultado y horario
laboral aporta duración al reporte.

Los intervalos pueden clasificarse total o parcialmente, después de
desbloquear la gestión con la clave maestra:

```text
UNPLANNED          rojo; suma en Total down
REQUESTED_RESTART  azul; suma en Justificado y no en Total down
PLANNED_WORK       azul; suma en Justificado y no en Total down
```

La anotación admite ticket, solicitante, responsable de confirmación y nota;
no altera la evidencia cruda. Las barras solo imprimen duración cuando superan
una hora. Las columnas finales separan `Total down` y `Justificado`. La precisión
temporal está limitada por el intervalo de sensado (10 minutos por defecto).

La gestión protegida incorpora `Archivados` y `Horarios`. Un servicio archivado
queda fuera del scheduler, snapshot y KPI, pero conserva todo su historial y se
puede desarchivar. Al restaurarlo vuelve como `UNKNOWN` y su estado se recalcula
en el siguiente ciclo programado.

La exportación ZIP de `0.8.0` contiene:

```text
resumen.csv
ejecuciones.csv
downtime-resumen.csv
downtime-detalle.csv
```

`downtime-resumen.csv` sirve para reporte por grupo/proveedor; separa horas
imputables y justificadas. `downtime-detalle.csv` conserva intervalos,
clasificación, ticket, solicitante y nota.

La utilidad `Validación TCP / Conectividad` conserva el timeout de socket de
10 segundos y agrega un límite cliente de 12 segundos, contador visible y
cancelación con `AbortController`. Tanto el éxito como timeout, rechazo, falta
de ruta o error liberan siempre formulario y botón.

La cabecera operativa además calcula:

- total de servicios activos;
- operativos, en advertencia, caídos y sin datos;
- incidentes abiertos o pendientes.

Los conteos son por servicio. Las ejecuciones manuales se excluyen de
disponibilidad, p95, rachas e incidentes.

## Persistencia

Flyway V5 agrega únicamente:

```text
external_group
external_credential
external_service
external_probe
external_run
external_probe_result
external_incident
```

No modifica `ocp_namespace`, `ocp_deployment`, casos, elementos, relaciones,
anotaciones ni flujos. Los borrados funcionales son archivados lógicos; el
historial se conserva.

Flyway V7 de `0.8.0` es aditiva y crea:

```text
external_availability_schedule
external_schedule_exception
external_incident_classification
```

También agrega índices de rango y clasificación. No modifica filas históricas
ni elimina objetos de V1–V6.

## API

Lectura pública:

```http
GET /api/v1/external-services/snapshot
GET /api/v1/external-services/history?hours={1|6|24|168|720}[&serviceId={id}|&groupId={id}]
GET /api/v1/external-services/history?from={ISO-8601}&to={ISO-8601}[&serviceId={id}|&groupId={id}]
GET /api/v1/external-services/history/export.zip?...mismo rango y filtro...
GET /api/v1/external-services/downtime?...mismo rango y filtro...[&groupId={id}]
GET /api/v1/external-services/incidents?...mismo rango y filtro...[&groupId={id}]
```

Gestión protegida por `X-External-Admin-Token`:

```http
POST /api/v1/external-services/admin/unlock
GET|POST /api/v1/external-services/admin/credentials
PUT|POST /api/v1/external-services/admin/credentials/{id}[/archive]
POST /api/v1/external-services/admin/groups
POST /api/v1/external-services/admin/services
PUT /api/v1/external-services/admin/services/{id}
POST /api/v1/external-services/admin/services/{id}/clone
POST /api/v1/external-services/admin/services/{id}/archive
GET /api/v1/external-services/admin/services/archived
POST /api/v1/external-services/admin/services/{id}/restore
POST /api/v1/external-services/admin/services/{id}/run
GET|POST /api/v1/external-services/admin/schedules
PUT|POST /api/v1/external-services/admin/schedules/{id}[/archive]
POST /api/v1/external-services/admin/incidents/{id}/classifications
POST /api/v1/external-services/admin/incidents/classifications/{id}/archive
GET /api/v1/external-services/admin/export.kdbx
POST /api/v1/external-services/admin/import-history
```

## Configuración no sensible

```text
EXTERNAL_MONITOR_ENABLED=true   # configuración preparada para 0.7.1
EXTERNAL_MONITOR_TLS_VERIFY=false
EXTERNAL_MONITOR_INTERVAL=10m
EXTERNAL_MONITOR_INITIAL_DELAY=30s
EXTERNAL_MONITOR_PARALLELISM=1
EXTERNAL_ADMIN_SESSION_DURATION=15m
EXTERNAL_ADMIN_MAX_FAILURES=5
EXTERNAL_ADMIN_FAILURE_WINDOW=5m
EXTERNAL_ADMIN_BLOCK_DURATION=15m
```

Los cambios del ConfigMap requieren rollout/reinicio por el uso de `envFrom`.

## Evolución 0.7.3 desplegada

- no crea tablas ni altera datos existentes;
- reemplaza el cliente HTTP compartido por una conexión independiente por
  intento y solicita su cierre explícito;
- reintenta una sola vez exclusivamente los timeouts sin respuesta HTTP;
- distingue timeout de conexión, escritura y respuesta, además de errores TLS,
  DNS, conexión e I/O;
- presenta en la UI el código HTTP persistido para ejecuciones manuales y
  programadas;
- la desactivación TLS se aplica solo a las conexiones HTTPS del monitor y no
  cambia el cliente SOAP CMS;
- alinea el ConfigMap versionado con `EXTERNAL_MONITOR_PARALLELISM=1`;
- la imagen, el manifiesto y el Deployment apuntan a `0.7.3`;
- el target Docker local `test` superó 47 pruebas, incluidas cuatro pruebas
  específicas del nuevo transporte HTTP, sin fallos ni errores;
- imagen publicada y ejecutada con digest
  `sha256:e30565fd54fed7809f6b02e9ba5dfe20f4a087dc0c4f379c34a8157abb1e1bd7`;
- Deployment revisión 21, pod `1/1 Ready`, 0 reinicios y health/UI/API pública
  con HTTP 200;
- primer ciclo: 30 servicios secuenciales en 35,613 ms; 27 `UP` y 3 `DOWN`,
  con un único `action=http-retry` por timeout y sin errores de aplicación;
- 23 resultados HTTP expusieron `responseCode` en el snapshot público;
- rollback: restaurar imagen y ConfigMap `0.7.2`; no hay rollback de base de
  datos.

## Evolución 0.7.2 desplegada

- no crea tablas ni altera las existentes;
- la imagen, el manifiesto y el Deployment apuntan a `0.7.2`;
- el rollback es restaurar imagen y ConfigMap de `0.7.1`;
- la exportación histórica cambia de un CSV agregado en navegador a un ZIP
  generado en servidor con resumen y ejecuciones;
- las tarjetas KPI dejan de usar simultáneamente ventanas fijas 24 h/7 d y
  pasan a reflejar exactamente el selector histórico visible;
- el target Docker superó 43 pruebas y la imagen `linux/amd64` publicada y
  ejecutada usa el digest
  `sha256:98dcec1ac12ef434651218aed8e6884e292b55a04ecfccf7e177a68fd1b7fc95`;
- el primer ciclo programado procesó los 28 servicios en 14,321 ms.

## Migración de la prueba de concepto

El script `ms-ocp-tools/scripts/import-external-monitor-poc.ps1`:

- solicita la clave maestra sin escribirla en la línea de comandos;
- lee `servidores_validacion.txt` y agrupa entradas de igual nombre;
- crea credenciales cifradas para las configuraciones JDBC;
- fusiona pruebas HTTP y BD que correspondan al mismo servicio;
- crea TCP + DATABASE para cada destino JDBC;
- opcionalmente importa ambos JSONL en lotes de 200;
- es repetible para el historial: el backend omite `servicio + timestamp` ya
  importados;
- no agrega leyendas técnicas de migración en la descripción del servicio.

Antes de importar se debe restaurar una copia no productiva o respaldar la BD.
La migración no se ejecutará hasta que la versión esté construida, configurada
y el usuario autorice el despliegue. El primer rollout `0.7.0` conservó el
scheduler deshabilitado. Después de revisar los datos y confirmar ejecuciones
manuales reales, el manifiesto de `0.7.1` lo habilita cada 10 minutos, con
cuatro servicios en paralelo. Las pruebas dentro de un servicio continúan
siendo secuenciales y dependientes.

## Corrección 0.7.1 desplegada

- corrige `HTTP 406` de KDBX enviando `Accept: application/octet-stream`;
- muestra inmediatamente la última comprobación manual sin incluirla en KPI,
  incidentes ni rachas programadas;
- informa en la UI si el monitor está activo, intervalo, paralelismo y último
  ciclo registrado;
- registra en logs la configuración del monitor y el inicio/fin de cada lote;
- incorpora `Historial y KPI` sobre los datos ya persistidos;
- Flyway V6 limpia únicamente la descripción exacta
  `Migrado desde la prueba de concepto Node` y crea un índice parcial para
  consultas programadas por fecha.

Rollback de aplicación: restaurar imagen y ConfigMap de `0.7.0`. El esquema V6
es compatible con `0.7.0`; no se eliminan tablas ni datos históricos. El texto
técnico limpiado no se restaura porque no constituye información funcional.

## Rollback

### Entregable 0.8.0

Antes de desplegar se debe respaldar PostgreSQL y conservar imagen, manifiesto
y ConfigMap de `0.7.3`. El rollback normal consiste en restaurar esos tres
artefactos. `0.7.3` ignora las tablas creadas por V7, por lo que no hace falta
eliminar datos y es posible retomar `0.8.0` sin perder horarios ni
clasificaciones.

Una reversión física de V7 solo se admite después de un `pg_dump` validado y
con la aplicación `0.7.3` detenida. El orden es: eliminar
`external_incident_classification`, `external_schedule_exception` y
`external_availability_schedule`; después retirar únicamente la fila de V7 de
`flyway_schema_history`. Es destructiva para horarios y justificaciones, por lo
que no forma parte del rollback operativo recomendado. El SQL explícito queda
en `scripts/rollback-external-downtime-v7.sql` y no se ejecuta automáticamente.

### Entrega inicial del módulo

La reversión preferida es de aplicación: restaurar imagen y ConfigMap `0.6.0`.
Esa versión ignora las tablas `external_*`, por lo que no hace falta eliminar
datos y se conserva la posibilidad de volver a `0.7.0`.

Solo si se exige reversión física del esquema:

1. generar y validar `pg_dump`;
2. detener/restaurar la aplicación `0.6.0`;
3. ejecutar `scripts/rollback-external-services.sql`;
4. confirmar que solo desaparecieron tablas `external_*` y el registro V5.

La reversión física es destructiva para el historial y las credenciales del
módulo; no se ejecuta como parte de un rollback normal.

## Verificaciones completadas

- compilación Java 17 y empaquetado Quarkus de `0.7.0`;
- 39 pruebas sin fallos, incluidas política de estado, validación, cifrado
  AES-GCM, bloqueo de intentos administrativos y apertura real del KDBX
  exportado;
- arranque local, health y entrega de HTML, JavaScript y CSS;
- sintaxis de `external.js` y del importador PowerShell;
- Flyway V1–V5 validado sobre PostgreSQL 15 desechable;
- respaldo real pre-V5 verificado con `pg_restore` y SHA-256;
- imagen Linux `amd64` publicada con digest
  `sha256:c2de3977cf013db4a6d8d4dbd325e15fb749c38fce751c47c04c5c9f03b0aacb`;
- rollout 1/1, cero reinicios, Flyway V5 y conteos previos preservados;
- health, vistas, snapshots públicos y desbloqueo administrativo con HTTP 200;
- importación inicial verificada: 28 servicios, 36 pruebas, 6 credenciales
  cifradas, 15 878 ejecuciones y 16 523 resultados `POC_IMPORT`;
- 27 registros huérfanos de `BD Gfiscal |` fueron excluidos porque no existe
  un servicio, endpoint ni credencial vigente que permita monitorearlo sin
  inventar configuración.

## Validación de activación de 0.7.1

La revisión visual del catálogo y las muestras manuales desde OCP fueron
completadas. `BD - Jerarquía de Ventas` confirmó TCP y consulta Oracle en
1 677 ms. Por decisión del usuario, `0.7.1` habilita el scheduler y utiliza su
primer ciclo completo para establecer el estado corriente de los 28 servicios.

El rollout del 25 de septiembre de 2026 comprobó:

- log de configuración con `enabled=true`, intervalo de 600 segundos y
  paralelismo 4;
- inicio y fin del primer lote programado: 28 servicios en 13,017 segundos;
- resultados HTTPS con validación TLS activa; no se deshabilita TLS;
- memoria del pod durante el uso real de los drivers JDBC;
- 28 servicios clasificados sin `UNKNOWN`: 21 `UP` y 7 `WARNING` después del
  primer ciclo; el estado `DOWN` requiere la confirmación del segundo ciclo;
- `Historial y KPI` con 2,788 ejecuciones en 24 horas, disponibilidad 99.75 %
  y p95 de 610 ms;
- imagen efectiva con digest
  `sha256:24122926f0b929d63982ce1b384e489a87a7ebdd5781fba0ab75ff154421913e`.

El historial importado alimenta los KPIs de disponibilidad, pero no se usa para
afirmar que un servicio continúa disponible hoy; el estado corriente proviene
exclusivamente de las mediciones programadas.
