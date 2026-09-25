# Valores confirmados durante el diagnóstico. Pueden reemplazarse por imágenes
# corporativas equivalentes mediante --build-arg.
ARG BUILD_IMAGE=registry.access.redhat.com/ubi8/openjdk-17:1.16-1
ARG RUNTIME_IMAGE=registry.access.redhat.com/ubi8/openjdk-17:1.16-1
FROM ${BUILD_IMAGE} AS build-base

USER 0
COPY certs/ /tmp/ocp-tools-certs/
RUN find /tmp/ocp-tools-certs -type f -name '*.crt' \
      -exec cp {} /etc/pki/ca-trust/source/anchors/ \; \
    && update-ca-trust extract \
    && mkdir -p /workspace \
    && chown -R 185:0 /workspace \
    && chmod -R g=u /workspace

USER 185
WORKDIR /workspace
COPY --chown=185:0 pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY --chown=185:0 src ./src

# Objetivo opcional para ejecutar las pruebas en el servidor:
# podman build --target test --build-arg BUILD_IMAGE=... -t ocp-tools-test .
FROM build-base AS test
RUN mvn -B -ntp test

# El artefacto desplegable solo se genera después de superar las pruebas.
# Esto también protege los builds ejecutados directamente por OpenShift,
# donde no existe la opción local `podman build --target test`.
FROM test AS package
RUN mvn -B -ntp -DskipTests package

FROM ${RUNTIME_IMAGE} AS runtime

WORKDIR /deployments
COPY --from=package --chown=1001:0 /workspace/target/quarkus-app/lib/ ./lib/
COPY --from=package --chown=1001:0 /workspace/target/quarkus-app/*.jar ./
COPY --from=package --chown=1001:0 /workspace/target/quarkus-app/app/ ./app/
COPY --from=package --chown=1001:0 /workspace/target/quarkus-app/quarkus/ ./quarkus/

USER 1001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-Dquarkus.http.host=0.0.0.0"
ENTRYPOINT ["java", "-jar", "/deployments/quarkus-run.jar"]
