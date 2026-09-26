# Manual de construcción y despliegue — OCP Tools

Fecha de vigencia: 2026-09-25  
Estado: procedimiento comprobado y reutilizable mientras no exista pipeline/GitOps

## 1. Propósito

Este manual consolida el procedimiento aprendido para construir, transportar,
publicar, desplegar, validar y revertir una versión de `ms-ocp-tools`.

El flujo vigente utiliza dos equipos:

```text
Laptop corporativa
  Docker Desktop + código fuente + CA corporativa
            |
            | docker save + SHA-256 + SCP
            v
Servidor de accesos lnxsrpvmo0010
  oc 4.5.7 + Podman 1.6.4 + acceso al registry y OpenShift
            |
            v
Registry interno / ImageStream / Deployment / Route
```

La laptop construye y prueba. El servidor de accesos puede cargar la imagen,
publicarla en el registry y ejecutar las operaciones `oc`. Desde `0.7.0`
también está confirmada la publicación directa desde la laptop cuando la Route
del registry es alcanzable y existe una sesión `oc` vigente. No se instala
Maven ni se reconstruye la aplicación en el servidor.

## 2. Alcance

Este manual cubre entregas posteriores sobre infraestructura ya existente:

- nueva imagen de aplicación;
- cambios de ConfigMap;
- cambios de RBAC previamente autorizados;
- actualización del Deployment;
- validación funcional y rollback.

No autoriza automáticamente a recrear o eliminar:

- PostgreSQL;
- PVC o datos;
- Secrets;
- StatefulSet;
- Service o Route vigentes;
- ImageStream;
- Roles en namespaces que el usuario no administra.

Cuando una versión agregue una migración de base de datos, un recurso nuevo o
un cambio incompatible de configuración, debe existir un plan específico
adicional antes de aplicar esta secuencia.

## 3. Valores confirmados para OCP Tools

| Concepto | Valor vigente |
|---|---|
| Proyecto local | `C:\Users\Willyvb\Documents\CODEX_WORK\OCP_TOOLS\ms-ocp-tools` |
| Servidor | `devopstest@10.4.77.21` |
| Directorio servidor | `/home1/devopstest/ms-ocp-tools` |
| Cluster | `https://api.ocpnprod7.gp.inet:6443` |
| Proyecto OCP | `testing-pmx3` |
| Aplicación | `ms-ocp-tools` |
| ImageStream | `ms-ocp-tools` |
| Deployment | `ms-ocp-tools` |
| ConfigMap | `ms-ocp-tools-configmap` |
| Route | `ms-ocp-tools` |
| Registry para push | `default-route-openshift-image-registry.apps.ocpnprod7.gp.inet` |
| Registry usado por el pod | `image-registry.openshift-image-registry.svc:5000` |
| Plataforma de imagen | `linux/amd64` |
| Runtime | Java 17 / Quarkus JVM |
| Constructor local | Docker Desktop |
| Cargador del servidor | Podman 1.6.4 |

Para reutilizar el manual en otro proyecto se deben reemplazar explícitamente
estos valores. No se deben inferir del nombre del directorio o de la sesión OCP
que haya quedado abierta.

## 4. Reglas de entrega

1. Cada entrega usa una versión nueva e inmutable, por ejemplo `0.3.1`.
2. No reutilizar tags publicados y no utilizar `latest`.
3. No sobrescribir carpetas de releases anteriores.
4. El target Docker `test` se prueba, pero nunca se publica como runtime.
5. La imagen final se construye con `--platform linux/amd64` y
   `--provenance=false` para mantener compatibilidad con Podman 1.6.4.
6. Todo archivo se valida con SHA-256 después del SCP.
7. Antes de cualquier `apply`, confirmar dos veces el proyecto
   `testing-pmx3`.
8. Aplicar solamente los manifiestos que forman parte de la entrega.
9. No almacenar passwords, tokens, certificados privados ni valores reales de
   Secrets en el release.
10. Un error de permisos se resuelve con RBAC o retirando temporalmente el
    namespace de configuración; no se interpreta como un error de código.

## 5. Contenido esperado de un release

Ejemplo para `<version>`:

```text
release-<version>/
├── ms-ocp-tools-<version>.tar
├── ms-ocp-tools-<version>.tar.sha256
├── deployment.yaml
├── configmap.yaml
└── ocp-map-rbac.yaml
```

Los manifiestos de PostgreSQL, PVC, Service y Route se agregan únicamente si
esa entrega cambia deliberadamente esos recursos y cuenta con una validación
independiente. Un Secret real nunca forma parte del directorio.

