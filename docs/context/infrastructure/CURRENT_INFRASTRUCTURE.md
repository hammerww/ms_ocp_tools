# Infraestructura vigente — OCP Tools

Fecha de evidencia: 2026-09-24

## Plataforma desplegada

```text
Aplicación:       ms-ocp-tools
Versión desplegada: 0.7.0
Cluster:          https://api.ocpnprod7.gp.inet:6443
Proyecto:         testing-pmx3
ImageStream:      ms-ocp-tools
Deployment:       ms-ocp-tools
Service:          ms-ocp-tools
Route:            ms-ocp-tools
ConfigMap:        ms-ocp-tools-configmap
Secret real:      tools
Runtime:          Java 17 / Quarkus 3.33.3.1
Estrategia:       Recreate
Réplicas:         1
Estado funcional: herramientas 01–04 operativas; scheduler de Herramienta 04 deshabilitado
```

La UI y la Route funcionan desde la laptop corporativa dentro de la red
privada/Citrix. Los nombres e IP de pods observados son efímeros y no deben
utilizarse como configuración. En la laptop actual está disponible `oc`; antes
de validar el clúster el usuario debe ejecutar el login interactivo.

## Evidencia de imagen

El digest efectivo de `0.7.0`, verificado durante el rollout del 2026-09-24,
es:

```text
image-registry.openshift-image-registry.svc:5000/
  testing-pmx3/ms-ocp-tools@sha256:c2de3977cf013db4a6d8d4dbd325e15fb749c38fce751c47c04c5c9f03b0aacb
```

La revisión del Deployment es 11. El pod quedó `1/1 Ready`, sin reinicios, y
Flyway confirmó V1–V5. Los conteos de las tablas previas permanecieron iguales
al respaldo pre-V5. Se importaron 28 servicios, 36 pruebas, 6 credenciales
cifradas, 15 878 ejecuciones y 16 523 resultados históricos; 27 registros
huérfanos de `BD Gfiscal |` se excluyeron por carecer de configuración vigente.
El ConfigMap efectivo conserva `EXTERNAL_MONITOR_ENABLED=false` e intervalo de
10 minutos. Los estados actuales siguen `UNKNOWN` hasta una medición en vivo.

Respaldo postimportación verificado:

```text
artifacts/database-backups/ocp-map-after-external-import-20260924-220335.dump
SHA-256: 570133c03a53e76280e894fe713a95065889d4f98815c21f06eccc2d81c8b793
```

El dump contiene credenciales externas cifradas y requiere conservar las dos
claves correspondientes del Secret `tools` para poder recuperarlas.

Destino externo utilizado para push:

```text
default-route-openshift-image-registry.apps.ocpnprod7.gp.inet/
  testing-pmx3/ms-ocp-tools:<version>
```

## Secret vigente

El Secret `tools`, tipo `Opaque`, fue creado en `testing-pmx3` desde la consola
web. Claves confirmadas:

```text
CMS_DB_USER
CMS_DB_PASSWORD
EXTERNAL_CREDENTIAL_ENCRYPTION_KEY
EXTERNAL_KDBX_MASTER_PASSWORD
```

Los valores no se documentan ni se almacenan en el repositorio. Las claves
externas fueron generadas de forma criptográficamente segura; la clave de
cifrado decodifica a 32 bytes y es distinta de la clave maestra KDBX.

## Runtime y construcción comprobados

```text
Imagen base: registry.access.redhat.com/ubi8/openjdk-17:1.16-1
Java:       OpenJDK 17.0.7 LTS
Maven:      3.8.5
Plataforma: linux/amd64
Quarkus:    3.33.3.1
Informix:   JDBC 4.50.14
```

El target Docker de `0.3.2` utilizó Maven 3.8.5, compiló 39 fuentes con release
Java 17 y ejecutó 14 pruebas sin fallos. La validación real del nuevo `UPDATE`
Informix permanece como gate de despliegue.

