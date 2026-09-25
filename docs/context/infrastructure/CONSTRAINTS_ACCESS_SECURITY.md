# Restricciones, accesos y seguridad

Fecha de vigencia: 2026-09-24

## Restricciones de red

- Los pods de OpenShift no tienen acceso a Internet.
- La imagen debe contener Java, la aplicación, dependencias, driver JDBC y
  recursos web.
- El runtime solo debe conectarse a destinos internos autorizados.
- La implementación no puede depender de CDNs, `unpkg.com`, registries públicos
  ni descargas en tiempo de ejecución.
- Los mockups autónomos pueden usar recursos externos únicamente como referencia
  visual; la implementación debe sustituirlos por recursos locales.
- La laptop corporativa puede abrir la Route y dispone actualmente del cliente
  `oc`; el usuario debe hacer login interactivo antes de cada bloque de
  validaciones. El servidor de accesos continúa disponible como alternativa.

## Build y certificados corporativos

Docker Desktop pudo acceder a Maven Central después de incorporar en la etapa
builder las CA públicas corporativas:

```text
Raíz:       CN=*.dfw3.goskope.com, O=Netskope Inc.
Intermedia: CN=ca.sse-pe-prod.goskope.com, O=Integratel
```

Los certificados públicos se usan únicamente en la etapa de construcción, se
excluyen de Git y no pasan a la imagen runtime.

No se permite:

- desactivar TLS de Maven;
- usar `maven.wagon.http.ssl.insecure`, `allowall` o equivalentes;
- almacenar PFX/P12, claves privadas o certificados personales;
- tratar el certificado hoja de `repo1.maven.org` como CA corporativa.

## Excepción TLS limitada al registry

El servidor legado no confía en la CA de la Route externa del registry. El
procedimiento corporativo admite `--tls-verify=false` exclusivamente para:

```text
default-route-openshift-image-registry.apps.ocpnprod7.gp.inet
```

Esta excepción no aplica a Maven, CMS, SOAP, PostgreSQL, la Route de la
aplicación, la consola OCP ni otros registries.

## Credenciales y configuración

Valores sensibles:

- OpenShift Secret;
- nunca en código, frontend, ConfigMap, logs, contexto o historial del shell.

Valores no sensibles:

- ConfigMap;
- URLs internas, namespaces, intervalos, timeouts y catálogos.

Servicios externos agrega dos valores sensibles distintos en el Secret
`tools`: una clave AES-256-GCM Base64 de 32 bytes para cifrar el payload de la
tabla de credenciales y una clave maestra para desbloquear la gestión y abrir
el KDBX. No se deben reutilizar. La vista pública puede mostrar destinos
técnicos, pero nunca payloads cifrados, usuarios, passwords, tokens o client
secrets.

La configuración HTTP no permite guardar `Authorization`, cookies o API keys
como headers en claro; debe referenciar una credencial cifrada. Para los
destinos del monitor, `0.7.2` acepta el riesgo de
`EXTERNAL_MONITOR_TLS_VERIFY=false`: HTTPS sigue cifrado, pero no se valida la
cadena ni el nombre del certificado. Esta excepción no aplica a Maven,
PostgreSQL, SOAP CMS, registry ni clientes ajenos al monitor y puede volver a
`true` desde ConfigMap.

No documentar contraseñas SSH/OCP, tokens de `oc`, credenciales de registry,
passwords de bases de datos ni valores reales de Secrets.

### Escritura controlada en CMS

La evolución `0.3.2` utiliza una conexión JDBC normal al datasource Informix
para solicitar el reenvío posterior a `createEsb`:

```sql
UPDATE nil_trama_da_cmsnil
SET estado_env = 'P'
WHERE cfs_access_id = ?;
```

La sentencia es parametrizada, actualiza todas las coincidencias y no se expone
como endpoint SQL genérico. El usuario confirmó que la credencial actual tiene
permiso de escritura porque es la misma utilizada para esta operación desde
DBeaver; el permiso efectivo desde el pod debe validarse al desplegar. La
integración JTA permanece deshabilitada y la operación individual usa
auto-commit.

## Proyecto y prevención de errores operativos

Antes de cualquier operación real:

```bash
oc project testing-pmx3
oc project
```

La segunda salida debe confirmar `testing-pmx3`. El servidor de acceso puede
operar diferentes proyectos; su nombre o inventario no determina el namespace
objetivo.

## Permisos vigentes conocidos

El preflight confirmó que el usuario observado podía crear o modificar los
recursos principales dentro de `testing-pmx3`, incluidos Deployments, Services,
Routes, ConfigMaps, Secrets e ImageStreams.

