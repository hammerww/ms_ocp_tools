# OCP Tools — plataforma operativa

Aplicación web modular que conserva la validación CMS/Ambiente y agrega el
Mapa operativo OCP como herramienta de visibilidad de Deployments y casos de
prueba.

## Contexto vigente

El contexto técnico y funcional se mantiene separado por plataforma,
infraestructura y herramienta. Antes de realizar cambios, leer el índice:

[`docs/context/README.md`](docs/context/README.md)

Ese índice y los contratos enlazados son la fuente de verdad cuando una sección
histórica de este README describa un estado anterior del proceso.

El procedimiento manual completo para construir, transportar, publicar,
desplegar, validar y revertir una versión se encuentra en
[`docs/context/infrastructure/MANUAL_DESPLIEGUE.md`](docs/context/infrastructure/MANUAL_DESPLIEGUE.md).

La versión `0.7.1` está desplegada y operativa en `testing-pmx3`. Conserva las
funciones de `0.7.0` —incluidos los flujos secuenciales, consultas anidadas,
JSON, búsqueda de casos por Deployment y Servicios externos— y corrige KDBX,
diferencia comprobaciones manuales y programadas, agrega `Historial y KPI` y
activa el monitor automático cada 10 minutos. Su contrato completo está en
[`docs/context/tools/tool-04-external-services/CONTEXT.md`](docs/context/tools/tool-04-external-services/CONTEXT.md).
Flyway V6 se aplicó de forma aditiva y conserva servicios, credenciales,
ejecuciones y resultados. La imagen Linux `amd64` superó 39 pruebas y fue
publicada con digest `sha256:24122926f0b929d63982ce1b384e489a87a7ebdd5781fba0ab75ff154421913e`.
El primer ciclo programado procesó los 28 servicios en 13,017 segundos.
`0.7.0`, acompañado por su ConfigMap con scheduler deshabilitado, es el rollback
de aplicación recomendado.

La versión `0.7.2` está implementada sólo en fuente y todavía no se ha
desplegado. Agrega HTTPS sin validación de certificados para el monitor
externo, KPI por rango/servicio, selector personalizado de hasta 90 días,
máximo de 40 bloques y exportación ZIP con `resumen.csv` y `ejecuciones.csv`.
El target Docker `test` superó 43 pruebas y la imagen runtime local se construyó
correctamente para `linux/amd64`; ninguna imagen `0.7.2` se ha publicado ni
desplegado.
No contiene migraciones de base de datos; su rollback futuro es imagen y
ConfigMap `0.7.1`.

## Decisiones de la beta

- Java 17 y Quarkus en modo JVM.
- Una aplicación y un Deployment.
- Proyecto OpenShift: `testing-pmx3`.
- Una réplica y estrategia `Recreate` mientras `createEsb` no tenga una restricción única confirmada.
- Timeout máximo de 5 segundos por operación externa, salvo la conexión de la
  utilidad TCP, aprobada expresamente con 10 segundos.
- Sin reintentos automáticos de `createEsb`.
- Build y pruebas mediante Docker Desktop corporativo; transporte de la imagen
  `linux/amd64` al servidor y carga con Podman 1.6.4.
- No se instalan Java, Maven ni clientes Informix en la laptop corporativa.

## Flujo

```text
AccessID + ambiente
        |
        v
CMS Informix: registro más reciente
        |
        +-- no existe --> CMS_NOT_FOUND
        |
        v
queryESb(PID, EXECID)
        |
        +-- encontrado --> mostrar ambiente real y bloquear registro
        |
        +-- ORA-01403 --> permitir registro explícito
        |
        +-- ORA-01422/error/timeout --> bloquear registro
                                     |
                                     v
                             createEsb
                                     |
                                     v
                          esperar 1 segundo
                                     |
                                     v
                     CMS: estado_env = 'P'
                                     |
                                     v
                             queryESb de verificación
```

## Endpoints de OCP Tools