### 5.1 Orden completo de una entrega

El orden obligatorio, antes de ejecutar los comandos detallados, es:

1. asignar una versión nueva y revisar `pom.xml`, Deployment y ConfigMap;
2. comprobar Docker Desktop, las CA públicas y la sesión local de OpenShift;
3. compilar y ejecutar pruebas dentro del target Docker `test`; esto confirma
   Java 17, dependencias, compilación y regresión antes de crear una imagen;
4. construir la imagen runtime `linux/amd64` sólo si las pruebas pasan;
5. crear el release inmutable con TAR, SHA-256 y manifiestos;
6. copiar el release al servidor de accesos y verificar el checksum;
7. en el servidor, confirmar `oc whoami`, proyecto, Podman y recursos actuales;
8. respaldar Deployment y ConfigMap vigentes;
9. validar los manifiestos con `dry-run`;
10. cargar con Podman y publicar la imagen, o usar el push directo documentado;
11. aplicar sólo los manifiestos modificados, observar el rollout y validar;
12. conservar evidencia y la versión previa para rollback.

El servidor de accesos se usa porque tiene `oc`, Podman y conectividad al
registry/cluster. No compila: recibe una imagen ya probada y sus manifiestos.

## 6. Fase A — preparar y probar en la laptop

### 6.1 Definir versión

Abrir PowerShell:

```powershell
$OcpToolsVersion = "<version>"
$OcpToolsProject = "C:\Users\Willyvb\Documents\CODEX_WORK\OCP_TOOLS\ms-ocp-tools"
$OcpToolsRelease = Join-Path $OcpToolsProject "release-$OcpToolsVersion"
Set-Location $OcpToolsProject
```

La versión debe coincidir en:

- `pom.xml`;
- tag local de Docker;
- `ocp/deployment.yaml`;
- nombre del archivo TAR;
- tag publicado en el ImageStream.

Validación rápida:

```powershell
Select-String -Path .\pom.xml -Pattern $OcpToolsVersion
Select-String -Path .\ocp\deployment.yaml -Pattern "ms-ocp-tools:$OcpToolsVersion"
```

### 6.2 Verificar Docker Desktop y certificados

```powershell
$DockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
$Docker = if ($DockerCommand) { $DockerCommand.Source } else {
    "C:\Users\Willyvb\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe"
}
if (-not (Test-Path -LiteralPath $Docker)) { throw "No se encontró docker.exe" }
& $Docker info
```

El comando debe mostrar información del servidor Docker. En esta laptop
`docker.exe` puede existir aunque no esté agregado al `PATH`; por eso se
resuelve su ruta explícita. Si informa que no existe
`dockerDesktopLinuxEngine`, iniciar Docker Desktop antes de continuar.

Comprobar las CA públicas autorizadas:

```powershell
Get-ChildItem .\certs\*.crt
```

No colocar en `certs/` PFX/P12, claves privadas, certificados personales ni el
certificado hoja temporal del sitio Maven.

### 6.3 Construir el target de pruebas

```powershell
& $Docker build `
  --platform linux/amd64 `
  --provenance=false `
  --target test `
  --progress=plain `
  -t "ms-ocp-tools-test:$OcpToolsVersion" `
  .
```

Gate obligatorio:

```text
BUILD SUCCESS
Tests run: N, Failures: 0, Errors: 0
```

Si Maven termina con `PKIX path building failed`, detener el proceso y corregir
la confianza de la CA corporativa. No desactivar SSL ni usar opciones Maven
inseguras.

### 6.4 Construir la imagen runtime

```powershell
& $Docker build `
  --platform linux/amd64 `
  --provenance=false `
  --progress=plain `
  -t "ms-ocp-tools:$OcpToolsVersion" `
  .
```

Confirmar arquitectura:

```powershell
& $Docker image inspect "ms-ocp-tools:$OcpToolsVersion" `
  --format '{{.Os}}/{{.Architecture}}'
```

Resultado requerido:

```text
linux/amd64
```

### 6.5 Crear el paquete de entrega

No continuar si la carpeta ya existe; esto protege un release preparado
anteriormente:

```powershell
if (Test-Path $OcpToolsRelease) {
    throw "El release ya existe: $OcpToolsRelease"
}
New-Item -ItemType Directory $OcpToolsRelease
```

Copiar los manifiestos revisados:

```powershell
Copy-Item .\ocp\deployment.yaml $OcpToolsRelease
Copy-Item .\ocp\configmap.yaml $OcpToolsRelease
Copy-Item .\ocp\ocp-map-rbac.yaml $OcpToolsRelease
```

