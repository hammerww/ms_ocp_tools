# Runbook de construcción, entrega y rollback

Fecha de vigencia: 2026-08-25

## Alcance

Describe el proceso manual ya comprobado para `ms-ocp-tools`. No debe repetirse
mientras solo se diseñe o documente una herramienta. Se utiliza al preparar una
nueva versión desplegable.

El procedimiento operativo completo, con comandos parametrizados, gates,
diagnóstico y checklist de evidencia, se mantiene en
[`MANUAL_DESPLIEGUE.md`](MANUAL_DESPLIEGUE.md). Este runbook conserva el
contexto técnico, los antecedentes y las variantes específicas del proyecto.

## Baseline protegido

```text
Imagen: ms-ocp-tools:0.1.0
Digest: sha256:69326806c6abd8f13b5dfd312b468d9baf9159ab5528cce5c07ff40212d3984b
```

La primera entrega de la Herramienta 02 se realizó como demo `0.2.0`. No
sobrescribir `0.1.0` ni repetir el despliegue `0.2.0` durante tareas de diseño,
documentación o mockup.

## Flujo comprobado

```text
Docker Desktop corporativo
  → build linux/amd64 con CA corporativa
  → pruebas Maven
  → imagen runtime sin provenance
  → smoke test local
  → docker save
  → checksum SHA-256
  → SCP al servidor de accesos
  → validación del checksum
  → podman load
  → tag para registry externo
  → login/push
  → ImageStreamTag
  → actualización de manifiestos
  → dry-run server-side
  → rollout
  → health, UI, logs y pruebas funcionales
```

La combinación `docker build --provenance=false` y `docker save` fue compatible
con Podman 1.6.4. No fue necesario reconstruir ni instalar Maven en el servidor.

Evidencia del artefacto `0.1.0` transportado:

```text
Archivo:  ms-ocp-tools-0.1.0.tar
Tamaño:   179672064 bytes
SHA-256:  8FC879CE780FB6B418458B003DF7CDE09BB3162C23BC9B5C4768BAFDCCEDA87C
Podman:   localhost/ms-ocp-tools:0.1.0
Image ID: c5817cacb204
```

Estos valores son evidencia histórica del baseline, no parámetros para una
nueva versión.

## Construcción de pruebas

Desde `ms-ocp-tools/`, después de disponer de las CA públicas autorizadas en
`certs/`:

```powershell
docker build `
  --platform linux/amd64 `
  --target test `
  --progress=plain `
  -t ms-ocp-tools-test:<version> `
  .
```

No publicar la imagen con target `test`.

## Imagen runtime

```powershell
docker build `
  --platform linux/amd64 `
  --provenance=false `
  --progress=plain `
  -t ms-ocp-tools:<version> `
  .
```

Validar `/`, `/q/health/live` y `/q/health/ready` antes de exportar.

## Transporte

```powershell
docker save -o ms-ocp-tools-<version>.tar ms-ocp-tools:<version>
Get-FileHash -Algorithm SHA256 ms-ocp-tools-<version>.tar
```

En el servidor:

```bash
sha256sum -c ms-ocp-tools-<version>.tar.sha256
podman load -i ms-ocp-tools-<version>.tar
```

Registrar el nombre exacto que devuelve `podman load` antes de etiquetar.

## Publicación

```text
Push externo:
default-route-openshift-image-registry.apps.ocpnprod7.gp.inet/
  testing-pmx3/ms-ocp-tools:<version>

Referencia del Deployment:
image-registry.openshift-image-registry.svc:5000/
  testing-pmx3/ms-ocp-tools:<version>
```

La opción `--tls-verify=false` solo está autorizada para la Route externa del
registry indicada en el contexto de restricciones.

## Despliegue

1. Verificar cluster y proyecto.
2. Respaldar ConfigMap y manifiesto vigente.
3. Confirmar que los Secrets requeridos existen sin mostrar sus valores.
4. Actualizar la referencia de imagen versionada.
5. Ejecutar `oc apply --dry-run=server`.
6. Aplicar únicamente los recursos revisados.
7. Observar el rollout y eventos.
8. Validar health, Route, UI, logs y regresión.

