# Contexto de plataforma — OCP Tools

Fecha de vigencia: 2026-09-24

## Identidad y propósito

Nombre conceptual: **OCP Tools**  
Aplicación y Deployment: `ms-ocp-tools`

OCP Tools es una plataforma web de herramientas operativas internas. No debe
evolucionar como un microservicio dedicado exclusivamente a CMS ni atribuirse al
equipo de Integración por su nombre.

La plataforma aprovecha la conectividad de OpenShift hacia bases de datos,
APIs, servicios internos y la API de Kubernetes/OpenShift, manteniendo las
credenciales centralizadas y evitando instalaciones en las laptops de los
usuarios.

## Arquitectura común cerrada

- Aplicación web desplegada en OpenShift.
- Un artefacto Quarkus y un Deployment de aplicación.
- Una Route y una experiencia de navegación común.
- Frontend HTML/CSS/JavaScript servido por Quarkus desde
  `META-INF/resources`.
- Java 17 y Quarkus 3.33.3.1 en modo JVM.
- Herramientas implementadas como módulos lógicos independientes.
- Configuración no sensible externalizada mediante ConfigMap.
- Secretos únicamente mediante OpenShift Secret.

No se incorporarán por defecto:

- un microservicio por herramienta;
- frontend o Deployment separado;
- Nginx, Node.js o npm;
- API Gateway adicional;
- colas o eventos para operaciones simples;
- base de datos propia sin una necesidad funcional concreta.

La persistencia compartida requerida por la Herramienta 02 constituye una
necesidad concreta y justifica PostgreSQL como dependencia de infraestructura,
sin dividir la aplicación `ms-ocp-tools`.

## Organización funcional de la UI

La navegación debe permitir módulos grandes con vistas internas y utilidades
pequeñas orientadas a una acción concreta.

Estructura desplegada en la demo `0.2.0` y evolución administrativa acordada:

```text
OCP Tools
├── Visibilidad operativa
│   └── Mapa operativo OCP
│       ├── Servicios externos
│       ├── Casos de prueba
│       ├── Microservicios
│       ├── Sin flujo
│       └── Impacto al apagar
│
├── Administración web — ruta directa, sin enlace visible
│   └── /admin
│       ├── Casos de prueba
│       ├── Elementos / plataformas
│       ├── Clonación, anotaciones y editor de flujo
│       └── Archivados
│
└── Utilidades operativas
    ├── Validación CMS / Ambiente
    └── Validación TCP / Conectividad
```

`Utilidades operativas` es el nombre preferido frente a `Helps` para mantener
la interfaz en español y describir herramientas de alcance breve.

`/admin` forma parte del mismo artefacto, Deployment, Service y Route. No se
creará un frontend ni un microservicio administrativo separado. La vista
operativa no mostrará navegación hacia esa ruta y tampoco mostrará la etiqueta
`BETA`.

## Reglas transversales

### Errores

Nunca interpretar un error técnico como ausencia de datos. Diferenciar siempre:

```text
NO ENCONTRADO
ERROR DE CONSULTA
TIMEOUT
RESULTADO INDETERMINADO
```

Una acción modificatoria solo puede ejecutarse cuando sus precondiciones se
hayan confirmado correctamente.

### Timeouts

- Máximo general vigente: 5 segundos por operación externa individual.
- Excepción aprobada: la conexión de Validación TCP / Conectividad utiliza 10
  segundos.
- Sin timeouts infinitos.
- Separar conexión y respuesta cuando la tecnología lo permita, sin superar el
  máximo funcional.
- Una herramienta puede proponer otra regla, pero debe quedar aprobada y
  documentada expresamente.

### Seguridad

No almacenar secretos en código, frontend, repositorio, Dockerfile, ConfigMap,
logs o documentos de contexto. Usar Secret para credenciales y ConfigMap para
URLs, intervalos, catálogos y parámetros no sensibles.

Aplicar privilegio mínimo a usuarios técnicos y ServiceAccounts. Una
herramienta informativa no debe recibir permisos de modificación sobre OCP.

### Auditoría y observabilidad

Reutilizar:

- `correlationId`, `requestId` o identificador equivalente;
- logs JSON con niveles INFO, WARN y ERROR;
- duración de consultas y operaciones externas;
- timestamp, herramienta, acción, resultado y contexto funcional;
- health checks `/q/health/live` y `/q/health/ready`.

No registrar passwords, tokens, valores completos de Secrets ni información
sensible.

### Pruebas

Cada entrega debe incluir:

- pruebas propias de la nueva herramienta;
- regresión de las herramientas existentes;
- escenarios de éxito, ausencia, error, timeout y respuesta inválida;
- validación de UI y accesibilidad básica;
- prueba de health y smoke test de la Route.

## Versionado

- `0.1.0`: baseline desplegado y rollback de la Herramienta 01.
- `0.2.0`: demo desplegada con la primera versión de la Herramienta 02.
- `0.3.0`: administración web, vista compacta y API JSON desplegadas como demo.
- `0.3.1`: mejora de selección administrativa implementada en desarrollo.
- `0.3.2`: incorpora esa mejora y el reenvío CMS posterior al registro;
  implementada y probada en desarrollo.
- `0.4.0`: integra los cambios de `0.3.2` y agrega Validación TCP /
  Conectividad mediante IP y puerto libres; desplegada y operativa.
- `0.5.0`: desplegada y operativa con inventario completo visible, anotaciones,
  clonación y diagramas de flujo por caso.
- `0.5.2`: mejora visual desplegada sobre `0.5.0`; alinea acciones, abre visor y
  editor en popup preservando el estado del panel y amplía la navegación y
  edición del lienzo. No incorpora migraciones de base de datos.
- `0.6.0`: desplegada y operativa; agrega flujo secuencial con consultas
  anidadas, importación/exportación JSON y búsqueda de casos por Deployment.
- `0.7.0`: desplegada y operativa; incorpora Servicios externos, credenciales
  cifradas, scheduler, historial, incidentes y KDBX. Flyway quedó en V5. El
  scheduler se conserva deshabilitado hasta importar y validar una muestra.
- `0.7.1`: desplegada y operativa; corrige la descarga KDBX, diferencia
  resultados manuales y programados, expone KPI históricos y ejecuta el
  scheduler cada 10 minutos. Flyway V6 es compatible con `0.7.0` y no elimina
  historial ni credenciales. `0.7.0` es el rollback inmediato.
- `0.7.2`: implementada localmente y no desplegada; agrega la política TLS
  configurable del monitor, rangos KPI personalizados, máximo 40 bloques y
  exportación ZIP con resumen y ejecuciones. No agrega migraciones.
- Los tags publicados deben ser inmutables.
- No sobrescribir `0.1.0` ni desacoplar el rollback de imagen de su ConfigMap
  compatible.

## Evolución operativa

El despliegue manual actual es temporal. El destino futuro sigue siendo:

```text
Bitbucket → pipeline corporativo → GitOps → Argo CD → OpenShift
```

La lógica funcional no debe depender del mecanismo manual de entrega.