Exportar la imagen:

```powershell
$OcpToolsTar = Join-Path $OcpToolsRelease "ms-ocp-tools-$OcpToolsVersion.tar"
& $Docker save --output $OcpToolsTar "ms-ocp-tools:$OcpToolsVersion"
```

Generar un checksum con terminación de línea compatible con Linux:

```powershell
$OcpToolsHash = (Get-FileHash $OcpToolsTar -Algorithm SHA256).Hash.ToLower()
$OcpToolsChecksum = Join-Path $OcpToolsRelease "ms-ocp-tools-$OcpToolsVersion.tar.sha256"
[System.IO.File]::WriteAllText(
    $OcpToolsChecksum,
    "$OcpToolsHash  ms-ocp-tools-$OcpToolsVersion.tar`n",
    [System.Text.Encoding]::ASCII
)
```

Inventariar el release:

```powershell
Get-ChildItem $OcpToolsRelease
Get-Content $OcpToolsChecksum
```

## 7. Fase B — transportar al servidor de accesos

### 7.1 Verificar el destino

En `lnxsrpvmo0010`:

```bash
OCPTOOLS_VERSION="<version>"
OCPTOOLS_SERVER_ROOT="/home1/devopstest/ms-ocp-tools"
test ! -e "$OCPTOOLS_SERVER_ROOT/release-$OCPTOOLS_VERSION" \
  && echo "Destino disponible"
```

Si no aparece `Destino disponible`, detenerse: el release ya existe y no debe
sobrescribirse.

### 7.2 Copiar desde la laptop

En PowerShell:

```powershell
scp -r $OcpToolsRelease `
  "devopstest@10.4.77.21:/home1/devopstest/ms-ocp-tools/"
```

No copiar el release a `/home1/devopstest/ocp-tools-0.2.0` ni dejar manifiestos
sueltos fuera de `/home1/devopstest/ms-ocp-tools`.

### 7.3 Validar integridad

En el servidor:

```bash
cd "$OCPTOOLS_SERVER_ROOT/release-$OCPTOOLS_VERSION"
ls -lh
sha256sum -c "ms-ocp-tools-$OCPTOOLS_VERSION.tar.sha256"
```

Resultado obligatorio:

```text
ms-ocp-tools-<version>.tar: OK
```

Si falla el checksum, no ejecutar `podman load`: repetir el SCP.

## 8. Fase C — preflight y respaldo en OpenShift

### 8.1 Confirmar identidad y proyecto

```bash
oc whoami
oc project testing-pmx3
oc project
```

La última salida debe confirmar `testing-pmx3`.

### 8.2 Confirmar recursos existentes

```bash
oc get deployment ms-ocp-tools
oc get configmap ms-ocp-tools-configmap
oc get secret tools ms-ocp-tools-postgresql
oc get statefulset ms-ocp-tools-postgresql
oc get pvc ms-ocp-tools-postgresql
```

Estos comandos verifican existencia y estado; no deben decodificar ni imprimir
valores de los Secrets.

### 8.3 Respaldar versión vigente

```bash
OCPTOOLS_BACKUP="$OCPTOOLS_SERVER_ROOT/backups/before-$OCPTOOLS_VERSION"
mkdir -p "$OCPTOOLS_BACKUP"
```

Respaldar los manifiestos canónicos del servidor:

```bash
cp -p "$OCPTOOLS_SERVER_ROOT/ocp/deployment.yaml" "$OCPTOOLS_BACKUP/deployment-source.yaml"
cp -p "$OCPTOOLS_SERVER_ROOT/ocp/configmap.yaml" "$OCPTOOLS_BACKUP/configmap-source.yaml"
cp -p "$OCPTOOLS_SERVER_ROOT/ocp/ocp-map-rbac.yaml" "$OCPTOOLS_BACKUP/ocp-map-rbac-source.yaml"
```

Guardar también evidencia de lo que está realmente desplegado:

```bash
oc get deployment ms-ocp-tools -o yaml > "$OCPTOOLS_BACKUP/deployment-live.yaml"
oc get configmap ms-ocp-tools-configmap -o yaml > "$OCPTOOLS_BACKUP/configmap-live.yaml"
```

### 8.4 Revisar y validar los manifiestos del release

```bash
cd "$OCPTOOLS_SERVER_ROOT"
grep "ms-ocp-tools:$OCPTOOLS_VERSION" "release-$OCPTOOLS_VERSION/deployment.yaml"
grep 'OCP_MAP_NAMESPACES' "release-$OCPTOOLS_VERSION/configmap.yaml"
```

