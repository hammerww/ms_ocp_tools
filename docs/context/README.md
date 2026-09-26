# Índice de contexto — OCP Tools

Fecha de vigencia: 2026-09-24

## Propósito

Esta carpeta es la fuente de contexto técnico y funcional para diseñar,
implementar, probar, desplegar y evolucionar OCP Tools. Los documentos están
separados por responsabilidad para evitar mezclar decisiones vigentes con el
historial cronológico de conversaciones.

Ante una contradicción se aplica este orden:

1. el contrato de la herramienta afectada;
2. el contexto vigente de infraestructura y restricciones;
3. el contexto común de plataforma;
4. el README del proyecto;
5. ejemplos, mockups o antecedentes históricos.

No se deben asumir credenciales, permisos, URLs, namespaces o reglas de negocio
que no aparezcan confirmados en estos documentos.

## Orden de lectura

### Contexto común

1. [`platform/OCP_TOOLS_PLATFORM.md`](platform/OCP_TOOLS_PLATFORM.md)
2. [`infrastructure/CURRENT_INFRASTRUCTURE.md`](infrastructure/CURRENT_INFRASTRUCTURE.md)
3. [`infrastructure/CONSTRAINTS_ACCESS_SECURITY.md`](infrastructure/CONSTRAINTS_ACCESS_SECURITY.md)
4. [`infrastructure/MANUAL_DESPLIEGUE.md`](infrastructure/MANUAL_DESPLIEGUE.md)
5. [`infrastructure/BUILD_DEPLOYMENT_RUNBOOK.md`](infrastructure/BUILD_DEPLOYMENT_RUNBOOK.md)

### Herramienta 01 — Validación CMS / Ambiente

6. [`tools/tool-01-cms-environment/CONTEXT.md`](tools/tool-01-cms-environment/CONTEXT.md)

### Herramienta 02 — Mapa operativo OCP

7. [`tools/tool-02-ocp-operational-map/CONTEXT.md`](tools/tool-02-ocp-operational-map/CONTEXT.md)
8. [`tools/tool-02-ocp-operational-map/VISUAL_CONTRACT.md`](tools/tool-02-ocp-operational-map/VISUAL_CONTRACT.md)
9. Mockup autónomo:
   [`tools/tool-02-ocp-operational-map/mockups/control-ocp-impacto.html`](tools/tool-02-ocp-operational-map/mockups/control-ocp-impacto.html)
10. Fuente editable:
   [`tools/tool-02-ocp-operational-map/mockups/control-ocp-impacto.source.html`](tools/tool-02-ocp-operational-map/mockups/control-ocp-impacto.source.html)

### Herramienta 03 — Validación TCP / Conectividad

11. [`tools/tool-03-tcp-connectivity/CONTEXT.md`](tools/tool-03-tcp-connectivity/CONTEXT.md)

### Herramienta 04 — Servicios externos

12. [`tools/tool-04-external-services/CONTEXT.md`](tools/tool-04-external-services/CONTEXT.md)

## Estado resumido

- `ms-ocp-tools:0.7.3` está desplegado y operativo en `testing-pmx3`; Flyway
  conserva V6, el pod está `1/1 Ready` sin reinicios y health/API pública
  responden HTTP 200. La imagen superó 47 pruebas y ejecuta el digest
  `sha256:e30565fd54fed7809f6b02e9ba5dfe20f4a087dc0c4f379c34a8157abb1e1bd7`.
  El monitor continúa activo cada 10 minutos con paralelismo 1; su primer lote
  procesó 30 servicios en 35,613 segundos y persistió códigos HTTP y fases
  diagnósticas.
- `0.7.2`, con su imagen y ConfigMap conservados, es el rollback funcional
  inmediato. V6 no requiere reversión física porque `0.7.3` no incorpora
  migraciones.
- La Herramienta 01 debe conservarse operativa y sin regresiones.
- La Herramienta 02 funciona con PostgreSQL persistente, sensor OCP, API y UI;
  el ServiceAccount confirmó lectura y ausencia de permisos modificatorios.
- `0.5.0` agrega inventario completo de microservicios en la vista normal,
  anotaciones extensas, clonación de casos, último código como ayuda manual y
  diagramas de flujo editables/persistentes. Flyway V4 se aplicó sin alterar los
  conteos del catálogo existente.
- `0.5.2` mantiene la estructura de las vistas y mejora únicamente la UI: tres
  posiciones estables por caso (`Ver flujo`/`Sin flujo`, `Anotación`, estado),
  visor/editor en popup con conservación del estado del panel, lienzo amplio,
  zoom, ajuste, desplazamiento, minimapa y edición fiable de conexiones. No
  agrega migraciones ni modifica el modelo de datos.
- La prueba de publicación PostgreSQL mediante `NodePort` no tuvo conectividad y
  ese Service fue retirado. El acceso ocasional con DBeaver queda mediante túnel
  SSH más `oc port-forward`. En la laptop actual sí está disponible `oc`, pero
  el usuario debe autenticarse antes de cada bloque de validaciones del clúster.
- `0.1.0` continúa protegido como referencia y rollback; no debe sobrescribirse.
- El respaldo fuente y documental anterior a Servicios externos es
  `ms-ocp-tools/artifacts/rollback/pre-external-services-20260924-160744.zip`,
  SHA-256 `9654cd7e86e5370debeef3ccdf9ea31cec7c6ff066dca883c253b1057b09a9ba`.
- El entregable local posterior está en
  `ms-ocp-tools/artifacts/releases/ocp-tools-0.7.0-SNAPSHOT-source-and-docs.zip`;
  su checksum se conserva en el archivo `.sha256` adyacente.
- El cierre fuente/documental del despliegue está en
  `ms-ocp-tools/artifacts/releases/ocp-tools-0.7.0-source-and-docs.zip`; su
  checksum se conserva en el archivo `.sha256` adyacente.
- El paquete inmutable desplegado está en `ms-ocp-tools/release-0.7.0`; el
  Docker archive tiene SHA-256
  `e65434392a92b1a32c2b7e11efdda95b411f70bb4bc2adf178ae5c95dbc2c905`.

## Mantenimiento de estos documentos

- Actualizar el documento responsable de una decisión; no agregar un nuevo
  diario monolítico al final de otro archivo.
- Marcar cada dato como `confirmado`, `propuesto` o `pendiente` cuando pueda
  existir ambigüedad.
- Mantener separados el contrato funcional, la infraestructura y el runbook.
- No almacenar secretos, contraseñas, tokens, certificados privados o valores
  sensibles.
- Cuando se agregue una herramienta, partir de
  [`templates/TOOL_CONTEXT_TEMPLATE.md`](templates/TOOL_CONTEXT_TEMPLATE.md).
- Los mockups pueden contener datos de ejemplo, pero el contrato debe declarar
  expresamente si se consideran datos iniciales reales.