```http
GET  /api/v1/cms/environments
POST /api/v1/cms/validate
POST /api/v1/cms/register
POST /api/v1/tcp-check
GET  /q/health/live
GET  /q/health/ready
GET  /q/swagger-ui
```

Mapa operativo OCP:

```http
GET  /api/v1/ocp-map/snapshot
PUT  /api/v1/ocp-map/deployments/{namespace}/{name}/testing-mark
POST /api/v1/ocp-map/deployments/{namespace}/{name}/testing-mark/remove
```

Servicios externos:

```http
GET  /api/v1/external-services/snapshot
GET  /api/v1/external-services/history?hours=24[&serviceId=15]
GET  /api/v1/external-services/history?from=...&to=...[&serviceId=15]
GET  /api/v1/external-services/history/export.zip?...rango y filtro...
POST /api/v1/external-services/admin/unlock
POST /api/v1/external-services/admin/services
PUT  /api/v1/external-services/admin/services/{id}
POST /api/v1/external-services/admin/services/{id}/run
GET  /api/v1/external-services/admin/export.kdbx
```

Los endpoints bajo `/admin/` requieren un token efímero obtenido con la clave
maestra. La vista general y el snapshot son públicos dentro de la Route, pero
no contienen secretos.

API pública JSON de solo lectura incorporada en `0.3.0`:

```http
GET /api/v1/ocp-map/deployments
GET /api/v1/ocp-map/test-cases
GET /api/v1/ocp-map/elements
GET /api/v1/ocp-map/export
GET /api/v1/ocp-map/test-cases/{id}/flow
```

Estos endpoints son JSON y de solo lectura. La gestión se realiza mediante
la UI `/admin` del mismo artefacto y Route; sus operaciones HTTP de escritura
serán internas a la UI y no formarán parte de la API pública.

La vista operativa no mostrará la etiqueta `BETA` ni un acceso visible a
`/admin`; la ruta administrativa se abrirá escribiéndola manualmente.

La administración permite crear, editar, clonar y archivar casos. El código de
un caso nuevo comienza con el último código guardado, pero el incremento queda
deliberadamente a cargo del usuario. Las anotaciones admiten texto extenso. Los
diagramas se editan en `/admin/flow?caseId={id}` y se visualizan, con animación
automática e informativa, en `/flow?caseId={id}`. El visor público no ejecuta
acciones sobre sistemas u OpenShift y no exporta imágenes o GIF.

`OCP_MAP_NAMESPACES` controla la lista sensada y
`OCP_MAP_VISIBLE_NAMESPACES` el subconjunto mostrado en UI y API pública. Si la
segunda variable está vacía, se muestran todos los namespaces sensados. Los
cambios de ConfigMap requieren reiniciar el pod porque llegan mediante
`envFrom`.

El snapshot se responde desde PostgreSQL; no dispara consultas OCP. Un
scheduler ejecuta `list deployments` secuencialmente, un namespace por ciclo.
La Herramienta 02 devuelve `503` si su base aún no está disponible, sin afectar
los endpoints CMS ni la salud global de la aplicación.

Request de consulta y registro:

```json
{
  "accessId": "1400068580",
  "environment": "PMX3"
}
```

El endpoint de registro vuelve a consultar CMS y `queryESb`; no confía en PID, EXECID ni External ID enviados por el navegador.

## Configuración

Valores no sensibles en `ocp/configmap.yaml`:

- URL JDBC de Informix.
- URLs SOAP.
- timeouts.
- retraso posterior a `createEsb` antes de solicitar el reenvío CMS.
- timeout de 10 segundos de la utilidad TCP.
- tamaño máximo del pool.
- mapeo de ambientes a subsistemas.
- URL JDBC y pool del inventario PostgreSQL.
- namespaces sensados y visibles, intervalo de sensado, timeouts OCP y URL base
  de la consola. `diagnosticador-test` forma parte de la configuración de la
  evolución `0.5.0` y requiere su Role/RoleBinding de lectura antes del despliegue.