Se confirmó previamente creación de Roles y RoleBindings en los dos primeros
namespaces; el tercero debe revalidarse con su nombre corregido:

```text
testing
testing-diversificacion
testing-matrix  # pendiente de revalidación
```

Corrección operativa: un primer dry-run usó por error `matrix` y recibió
`Forbidden`. No se creó RBAC allí. La fuente vigente usa `testing-matrix`; el
RBAC real fue creado y la lectura con el ServiceAccount respondió `yes` en los
tres namespaces. Las pruebas de `patch`, `update deployments/scale` y `delete`
respondieron `no`.

Los namespaces de la Herramienta 02 serán parametrizables; cada namespace nuevo
deberá tener autorización explícita.

## Validación runtime pendiente del ServiceAccount

Objetivo: permitir únicamente `get` y `list` sobre `deployments.apps` en los
namespaces configurados. La beta no necesita `watch`, lectura de Secrets ni
verbos de modificación.

La validación de permisos del usuario ya se completó con respuestas `yes` para:

```bash
oc auth can-i create serviceaccounts -n testing-pmx3
oc auth can-i create roles.rbac.authorization.k8s.io -n testing
oc auth can-i create rolebindings.rbac.authorization.k8s.io -n testing
oc auth can-i create roles.rbac.authorization.k8s.io -n testing-diversificacion
oc auth can-i create rolebindings.rbac.authorization.k8s.io -n testing-diversificacion
oc auth can-i create roles.rbac.authorization.k8s.io -n testing-matrix
oc auth can-i create rolebindings.rbac.authorization.k8s.io -n testing-matrix
```

Después de aplicar `ocp/ocp-map-rbac.yaml`, validar la
identidad técnica:

```bash
oc auth can-i list deployments.apps \
  --as=system:serviceaccount:testing-pmx3:ms-ocp-tools \
  -n testing

oc auth can-i list deployments.apps \
  --as=system:serviceaccount:testing-pmx3:ms-ocp-tools \
  -n testing-diversificacion

oc auth can-i list deployments.apps \
  --as=system:serviceaccount:testing-pmx3:ms-ocp-tools \
  -n testing-matrix
```

La suplantación mediante `--as` también puede estar restringida para el usuario
humano. Si el comando no está autorizado, un administrador deberá validar el
RBAC o se hará una prueba controlada desde un pod con el ServiceAccount, sin
mostrar su token.

## Diseño RBAC recomendado

- ServiceAccount dedicado `ms-ocp-tools` en `testing-pmx3`.
- Role mínimo en cada namespace objetivo.
- RoleBinding en cada namespace, apuntando al ServiceAccount de
  `testing-pmx3`.
- Sin permisos `create`, `update`, `patch`, `delete` o `scale`.
- Un namespace agregado a configuración no será consultable hasta que reciba su
  RoleBinding correspondiente.

## Consola web de OpenShift

Los enlaces de la Herramienta 02 se abren en otra pestaña. La autenticación la
gestiona la propia consola:

- con sesión existente, abre el Deployment;
- sin sesión, solicita login manual.

La aplicación no debe capturar ni reutilizar las credenciales del usuario de la
consola.

## PostgreSQL aprobado para la primera beta

La configuración inicial aprobada usa PostgreSQL 15, StatefulSet de una réplica,
Service interno y PVC RBD de 1 Gi. Los manifiestos no contienen contraseñas
reales. El Secret se debe crear manualmente antes del StatefulSet.

La capacidad total disponible del pool no es visible con los permisos actuales.
Esto no se sustituye por una estimación: la prueba operativa es que el PVC quede
`Bound`. Antes de considerar la persistencia recuperable aún debe definirse y
probarse backup/restore; la existencia del PVC no equivale a backup.

El valor inicial de `POSTGRESQL_PASSWORD` quedó visible durante la creación
manual y debe considerarse comprometido. Debe rotarse desde la consola antes de
iniciar por primera vez el StatefulSet. El nuevo valor no se documenta ni se
comparte en capturas o salida de comandos.

No usar almacenamiento `emptyDir` para información compartida ni colocar la
base dentro del mismo contenedor de `ms-ocp-tools`.

## Exposición PostgreSQL aceptada para la demo

El usuario aceptó probar la publicación de PostgreSQL dentro de la red privada
para conectarse ocasionalmente con DBeaver. El Service `NodePort` se creó, pero
no tuvo conectividad desde la laptop y posteriormente fue eliminado. No se debe
crear una Route para PostgreSQL ni modificar el Service headless actual.

La alternativa vigente para la demo es un túnel SSH a `lnxsrpvmo0010` más
`oc port-forward` hacia el Service interno, activo únicamente durante la sesión.
No constituye un patrón de seguridad reutilizable para producción.