Validar localmente con el cliente:

```bash
oc apply --dry-run=client -f "release-$OCPTOOLS_VERSION/ocp-map-rbac.yaml"
oc apply --dry-run=client -f "release-$OCPTOOLS_VERSION/configmap.yaml"
oc apply --dry-run=client -f "release-$OCPTOOLS_VERSION/deployment.yaml"
```

Validar contra el servidor:

```bash
oc apply --dry-run=server -f "release-$OCPTOOLS_VERSION/configmap.yaml"
oc apply --dry-run=server -f "release-$OCPTOOLS_VERSION/deployment.yaml"
```

Para un archivo RBAC multi-documento, el dry-run server-side puede informar
`Role not found` para el RoleBinding porque el Role simulado no se persiste. En
ese caso usar el dry-run client-side, revisar los namespaces individualmente y
comprobar permisos con `oc auth can-i`. Un `Forbidden` real no se ignora.

## 9. Fase D — cargar y publicar la imagen

### 9.1 Cargar el Docker archive en Podman

```bash
podman load -i "release-$OCPTOOLS_VERSION/ms-ocp-tools-$OCPTOOLS_VERSION.tar"
```

Registrar el nombre exacto mostrado por `podman load` y validar:

```bash
podman image inspect "ms-ocp-tools:$OCPTOOLS_VERSION" \
  --format '{{.Os}}/{{.Architecture}}'
```

Debe devolver `linux/amd64`.

### 9.2 Autenticarse y etiquetar

```bash
OCPTOOLS_PUSH_REGISTRY="default-route-openshift-image-registry.apps.ocpnprod7.gp.inet"
oc registry login
```

```bash
podman tag "ms-ocp-tools:$OCPTOOLS_VERSION" \
  "$OCPTOOLS_PUSH_REGISTRY/testing-pmx3/ms-ocp-tools:$OCPTOOLS_VERSION"
```

### 9.3 Publicar

```bash
podman push --tls-verify=false \
  "$OCPTOOLS_PUSH_REGISTRY/testing-pmx3/ms-ocp-tools:$OCPTOOLS_VERSION"
```

`--tls-verify=false` está autorizado únicamente para esta Route externa del
registry en el servidor legado. No se reutiliza para Maven, la aplicación,
PostgreSQL u otros destinos.

Confirmar el tag:

```bash
oc get imagestreamtag "ms-ocp-tools:$OCPTOOLS_VERSION"
```

No aplicar el Deployment si el push o la consulta del ImageStreamTag fallan.

### 9.4 Alternativa confirmada: push directo desde la laptop

Usar esta ruta sólo si la laptop resuelve la Route del registry, alcanza el
puerto 443 y `oc whoami` confirma una sesión vigente. Guardar la autenticación
en un directorio temporal para no alterar el archivo Docker permanente:

```powershell
$OcpToolsRegistry = "default-route-openshift-image-registry.apps.ocpnprod7.gp.inet"
$OcpToolsAuthDir = Join-Path $OcpToolsProject ".registry-auth-temporal"
New-Item -ItemType Directory -Path $OcpToolsAuthDir | Out-Null

try {
    oc registry login --registry $OcpToolsRegistry `
      --to (Join-Path $OcpToolsAuthDir "config.json")
    & $Docker tag "ms-ocp-tools:$OcpToolsVersion" `
      "$OcpToolsRegistry/testing-pmx3/ms-ocp-tools:$OcpToolsVersion"
    & $Docker --config $OcpToolsAuthDir push `
      "$OcpToolsRegistry/testing-pmx3/ms-ocp-tools:$OcpToolsVersion"
}
finally {
    Remove-Item -LiteralPath (Join-Path $OcpToolsAuthDir "config.json") -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $OcpToolsAuthDir -Force -ErrorAction SilentlyContinue
}
```

Comparar el digest informado por Docker con el `ImageStreamTag`. Si difieren,
no aplicar el Deployment. Esta alternativa no usa `--tls-verify=false`.

## 10. Fase E — desplegar

### 10.1 Aplicar sólo los recursos modificados

Si el release cambia RBAC:

```bash
oc apply -f "release-$OCPTOOLS_VERSION/ocp-map-rbac.yaml"
```

Si cambia configuración:

```bash
oc apply -f "release-$OCPTOOLS_VERSION/configmap.yaml"
```

Aplicar la nueva imagen:

```bash
oc apply -f "release-$OCPTOOLS_VERSION/deployment.yaml"
```

No volver a aplicar PostgreSQL, PVC, Secret, Service, Route o ImageStream si la
entrega no los modifica.

### 10.2 Observar rollout

```bash
oc rollout status deployment/ms-ocp-tools --timeout=180s
oc get pods -l app.kubernetes.io/name=ms-ocp-tools -o wide
```

Confirmar imagen efectiva:

```bash
oc get deployment ms-ocp-tools \
  -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'