- monitor externo habilitado y ciclo programado cada 10 minutos. Los servicios
  se procesan con paralelismo limitado y las pruebas de cada servicio respetan
  su orden y dependencias. `EXTERNAL_MONITOR_TLS_VERIFY=false` mantiene HTTPS
  cifrado, pero omite cadena y nombre del certificado en los destinos
  monitoreados; se puede volver a `true` desde ConfigMap.

Valores sensibles requeridos en el Secret `tools`:

```text
CMS_DB_USER
CMS_DB_PASSWORD
EXTERNAL_CREDENTIAL_ENCRYPTION_KEY
EXTERNAL_KDBX_MASTER_PASSWORD
```

Las dos claves externas deben ser distintas. La primera es Base64 de 32 bytes
y cifra credenciales con AES-256-GCM; la segunda desbloquea la gestión durante
15 minutos y protege el KDBX exportado.

La plantilla `ocp/secret.example.yaml` no contiene valores reales. Para la demo
se creó el Secret `tools` desde la consola web de OpenShift dentro de
`testing-pmx3`, evitando exponer la contraseña en el historial del shell.

PostgreSQL usa un Secret separado `ms-ocp-tools-postgresql` con las claves:

```text
POSTGRESQL_USER
POSTGRESQL_PASSWORD
POSTGRESQL_DATABASE
```

La plantilla `ocp/postgresql-secret.example.yaml` sólo contiene marcadores. El
PVC inicial es de 1 Gi sobre `ocs-storagecluster-ceph-rbd`; puede expandirse y
no debe confundirse con una copia de respaldo.

## Versiones iniciales

```text
Java:                 17
Quarkus LTS:          3.33.3.1
Informix JDBC Driver: 4.50.14
```

Estas versiones son una base provisional. Antes del primer build se debe inventariar Java y las imágenes base de microservicios corporativos comparables. Quarkus 3.33 requiere Java 17 o superior; si el estándar autorizado fuera Java 11, será necesario cambiar el baseline de Quarkus o gestionar una imagen Java 17 aprobada.

La coordenada Maven del driver es:

```text
com.ibm.informix:jdbc:4.50.14
```

OCP no tiene acceso a Internet. El build no debe depender de Maven Central ni de registries públicos. Antes de construir se debe confirmar que el driver y las dependencias de Quarkus estén disponibles en un repositorio Maven corporativo, en una caché Maven aprobada o dentro de un paquete offline autorizado. Si la empresa proporciona otra versión del driver, modificar la propiedad `informix.jdbc.version` del `pom.xml`.

## Restricción de red y alternativas offline

Los pods de OCP no necesitan Internet para ejecutar la aplicación: la imagen debe contener el runtime, el código y todas las librerías. Las URLs de Informix y SOAP son internas.

La construcción, en cambio, necesita resolver dos grupos de artefactos:

1. imágenes base de build y runtime;
2. dependencias Maven, incluido el driver Informix.

Opciones admitidas:

- usar imágenes base del registry interno y un mirror Maven corporativo configurado mediante `settings.xml`;
- usar una caché Maven offline previamente aprobada y completa;
- recibir `target/quarkus-app` ya construido desde otro entorno autorizado y crear únicamente la imagen runtime en el servidor de accesos.

La tercera opción utiliza `Dockerfile.runtime`. Se debe copiar el directorio completo, no solamente `quarkus-run.jar`:

```text
artifact/
+-- quarkus-app/
    +-- app/
    +-- lib/
    +-- quarkus/
    +-- quarkus-run.jar
```

Construcción runtime sin Maven:

```bash
podman build \
  -f Dockerfile.runtime \
  --build-arg RUNTIME_IMAGE="$RUNTIME_IMAGE" \
  -t "$APP_IMAGE" .
```

Esta modalidad solo requiere que la imagen base runtime ya esté disponible desde un registry accesible por el servidor.

### Nginx no es una dependencia de OCP Tools