La entrega integral `0.4.0` compiló 45 fuentes Java 17 y ejecutó 20 pruebas sin
fallos, incluidas seis pruebas de la utilidad TCP. La imagen runtime se construyó
como `linux/amd64` con `--provenance=false` y digest local
`sha256:4040b25b9153504fba8bb160cd0a62fb0dffac2fe1d70da389db9f85652c3201`.
El smoke test local confirmó health `UP`, presencia de la nueva vista,
`CONNECTED` contra el propio puerto 8080 e `INVALID_REQUEST`/HTTP 400 para una
IPv4 inválida. La conectividad real desde OCP permanece como gate de despliegue.

## Servidor de accesos

```text
Acceso SSH:  devopstest@10.4.77.21
Hostname:    lnxsrpvmo0010
Sistema:     Red Hat Enterprise Linux Server 7.6
oc client:   4.5.7
Podman:      1.6.4
Java host:   OpenJDK 8u262
Maven host:  no instalado
```

El Java del host no define el runtime del contenedor. La imagen se construyó en
Docker Desktop y se transportó como Docker archive compatible con Podman 1.6.4.

## ServiceAccount desplegado

La Herramienta 02 utiliza el ServiceAccount dedicado:

```text
system:serviceaccount:testing-pmx3:ms-ocp-tools
```

El ServiceAccount, Roles y RoleBindings fueron aplicados. Se confirmó
`get/list deployments.apps = yes` en los once namespaces activos:
`agendador-test`, `diagnosticador-test`, `pmx-test`, `security-test`, `testing`,
`testing-diversificacion`, `testing-matrix`, `testing-pmx1`, `testing-pmx2`,
`testing-pmx3` y `testing-pmx4`. Las pruebas de `patch`,
`update deployments/scale` y `delete` devolvieron `no` en
`diagnosticador-test`. `listanegra-test` quedó fuera porque el usuario no puede
gestionar su RBAC.

## Persistencia de la Herramienta 02

PostgreSQL está desplegado para OCP Tools con esta configuración:

```text
Imagen lógica:     openshift/postgresql:15-el8
Digest fijado:     sha256:447998f7e67e32d28249a78f2ee8565d6b6585cee435c1d607f62b3e7eaef69c
Workload:          StatefulSet, 1 réplica
Service:           headless ClusterIP None, interno, sin Route
PVC:               1Gi, RWO, Filesystem
StorageClass:      ocs-storagecluster-ceph-rbd
Request:           50m CPU / 256Mi memoria
Limit:             400m CPU / 512Mi memoria
Expansión:         permitida por StorageClass
Reclaim policy:    Delete
```

El usuario no puede consultar la capacidad libre del pool Ceph (`oc auth can-i
get cephclusters.ceph.rook.io -n openshift-storage` devolvió `no`). Se aceptó
1 Gi como capacidad inicial y el PVC quedó aprovisionado; PostgreSQL inició
`1/1 Running` y aceptando conexiones.

La cuota observada antes de crear PostgreSQL era:

```text
Pods:             1 / 23
Límite CPU:       500m / 1100m
Límite memoria:   512Mi / 4600Mi
```

El límite propuesto de PostgreSQL llevaría esos valores a `2/23`, `900m/1100m`
y `1024Mi/4600Mi`, respectivamente. Cabe en la cuota observada, aunque debe
revalidarse inmediatamente antes del despliegue porque es estado mutable.

El ImageStream `openshift/postgresql` expone las líneas 10, 12, 13 y 15. El
template `openshift/postgresql-persistent` existe, pero su valor predeterminado
es PostgreSQL 10, deja `storageClassName` vacío y no define el perfil CPU
aprobado. Por ello no se aplica el template directamente; los manifiestos
versionados fijan PostgreSQL 15 y RBD de forma explícita.

PostgreSQL será infraestructura de soporte; `ms-ocp-tools` continuará siendo
una sola aplicación y un solo Deployment.