Recursos de aplicación actuales:

```bash
oc apply -f ocp/imagestream.yaml
oc apply -f ocp/configmap.yaml
oc apply -f ocp/service.yaml
oc apply -f ocp/route.yaml
oc apply -f ocp/deployment.yaml

oc rollout status deployment/ms-ocp-tools
oc get pods -l app.kubernetes.io/name=ms-ocp-tools
oc get service ms-ocp-tools
oc get route ms-ocp-tools
oc logs deployment/ms-ocp-tools --tail=200
```

### Orden histórico aplicado para `0.2.0`

Esta sección conserva el procedimiento aplicado como referencia. Los recursos
ya están desplegados y no deben recrearse mientras sólo se revisa código o se
diseña la siguiente evolución.

1. crear manualmente el Secret `ms-ocp-tools-postgresql` desde
   `ocp/postgresql-secret.example.yaml`, reemplazando el marcador sin guardar el
   valor real en archivos o historial;
2. validar y crear PVC, Service y StatefulSet PostgreSQL;
3. esperar PVC `Bound` y PostgreSQL listo;
4. aplicar el ServiceAccount/RBAC y ejecutar pruebas positivas y negativas;
5. aplicar ConfigMap y Deployment `0.2.0`;
6. ejecutar pruebas de Herramienta 02 y regresión completa de Herramienta 01.

```bash
oc project testing-pmx3
oc project

oc apply --dry-run=server -f ocp/postgresql-pvc.yaml
oc apply --dry-run=server -f ocp/postgresql-service.yaml
oc apply --dry-run=server -f ocp/postgresql-statefulset.yaml
oc apply --dry-run=client -f ocp/ocp-map-rbac.yaml

oc apply -f ocp/postgresql-pvc.yaml
oc apply -f ocp/postgresql-service.yaml
oc apply -f ocp/postgresql-statefulset.yaml
oc get pvc ms-ocp-tools-postgresql
oc rollout status statefulset/ms-ocp-tools-postgresql

oc apply -f ocp/ocp-map-rbac.yaml
oc auth can-i list deployments.apps --as=system:serviceaccount:testing-pmx3:ms-ocp-tools -n testing
oc auth can-i list deployments.apps --as=system:serviceaccount:testing-pmx3:ms-ocp-tools -n testing-diversificacion
oc auth can-i list deployments.apps --as=system:serviceaccount:testing-pmx3:ms-ocp-tools -n testing-matrix
oc auth can-i patch deployments.apps --as=system:serviceaccount:testing-pmx3:ms-ocp-tools -n testing
oc auth can-i update deployments/scale.apps --as=system:serviceaccount:testing-pmx3:ms-ocp-tools -n testing
oc auth can-i delete deployments.apps --as=system:serviceaccount:testing-pmx3:ms-ocp-tools -n testing

oc apply -f ocp/configmap.yaml
oc apply -f ocp/deployment.yaml
oc rollout status deployment/ms-ocp-tools
```

### Evolución desplegada `0.4.0`

No recrea PostgreSQL, PVC, Secret, Service, StatefulSet, Route ni RBAC, y no
repite el despliegue `0.3.0`. Esta entrega no agrega migraciones; actualiza el
ConfigMap con `CMS_POST_REGISTER_UPDATE_DELAY=1S` y `TCP_CHECK_TIMEOUT=10S`,
integra la escritura CMS de `0.3.2` y agrega Validación TCP / Conectividad.

Antes del rollout:

```bash
oc project testing-pmx3
grep 'CMS_POST_REGISTER_UPDATE_DELAY' ocp/configmap.yaml
grep 'TCP_CHECK_TIMEOUT' ocp/configmap.yaml
oc apply --dry-run=server -f ocp/configmap.yaml
oc apply --dry-run=server -f ocp/deployment.yaml
```

Después se actualizan configuración e imagen y se observa explícitamente el
arranque sin errores:

```bash
oc apply --dry-run=server -f ocp/configmap.yaml
oc apply --dry-run=server -f ocp/deployment.yaml
oc apply -f ocp/configmap.yaml
oc apply -f ocp/deployment.yaml
oc rollout status deployment/ms-ocp-tools
oc logs deployment/ms-ocp-tools --tail=250
```

Validar `/`, `/admin`, los cuatro endpoints públicos de solo lectura, los diez
namespaces, la regresión completa de Validación CMS / Ambiente y Validación TCP
contra un puerto conectado, uno rechazado y un destino que llegue al timeout.
Con intervalo de cinco segundos, esperar al menos cincuenta segundos para
observar una vuelta completa del sensor.

El RBAC se valida primero con `--dry-run=client` y con `oc auth can-i` en cada
namespace. En este clúster, un `--dry-run=server` sobre el archivo multi-documento
puede devolver `Role not found`: el Role simulado no se persiste para que el
RoleBinding siguiente lo encuentre. El `apply` real sí procesa y persiste el
Role antes de su RoleBinding.

Las tres validaciones `list` deben responder `yes`; `patch`, `update .../scale`
y `delete` deben responder `no`. Si la suplantación `--as` no está autorizada
para el usuario humano, validar desde un pod controlado con el ServiceAccount.

Para apagar o encender PostgreSQL sin eliminar PVC ni datos:

```bash
oc scale statefulset/ms-ocp-tools-postgresql --replicas=0
oc scale statefulset/ms-ocp-tools-postgresql --replicas=1
```

Con PostgreSQL en cero, la Herramienta 01 debe seguir respondiendo; la
Herramienta 02 mostrará su inventario como no disponible hasta que la base
vuelva y termine el reintento automático.

La incorporación de PostgreSQL y RBAC ya tiene manifiestos propios. Deben
aplicarse y validarse en el orden documentado para `0.2.0`; no forman parte del
baseline `0.1.0` y no deben aplicarse durante tareas sólo documentales.

### Próxima evolución `0.5.0`

Esta entrega preserva la estructura pública y administrativa de `0.4.0` y agrega
inventario completo, clonación, anotaciones y diagramas de flujo. Antes de
actualizar ConfigMap, RBAC o Deployment debe generarse el respaldo restaurable:

```powershell
pwsh -File scripts/backup-ocp-map-db.ps1
```

El script no imprime credenciales: ejecuta `pg_dump` dentro del pod PostgreSQL,
valida el catálogo con `pg_restore`, copia el archivo a
`artifacts/database-backups/`, calcula SHA-256 y conserva conteos base. No se
continúa si falta alguno de esos artefactos o si el pod no está Ready.

La migración `V4__test_case_notes_and_flows.sql` es aditiva y no modifica las
tablas existentes. Después del rollout se comparan los conteos base, se valida
que Flyway registró V4 y se prueban clonación, anotaciones y persistencia del
flujo con datos reales.

Si Docker no confía en la CA corporativa del route público del registry, no se
modifica la configuración global de Docker Desktop. Se prepara un contexto
binario mínimo con `ocp/Dockerfile.binary` y `target/quarkus-app`, y se publica
mediante un BuildConfig temporal de OpenShift. El build debe apuntar a
`ms-ocp-tools:0.5.0` y finalizar en `Complete` antes de cualquier rollout.

## Rollback

El rollback incluye imagen y configuración compatible:

```bash
oc rollout history deployment/ms-ocp-tools
oc rollout undo deployment/ms-ocp-tools --to-revision=<REVISION>
```

Restaurar también el ConfigMap respaldado si la versión anterior no es
compatible con la configuración nueva.

PostgreSQL requerirá un procedimiento independiente de backup/restore; un
rollback de la aplicación no debe ejecutar automáticamente cambios destructivos
sobre datos.

## Destino futuro

Los Dockerfiles y manifiestos deben permanecer reproducibles y migrables a:

```text
Bitbucket → pipeline → registry → repositorio GitOps → Argo CD
```

Las ediciones manuales hechas en la consola deben reflejarse en la fuente de
verdad mientras no exista GitOps.