El frontend de Materiales utiliza una imagen Nginx separada, pero OCP Tools entrega su HTML, CSS y JavaScript directamente desde Quarkus en el mismo contenedor. No se debe agregar Nginx, Node.js ni npm a la imagen de OCP Tools salvo que la arquitectura cambie explícitamente a un frontend separado.

Los resultados del servidor confirmaron estas imágenes Java ya disponibles localmente:

```text
registry.access.redhat.com/ubi9/openjdk-21:latest
registry.access.redhat.com/ubi8/openjdk-17:1.16-1
registry.access.redhat.com/ubi8/openjdk-11:1.11
```

La imagen UBI8 OpenJDK 17 fue comprobada y contiene OpenJDK 17.0.7 y Maven 3.8.5:

```bash
podman run --rm registry.access.redhat.com/ubi8/openjdk-17:1.16-1 java -version
podman run --rm registry.access.redhat.com/ubi8/openjdk-17:1.16-1 mvn -version
```

Estos comandos usan la imagen local y no requieren Internet. Maven está disponible, pero Quarkus 3.33 documenta Maven 3.9.x para su construcción. La primera compilación debe considerarse una prueba de compatibilidad. Si falla por versión de Maven, no se debe instalar o reemplazar Maven arbitrariamente dentro del servidor: se utilizará una imagen builder Maven 3.9 aprobada o se construirá la imagen completa en otro entorno autorizado y se transportará mediante `save/load`.

## Cómo inventariar Java en otros microservicios

Ejecutar estas verificaciones desde el servidor de accesos después de iniciar sesión y seleccionar el proyecto correcto. Primero listar Deployment e imagen:

```bash
oc get deployments -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{range .spec.template.spec.containers[*]}{.name}{"="}{.image}{" "}{end}{"\n"}{end}'
```

Elegir un pod del microservicio comparable:

```bash
oc get pods
oc exec <pod> -c <contenedor> -- java -version
```

`java -version` informa el runtime real dentro del contenedor y es la evidencia principal. Si el contenedor no incluye `java`, puede tratarse de un ejecutable nativo o de una imagen con un PATH no estándar.

También revisar la imagen configurada y las variables disponibles:

```bash
oc get deployment <deployment> -o jsonpath='{.spec.template.spec.containers[*].image}{"\n"}'
oc set env deployment/<deployment> --list
```

En las carpetas históricas del servidor, revisar además:

```bash
grep -i '^FROM' Dockerfile*
unzip -p <aplicacion.jar> META-INF/MANIFEST.MF | grep -Ei 'Build-Jdk|Created-By'
```

La información debe anotarse en una tabla:

```text
Microservicio | Framework/versión | Imagen base | java -version | Build-Jdk | Tipo JVM/nativo
```

Conviene comparar primero microservicios recientes del mismo entorno y tecnología. No se debe copiar automáticamente Java 11 de una aplicación antigua ni adoptar Java 21 si todavía no existe una imagen corporativa aprobada.

## Construcción en el servidor de accesos

### Certificado corporativo para Maven

La primera construcción con Docker Desktop confirmó acceso de red a Maven
Central, pero Maven terminó en `dependency:go-offline` con:

```text
PKIX path building failed
unable to find valid certification path to requested target
```

Esto significa que la JVM de la imagen no confía todavía en la CA corporativa
que firma o inspecciona la conexión HTTPS. Los avisos posteriores sobre
versiones de dependencias Quarkus son efectos en cascada: primero debe
resolverse la confianza TLS, sin modificar todavía el `pom.xml`.

Desde el navegador corporativo se deben exportar la CA raíz corporativa y, si
existe, su CA intermedia en formato **X.509 Base-64**, asignarles extensión
`.crt` y copiarlas en `certs/`. El `Dockerfile` las agrega al almacén de
confianza de la etapa de construcción. Los certificados están excluidos de Git
y no pasan a la imagen final.