```

Debe terminar en `ms-ocp-tools:<version>`.

Revisar logs y eventos:

```bash
oc logs deployment/ms-ocp-tools --tail=250
oc get events --sort-by=.lastTimestamp | tail -20
```

### 10.3 Actualizar manifiestos canónicos del servidor

Sólo después de un rollout correcto:

```bash
cp -p "release-$OCPTOOLS_VERSION/deployment.yaml" "$OCPTOOLS_SERVER_ROOT/ocp/deployment.yaml"
cp -p "release-$OCPTOOLS_VERSION/configmap.yaml" "$OCPTOOLS_SERVER_ROOT/ocp/configmap.yaml"
cp -p "release-$OCPTOOLS_VERSION/ocp-map-rbac.yaml" "$OCPTOOLS_SERVER_ROOT/ocp/ocp-map-rbac.yaml"
```

Así, `/home1/devopstest/ms-ocp-tools/ocp` representa lo desplegado y la carpeta
`release-<version>` conserva el paquete inmutable transportado.

## 11. Fase F — validación posterior

### 11.1 Salud y Route

```bash
OCPTOOLS_ROUTE_HOST="$(oc get route ms-ocp-tools -o jsonpath='{.spec.host}')"
curl -ksS "https://$OCPTOOLS_ROUTE_HOST/q/health/live"
curl -ksS "https://$OCPTOOLS_ROUTE_HOST/q/health/ready"
```

Ambos endpoints deben responder en estado saludable.

### 11.2 Checklist funcional mínimo

- abrir `/` y confirmar que carga `Mapa operativo OCP`;
- abrir `/admin` escribiendo la ruta manualmente;
- validar la funcionalidad modificada por la versión;
- abrir `Validación CMS / Ambiente` y ejecutar su regresión principal;
- abrir `Validación TCP / Conectividad` y probar un destino conectado, uno
  rechazado y uno que alcance el timeout configurado;
- consultar los endpoints JSON públicos cuando formen parte del alcance;
- confirmar que PostgreSQL continúa `1/1 Running`;
- revisar que el sensor recupere los namespaces autorizados;
- confirmar que no aparecen errores repetidos `403`, SQL o de migración;
- realizar `Ctrl+F5` si el navegador conserva JavaScript/CSS anterior.

Para la configuración actual de diez namespaces y cinco segundos entre
consultas, esperar aproximadamente cincuenta segundos para observar una vuelta
completa del sensor.

### 11.3 Evidencia de cierre

Registrar sin datos sensibles:

```text
Versión:
Fecha/hora:
Responsable:
SHA-256 del TAR:
ImageStreamTag:
Imagen efectiva del Deployment:
Rollout:
Health live/ready:
Prueba funcional principal:
Regresión Herramienta 01:
Observaciones:
```

No considerar la entrega terminada solamente porque el pod esté `Running`.

## 12. Rollback

Ejecutar rollback si ocurre cualquiera de estas condiciones:

- rollout fallido o pod sin Ready;
- errores repetidos de inicio;
- health no saludable;
- regresión de una herramienta existente;
- falla funcional crítica de la versión.

Consultar historial:

```bash
oc rollout history deployment/ms-ocp-tools
```

Volver a la revisión anterior:

```bash
oc rollout undo deployment/ms-ocp-tools
oc rollout status deployment/ms-ocp-tools --timeout=180s
```

Confirmar la imagen después del rollback:

```bash
oc get deployment ms-ocp-tools \
  -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'
