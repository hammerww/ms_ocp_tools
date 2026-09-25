#!/usr/bin/env bash

# Diagnóstico de solo lectura para el servidor de accesos.
# No construye, no publica imágenes y no modifica recursos de OpenShift.

set -u

PROJECT="${1:-testing-pmx3}"
QUARKUS_VERSION="3.33.3.1"
INFORMIX_VERSION="4.50.14"

section() {
  echo
  echo "===== $1 ====="
}

available() {
  if command -v "$1" >/dev/null 2>&1; then
    echo "$1=DISPONIBLE ($(command -v "$1"))"
    return 0
  fi
  echo "$1=NO_ENCONTRADO"
  return 1
}

can_i() {
  resource="$1"
  verb="$2"
  result="$(oc auth can-i "$verb" "$resource" -n "$PROJECT" 2>&1)"
  printf '%-42s %s\n' "$verb $resource" "$result"
}

section "Herramientas del servidor"
available oc || exit 1
available podman || true
available java || true
available mvn || true
available unzip || true

section "Versiones"
oc version --client 2>&1 || true
podman --version 2>&1 || true
java -version 2>&1 || true
mvn -version 2>&1 || true

section "Sesión OpenShift"
echo "usuario=$(oc whoami 2>&1)"
echo "servidor=$(oc whoami --show-server 2>&1)"
echo "proyecto_actual=$(oc project -q 2>&1)"
echo "proyecto_objetivo=$PROJECT"
oc get namespace "$PROJECT" -o name 2>&1 || true

section "Permisos requeridos en $PROJECT"
can_i configmaps get
can_i configmaps create
can_i configmaps update
can_i secrets get
can_i secrets create
can_i secrets update
can_i deployments.apps get
can_i deployments.apps create
can_i deployments.apps update
can_i deployments.apps patch
can_i services get
can_i services create
can_i routes.route.openshift.io get
can_i routes.route.openshift.io create
can_i imagestreams.image.openshift.io get
can_i imagestreams.image.openshift.io create
can_i imagestreams.image.openshift.io update
can_i pods get
can_i pods/log get
can_i pods/exec create

section "Registry integrado"
echo "oc_registry_info=$(oc registry info 2>&1 || true)"
echo -n "ruta_registry="
oc get route default-route -n openshift-image-registry \
  -o jsonpath='{.spec.host}{"\n"}' 2>&1 || true
echo
echo "Nota: no se ejecutó oc registry login ni podman login."

section "Imágenes ya usadas en $PROJECT"
oc get deployments -n "$PROJECT" \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{range .spec.template.spec.containers[*]}{.name}{"="}{.image}{" "}{end}{"\n"}{end}' \
  2>&1 || true

section "ImageStreams existentes"
oc get imagestreams -n "$PROJECT" 2>&1 || true

section "Imágenes disponibles localmente en Podman (máximo 40)"
if command -v podman >/dev/null 2>&1; then
  podman images --format '{{.Repository}}:{{.Tag}} {{.ID}} {{.Size}}' 2>&1 | sed -n '1,40p'
fi

section "Maven y caché offline"
if [ -f "$HOME/.m2/settings.xml" ]; then
  echo "maven_settings=DISPONIBLE ($HOME/.m2/settings.xml)"
else
  echo "maven_settings=NO_ENCONTRADO"
fi

if [ -d "$HOME/.m2/repository" ]; then
  echo "maven_repository=DISPONIBLE ($HOME/.m2/repository)"
  du -sh "$HOME/.m2/repository" 2>&1 || true
else
  echo "maven_repository=NO_ENCONTRADO"
fi

if [ -d "$HOME/.m2/repository/io/quarkus/platform/quarkus-bom/$QUARKUS_VERSION" ]; then
  echo "quarkus_bom_${QUARKUS_VERSION}=EN_CACHE"
else
  echo "quarkus_bom_${QUARKUS_VERSION}=NO_EN_CACHE"
fi

INFORMIX_JAR="$HOME/.m2/repository/com/ibm/informix/jdbc/$INFORMIX_VERSION/jdbc-$INFORMIX_VERSION.jar"
if [ -f "$INFORMIX_JAR" ]; then
  echo "informix_jdbc_${INFORMIX_VERSION}=EN_CACHE"
else
  echo "informix_jdbc_${INFORMIX_VERSION}=NO_EN_CACHE"
fi

echo "Nota: NO_EN_CACHE no demuestra que falte en el repositorio corporativo; solo indica que aún no está en la caché local."

section "Capacidad local"
df -h . 2>&1 || true
free -h 2>&1 || true

section "Resultado esperado antes del build"
echo "1. oc y podman disponibles."
echo "2. Sesión conectada al cluster correcto y acceso a $PROJECT."
echo "3. Permisos de recursos OCP y de ImageStream suficientes."
echo "4. Registry accesible desde el servidor y permiso de push confirmado posteriormente."
echo "5. Imágenes base internas de build/runtime identificadas."
echo "6. Mirror Maven/caché offline completo, o artifact/quarkus-app preconstruido."
echo "7. Espacio suficiente para dependencias, capas e imagen final."