No se debe seleccionar el certificado inferior de la ruta cuyo sujeto sea
`CN=repo1.maven.org`: ese es el certificado hoja temporal del sitio. En la
laptop analizada, las CA confiables están instaladas en Windows con estos
sujetos:

```text
Raíz:      CN=*.dfw3.goskope.com, O=Netskope Inc.
Intermedia: CN=ca.sse-pe-prod.goskope.com, O=Integratel
```

No se debe desactivar la validación SSL de Maven ni usar opciones como
`maven.wagon.http.ssl.insecure` o `allowall`. Tampoco se deben copiar PFX/P12,
certificados personales o claves privadas.

Primera validación en la laptop, una vez copiados los `.crt`:

```powershell
Get-ChildItem .\certs\*.crt

docker build `
  --platform linux/amd64 `
  --target test `
  --progress=plain `
  -t ms-ocp-tools-test:0.1.0 `
  .
```

No construir todavía la imagen final ni publicarla hasta que este objetivo de
pruebas termine correctamente.

La validación realizada el 22 de agosto de 2026 terminó correctamente:

```text
Dependencias Maven: BUILD SUCCESS
Fuentes compiladas: 21, release Java 17
Pruebas: 4 ejecutadas, 0 fallos, 0 errores, 0 omitidas
Maven del builder: 3.8.5, compatible con esta construcción concreta
```

Para construir la imagen runtime destinada al servidor con Podman 1.6.4, se
desactiva la metadata de procedencia moderna de BuildKit para mejorar la
compatibilidad del archivo producido por `docker save`:

```powershell
docker build `
  --platform linux/amd64 `
  --provenance=false `
  --progress=plain `
  -t ms-ocp-tools:0.1.0 `
  .
```

Esta imagen se debe validar localmente y luego exportar; la imagen
`ms-ocp-tools-test:0.1.0` no es la imagen runtime que se publicará.

Antes de construir, ejecutar el diagnóstico de solo lectura:

```bash
bash scripts/preflight-server.sh testing-pmx3 | tee preflight-testing-pmx3.txt
```

El script no inicia sesión en el registry, no muestra el token, no crea recursos y no publica imágenes. Revisa herramientas, versiones, proyecto, permisos, registry, ImageStreams, imágenes locales, configuración Maven, caché y espacio disponible.

### Qué significa “subir la imagen”

La imagen construida con Podman existe inicialmente solo en el almacenamiento local del servidor de accesos. OpenShift no puede usarla desde allí. Es necesario publicarla en un registry accesible por el cluster:

```text
Código o artifact/quarkus-app
          |
          v
podman build en servidor de accesos
          |
          v
imagen local ms-ocp-tools:0.1.0
          |
          v
oc registry login
          |
          v
podman tag + podman push
          |
          v
registry / testing-pmx3 / ms-ocp-tools:0.1.0
          |
          v
Deployment utiliza esa imagen
```

Después de que el preflight confirme el registry y los permisos, el patrón será:

```bash
oc project testing-pmx3
PUSH_REGISTRY="default-route-openshift-image-registry.apps.ocpnprod7.gp.inet"
oc registry login

podman tag ms-ocp-tools:0.1.0 \
  "$PUSH_REGISTRY/testing-pmx3/ms-ocp-tools:0.1.0"

podman push "$PUSH_REGISTRY/testing-pmx3/ms-ocp-tools:0.1.0"
```

El Deployment utiliza la referencia interna confirmada:

```text
image-registry.openshift-image-registry.svc:5000/testing-pmx3/ms-ocp-tools:0.1.0
```

Los resultados del preflight confirmaron la Route externa, la convención
interna de los Deployments y permisos para crear o actualizar ImageStreams. El
primer push de `0.1.0` terminó correctamente y creó el ImageStreamTag.

En el servidor legado se confirmó una condición preexistente: la CA de la Route
externa del registry no está instalada en la confianza de Podman. El manual
corporativo habitual autoriza para ese host concreto usar
`--tls-verify=false` tanto en el login como en el push. Esta excepción queda
limitada a:

```text
default-route-openshift-image-registry.apps.ocpnprod7.gp.inet
```

No se debe generalizar a Maven, servicios SOAP, CMS, Routes de la aplicación u
otros registries.

La imagen base que se pudo descargar y ejecutar tanto en Docker Desktop como en
el servidor es:

```text
registry.access.redhat.com/ubi8/openjdk-17:1.16-1
```

El `Dockerfile` la utiliza provisionalmente como builder y runtime. El registry
de publicación y la referencia interna de OCP ya fueron identificados; aún se
debe validar el primer push y confirmar si la empresa exige reemplazar la base
por una copia alojada en un registry corporativo.

Ejecutar pruebas dentro de la imagen de build:

```bash
podman build \
  --target test \
  --build-arg BUILD_IMAGE="$BUILD_IMAGE" \
  --build-arg RUNTIME_IMAGE="$RUNTIME_IMAGE" \
  -t ms-ocp-tools-test:0.1.0 .
```

Construir la imagen final:

```bash
podman build \
  --build-arg BUILD_IMAGE="$BUILD_IMAGE" \
  --build-arg RUNTIME_IMAGE="$RUNTIME_IMAGE" \
  -t "$APP_IMAGE" .
```

Publicar:

```bash
podman push "$APP_IMAGE"
```

No ejecutar la publicación hasta completar las pruebas, producir la imagen
final y validar la autenticación contra el registry. El acceso Maven se está
resolviendo mediante la CA corporativa; si luego aparece un mirror corporativo,
se deberá configurarlo en `settings.xml` en lugar de depender de Maven Central.

## Despliegue manual

Ingresar al cluster y verificar explícitamente el proyecto:

```bash
oc login -u <usuario> https://api.ocpnprod7.gp.inet:6443
oc project testing-pmx3
oc project
```

Antes de aplicar `deployment.yaml`, reemplazar su imagen de ejemplo por `$APP_IMAGE`.

Con el Secret ya creado en la consola web:

```bash
oc apply -f ocp/imagestream.yaml
oc apply -f ocp/configmap.yaml
oc apply -f ocp/service.yaml
oc apply -f ocp/route.yaml
oc apply -f ocp/deployment.yaml
```

Validación del despliegue:

```bash
oc rollout status deployment/ms-ocp-tools
oc get pods -l app.kubernetes.io/name=ms-ocp-tools
oc get service ms-ocp-tools
oc get route ms-ocp-tools
oc logs deployment/ms-ocp-tools --tail=200
```

## Pruebas funcionales solicitadas

1. AccessID inexistente en CMS: debe terminar en `CMS_NOT_FOUND`.
2. AccessID encontrada en CMS y en `DIVPMX1`: debe mostrar PMX1 y no mostrar el botón Registrar, aunque se haya seleccionado PMX3 o PMX4.
3. `queryESb` con `ORA-01403`: debe terminar en `ENVIRONMENT_NOT_FOUND` y habilitar el registro.
4. `queryESb` con `ORA-01422`: debe terminar en `QUERY_ERROR` y bloquear el registro.
5. Timeout de Informix o SOAP: debe finalizar como `TIMEOUT` alrededor de los 5 segundos y bloquear el registro.
6. Registro exitoso: solo debe mostrar éxito después de que la consulta posterior encuentre el mismo subsistema solicitado.
7. Timeout de `createEsb`: no debe reintentar; debe consultar y confirmar o terminar en `REGISTER_UNVERIFIED`.
8. Cambio de AccessID o ambiente después de validar: debe ocultar el botón Registrar y exigir una nueva consulta.

## Rollback

Respaldar primero el ConfigMap vigente desde la consola o con el procedimiento corporativo. Para el Deployment:

```bash
oc rollout history deployment/ms-ocp-tools
oc rollout undo deployment/ms-ocp-tools --to-revision=<REVISION>
```

El rollback de imagen debe acompañarse con la configuración compatible con esa versión.