```

Si se cambió el ConfigMap y la versión anterior no es compatible, restaurar el
manifiesto fuente respaldado y renovar el pod:

```bash
oc apply -f "$OCPTOOLS_BACKUP/configmap-source.yaml"
oc rollout restart deployment/ms-ocp-tools
oc rollout status deployment/ms-ocp-tools --timeout=180s
```

No eliminar ni restaurar automáticamente PostgreSQL/PVC durante un rollback de
aplicación. Si la versión incluyó una migración incompatible, seguir el plan de
datos preparado para esa entrega.

## 13. Problemas frecuentes y aprendizaje acumulado

### Docker Desktop no responde

Síntoma:

```text
permission denied ... dockerDesktopLinuxEngine
```

Acción: iniciar o reiniciar Docker Desktop y repetir `docker info`.

### Maven no confía en el repositorio

Síntoma:

```text
PKIX path building failed
```

Acción: revisar las CA públicas corporativas de `certs/`. No desactivar TLS.

### OpenShift no crea el pod de un BuildConfig

Un upload que permanece recibiendo datos o un BuildConfig sin pod no prueba que
el Dockerfile haya fallado. En el intento previo, OpenShift canceló la carga a
los cinco minutos y el controlador no creó el pod por las restricciones/cuota
del namespace; el Dockerfile ni siquiera llegó a ejecutarse.

Acción: revisar `oc describe build`, eventos y cuota. Para este proyecto usar
el flujo comprobado de este manual: compilar/probar con Docker Desktop y
publicar la imagen directamente o transportarla al servidor con Podman.

### Podman antiguo no carga la imagen

Causa habitual: archive con metadata moderna o arquitectura incorrecta.

Acción: reconstruir en la laptop con:

```text
--platform linux/amd64 --provenance=false
```

Después repetir `docker save`, checksum y SCP.

### Checksum incorrecto

No cargar ni publicar el TAR. Repetir la transferencia y verificar espacio
disponible e integridad en ambos extremos.

### Namespace con `403 Forbidden`

No significa que el namespace esté vacío. Un namespace vacío devuelve una
consulta exitosa con cero Deployments. El `403` significa que el ServiceAccount
no tiene `get/list` o que el RoleBinding no pudo crearse.

Acción: conceder RBAC mínimo o retirar el namespace de
`OCP_MAP_NAMESPACES`/`OCP_MAP_VISIBLE_NAMESPACES` hasta disponer de permisos.

### Dry-run RBAC informa `Role not found`

En un YAML multi-documento, el Role del dry-run server-side no se persiste para
el RoleBinding siguiente. Validar con `--dry-run=client`, revisar cada namespace
y aplicar realmente sólo después de confirmar permisos.

### El pod usa una imagen anterior

Acciones:

- confirmar tag inmutable nuevo;
- confirmar ImageStreamTag;
- revisar la imagen del Deployment;
- verificar que `deployment.yaml` no conserva la versión anterior.

### ConfigMap cambiado pero la aplicación no lo refleja

Las variables llegan mediante `envFrom` y se leen al iniciar. Un cambio sólo de
ConfigMap requiere renovar el pod. En una entrega con imagen nueva, el rollout
del Deployment ya realiza esa renovación.

### La UI parece no haber cambiado

Confirmar primero la imagen efectiva y luego usar `Ctrl+F5`. Si persiste,
comparar el contenido del release y revisar los logs del nuevo pod.

### Rollout detenido

Diagnóstico de sólo lectura:

```bash
oc get pods -l app.kubernetes.io/name=ms-ocp-tools
oc describe deployment ms-ocp-tools
oc describe pod <pod>
oc logs <pod> -c ms-ocp-tools --tail=250
oc get events --sort-by=.lastTimestamp | tail -30
```

## 13.1. Entrega `0.7.0` — Servicios externos

Esta entrega incorpora Flyway V5 y dos claves sensibles nuevas. Antes de
construir o desplegar:

1. conservar el paquete previo
   `artifacts/rollback/pre-external-services-20260924-160744.zip` y comprobar su
   SHA-256 documentado;
2. construir el target de pruebas y validar todas las regresiones;
3. probar V5 sobre PostgreSQL 15 desechable;
4. generar un `pg_dump` real inmediatamente antes del rollout;
5. agregar al Secret `tools`, sin imprimir valores:
   `EXTERNAL_CREDENTIAL_ENCRYPTION_KEY` y
   `EXTERNAL_KDBX_MASTER_PASSWORD`;
6. conservar por separado el ConfigMap y Deployment efectivos de `0.6.0`;
7. confirmar `EXTERNAL_MONITOR_ENABLED=false` para el primer rollout;
8. aplicar imagen/configuración solo después de autorización explícita.

Después del rollout y antes de migrar datos:

```bash
oc logs deployment/ms-ocp-tools --tail=250
oc exec statefulset/ms-ocp-tools-postgresql -- psql -U "$POSTGRESQL_USER" -d "$POSTGRESQL_DATABASE" -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;"
```

No escribir claves reales en la línea de comandos. La importación se inicia
desde la laptop con `scripts/import-external-monitor-poc.ps1`; el script pide la
clave maestra como `SecureString`. Ejecutar primero sin `-IncludeHistory`,
revisar servicios/pruebas y luego importar el historial. Después, ejecutar
manualmente una muestra TCP, HTTP y Oracle. Solo si el resultado es aceptable:

```bash
# Cambiar EXTERNAL_MONITOR_ENABLED a true en ocp/configmap.yaml y versionarlo.
oc apply -f ocp/configmap.yaml
oc rollout restart deployment/ms-ocp-tools
oc rollout status deployment/ms-ocp-tools --timeout=180s
```

Este último cambio activa el ciclo programado de 10 minutos. Conservar el
ConfigMap versionado alineado con el valor efectivo elegido para la entrega.

Estado ejecutado el 2026-09-24: imagen publicada directamente desde la laptop,
digest `sha256:c2de3977cf013db4a6d8d4dbd325e15fb749c38fce751c47c04c5c9f03b0aacb`,
rollout 1/1, Flyway V5 aplicado, conteos previos preservados, scheduler en
`false`. Después del rollout se importaron 28 servicios, 36 pruebas, 6
credenciales cifradas, 15 878 ejecuciones y 16 523 resultados históricos. Los
27 registros huérfanos de `BD Gfiscal |` quedaron fuera. Los respaldos son
`artifacts/database-backups/ocp-map-before-v5-20260924-212206.dump` y
`artifacts/database-backups/ocp-map-after-external-import-20260924-220335.dump`.

Rollback normal:

1. restaurar imagen, Deployment y ConfigMap de `0.6.0`;
2. no borrar V5: `0.6.0` ignora las tablas `external_*`;
3. validar las herramientas 01–03.

Rollback físico, únicamente si existe una exigencia expresa y un dump validado:

```bash
psql -v ON_ERROR_STOP=1 -f scripts/rollback-external-services.sql
```

Ese script elimina todo el historial y las credenciales externas. No forma
parte del rollback operativo habitual.

## 13.2. Entrega `0.7.1` — Correctivos del monitor externo

Esta entrega debe partir de `0.7.0` y no reutilizar su tag. Antes del rollout:

1. respaldar Deployment y ConfigMap efectivos de `0.7.0`;
2. generar un `pg_dump` y verificarlo antes de que Flyway aplique V6;
3. confirmar en el manifiesto `EXTERNAL_MONITOR_ENABLED=true` e intervalo
   `10m`;
4. validar que la imagen efectiva referencia `ms-ocp-tools:0.7.1`;
5. no modificar ni recrear Secrets, PostgreSQL, PVC, Service o Route.

V6 no elimina tablas ni historial. Limpia solamente la descripción exacta
`Migrado desde la prueba de concepto Node` y crea un índice parcial sobre las
ejecuciones programadas. `0.7.0` puede operar con ese esquema si se revierte la
aplicación.

Validación específica posterior:

- el log de arranque informa `external-monitor ... enabled=true`;
- el primer ciclo registra `scheduled result=started` y
  `scheduled result=completed`;
- la descarga KDBX responde `200` y puede abrirse con la clave maestra;
- una ejecución manual se identifica como manual sin modificar los KPI;
- `Historial y KPI` responde para 24 horas y 7 días;
- desaparece la leyenda técnica de migración.

Resultado observado el 25 de septiembre de 2026:

- 39 pruebas exitosas y publicación de `0.7.1` con digest
  `sha256:24122926f0b929d63982ce1b384e489a87a7ebdd5781fba0ab75ff154421913e`;
- respaldo previo validado por `pg_restore`, SHA-256
  `b3a5915f2205dae38094128fcb5edae46a0218f4a8642981ef7b003599aa4538`;
- rollout `1/1`, cero reinicios y readiness HTTP 200;
- Flyway V6 aplicado, descripción técnica eliminada e índice parcial creado;
- primer lote completo: 28 servicios en 13,017 segundos, 21 `UP` y 7
  `WARNING` según la política de confirmación de fallos.

Rollback de `0.7.1`:

1. ejecutar `oc rollout undo deployment/ms-ocp-tools`;
2. restaurar el ConfigMap respaldado de `0.7.0`, cuyo scheduler está en
   `false`;
3. renovar el pod y confirmar la imagen `0.7.0`;
4. no revertir V6 físicamente: es compatible y no afecta las funciones de
   `0.7.0`.

## 13.3. Entrega `0.7.2` — TLS ligero y KPI por rango

Estado documental: desplegada y operativa en `testing-pmx3`.

No agrega migraciones ni modifica datos existentes. Incorpora:

- `EXTERNAL_MONITOR_TLS_VERIFY=false`, que conserva cifrado HTTPS pero no
  valida cadena ni nombre del certificado de los servicios monitoreados;
- rango KPI predefinido o personalizado de hasta 90 días;
- tarjetas de disponibilidad y p95 calculadas con el rango y servicio elegidos;
- línea de tiempo agregada en un máximo de 40 bloques;
- descarga ZIP con `resumen.csv` y `ejecuciones.csv`;
- fondos de semáforo con texto oscuro legible.

Validación local y despliegue completados el 25 de septiembre de 2026:

- target Docker `test`: 43 pruebas, 0 fallos, 0 errores y 0 omitidas;
- imagen runtime `ms-ocp-tools:0.7.2` construida para `linux/amd64`;
- digest local, publicado y ejecutado:
  `sha256:98dcec1ac12ef434651218aed8e6884e292b55a04ecfccf7e177a68fd1b7fc95`;
- Deployment revisión 20, pod `1/1 Ready` y 0 reinicios;
- health live/ready, vista principal, administración, mapa OCP, CMS, snapshot e
  historial externo respondieron HTTP 200;
- la exportación KPI de 24 horas respondió `application/zip` con HTTP 200;
- primer ciclo programado: 28 servicios en 14,321 ms;
- Flyway validó V6 y no ejecutó migraciones.

El rollback de aplicación/configuración consiste en restaurar la imagen y el
ConfigMap respaldados de `0.7.1`; no hay rollback de base de datos.

## 13.4. Entrega `0.7.3` — estabilización del monitor HTTP

Estado documental: desplegada y operativa en `testing-pmx3`.

No incorpora migraciones de Flyway. La entrega cambia únicamente el runtime y
el ConfigMap:

- una conexión nueva y con cierre explícito para cada intento HTTP;
- un segundo y último intento solo cuando el primero termina en timeout sin
  respuesta HTTP;
- clasificación separada de timeout de conexión, escritura y respuesta, TLS,
  DNS, conexión e I/O;
- código HTTP visible en el detalle de cada sonda;
- `EXTERNAL_MONITOR_PARALLELISM=1` en el manifiesto canónico.

Antes de publicar, ejecutar el target Docker `test` y comprobar específicamente
los tests de `ExternalProbeExecutorTest`. Después del rollout, validar una sonda
que responda `200`, una que responda `400`, una que agote ambos intentos y otra
que se recupere en el segundo intento. Confirmar en logs
`action=http-retry` únicamente para timeouts.

Validación completada el 26 de septiembre de 2026:

- target Docker `test`: 47 pruebas, 0 fallos, 0 errores y 0 omitidas;
- imagen runtime `linux/amd64` publicada y ejecutada con digest
  `sha256:e30565fd54fed7809f6b02e9ba5dfe20f4a087dc0c4f379c34a8157abb1e1bd7`;
- Deployment revisión 21, pod `1/1 Ready` y 0 reinicios;
- health live/ready, Route, UI y snapshot público respondieron HTTP 200;
- Flyway validó V6 sin migraciones pendientes;
- primer ciclo: 30 servicios con paralelismo 1 en 35,613 ms, 27 `UP` y 3
  `DOWN` según sus destinos;
- un timeout de conexión ejecutó exactamente un reintento y quedó clasificado
  como `HTTP_CONNECT_TIMEOUT` después de dos intentos;
- 23 resultados HTTP conservaron su `responseCode` en la API pública y la UI
  cargó el asset `external.js` de `0.7.3`.

El rollback consiste en aplicar el ConfigMap respaldado y restaurar la imagen
inmutable `0.7.2`. No ejecutar rollback de Flyway ni eliminar historial.

## 14. Criterio de finalización

Una versión queda desplegada cuando se cumple todo lo siguiente:

- target Docker de pruebas exitoso;
- imagen runtime `linux/amd64` construida sin provenance;
- checksum validado en el servidor, o digest del push directo igual al digest
  local de la imagen;
- imagen publicada con tag nuevo;
- ImageStreamTag visible;
- dry-run de manifiestos sin errores bloqueantes;
- rollout completo;
- imagen efectiva correcta;
- health live/ready saludable;
- prueba funcional nueva aprobada;
- regresión de herramientas existentes aprobada;
- manifiestos canónicos del servidor sincronizados;
- evidencia registrada y documentación actualizada.

## 15. Evolución futura

Este procedimiento manual debe convertirse posteriormente en etapas de
pipeline:

```text
Bitbucket
  → build y pruebas
  → análisis y versionado
  → imagen y checksum/SBOM
  → registry corporativo
  → manifiestos GitOps
  → Argo CD
  → smoke tests y evidencia
```

Mientras ese flujo no exista, este manual es el procedimiento operativo de
referencia para nuevas entregas manuales de OCP Tools.