Antes de Flyway V4 se generó y validó un `pg_dump` restaurable con SHA-256
`955ab34cd91d9b268f366f45de433183ecd9f92b12f7232f15cb4ed86f36bc2c`.
V4 creó únicamente `test_case_annotation` y `test_case_flow`; conservó los 17
casos, 26 elementos, 96 asociaciones caso-deployment, 28 asociaciones de
elementos y 10 metadatos existentes.

## Demo `0.2.0` desplegada

El código de la Herramienta 02, sus migraciones, UI, configuración, RBAC y
manifiestos PostgreSQL se desplegaron como demo `0.2.0`. La UI presentó el
inventario real sensado y el usuario confirmó que la demo funciona. No se debe
reconstruir ni repetir ese despliegue durante tareas de diseño o documentación.

Evidencia posterior del 2026-08-22:

- target Docker `test` de `0.2.0` construido correctamente en la laptop;
- Secret `ms-ocp-tools-postgresql` creado en `testing-pmx3` con las tres claves
  esperadas, sin documentar sus valores;
- cuota revalidada: pods `1/23`, CPU límite `500m/1100m` y memoria límite
  `512Mi/4600Mi`;
- dry-run de PVC, Service y StatefulSet PostgreSQL: correcto;
- se detectó que el tercer namespace correcto es `testing-matrix`, no `matrix`.

La fuente fue corregida a `testing-matrix` y el target de pruebas se reconstruyó
correctamente antes de preparar la imagen utilizada por la demo.

Validaciones completadas después de la corrección:

```text
PostgreSQL StatefulSet:  ms-ocp-tools-postgresql, 1/1 Running, 0 reinicios
Service PostgreSQL:      headless ClusterIP None, puerto 5432/TCP
PVC:                     aprovisionado y utilizado por PostgreSQL
ServiceAccount:          testing-pmx3:ms-ocp-tools
Lectura deployments:     yes en testing, testing-diversificacion y testing-matrix
Patch deployments:       no
Update deployments/scale:no
Delete deployments:      no
Target Docker test:      reconstruido correctamente después de corregir namespace
```

Los gates PostgreSQL, RBAC, build y funcionamiento de la demo están completos.
El digest exacto de la imagen runtime `0.2.0` no quedó registrado en este
contexto y no debe inferirse.

## Acceso PostgreSQL desde DBeaver

El Service vigente `ms-ocp-tools-postgresql` se conserva como headless e interno
porque participa en la identidad del StatefulSet. Se creó correctamente un
segundo Service `NodePort` con puerto asignado `32609` y endpoint PostgreSQL,
pero la red no permitió alcanzarlo desde la laptop. El Service y su manifiesto
fueron retirados por decisión del usuario.

No crear una Route HTTP para PostgreSQL ni modificar el Service headless. Para
uso ocasional de DBeaver se acordó un túnel SSH hacia el servidor de accesos y,
dentro de esa sesión, `oc port-forward` hacia el Service interno. Esta solución
es temporal y permanece activa sólo mientras la sesión está abierta.

## Configuración vigente de namespaces

El despliegue `0.3.0` y el manifiesto de desarrollo `0.4.0` contienen diez
namespaces activos:

```text
OCP_MAP_NAMESPACES=agendador-test,pmx-test,security-test,testing,testing-diversificacion,testing-matrix,testing-pmx1,testing-pmx2,testing-pmx3,testing-pmx4
OCP_MAP_VISIBLE_NAMESPACES=agendador-test,pmx-test,security-test,testing,testing-diversificacion,testing-matrix,testing-pmx1,testing-pmx2,testing-pmx3,testing-pmx4
```

La primera lista define lo que el scheduler intenta sensar y la segunda controla
lo mostrado. El RBAC `get/list deployments` fue aplicado y validado en los diez.
`listanegra-test` podrá agregarse nuevamente cuando exista permiso para crear y
consultar Role y RoleBinding en ese namespace.
