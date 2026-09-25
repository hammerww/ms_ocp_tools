# Herramienta 03 — Validación TCP / Conectividad

Fecha de vigencia: 2026-09-10  
Estado: desplegada y operativa en la entrega `0.4.0`

## Objetivo

Permitir que un usuario compruebe desde la red del pod de OCP Tools si una
dirección IPv4 acepta una conexión TCP en un puerto determinado, reemplazando
la prueba manual `curl -v telnet://IP:PUERTO` ejecutada con `oc rsh`.

## Alcance

- IP y puerto ingresados libremente por el usuario desde la web.
- Una única tentativa por acción explícita.
- Timeout de conexión de 10 segundos.
- La conexión se cierra inmediatamente después del handshake exitoso.
- No se envía payload ni se valida el protocolo de aplicación.
- No se persisten resultados ni se modifica ningún recurso OCP.

El usuario fue informado del riesgo de permitir destinos arbitrarios y aceptó
expresamente continuar sin allowlist de IP, red o puerto para esta demo interna.

## Ubicación en OCP Tools

Familia: `Utilidades operativas`  
Nombre visible: `Validación TCP / Conectividad`

Se selecciona desde el mismo desplegable que contiene Validación CMS /
Ambiente. No agrega Route, Service, Deployment ni frontend separado.

## Entradas y salidas

Request:

```json
{
  "ip": "10.10.71.174",
  "port": 8003
}
```

Validaciones:

- IPv4 con cuatro octetos entre 0 y 255;
- puerto entre 1 y 65535;
- el backend no confía en la validación HTML;
- no se admiten hostnames en la primera versión.

Response:

```json
{
  "status": "CONNECTED",
  "connected": true,
  "ip": "10.10.71.174",
  "port": 8003,
  "timeoutSeconds": 10,
  "durationMs": 24,
  "checkedAt": "2026-08-26T17:30:00Z",
  "correlationId": "...",
  "message": "Conexión TCP exitosa. El destino aceptó la conexión y OCP Tools la cerró inmediatamente."
}
```

## Reglas funcionales

- `CONNECTED` significa únicamente que se completó el handshake TCP.
- Un handshake exitoso no demuestra que el protocolo de aplicación esté sano.
- No se debe esperar contenido del servidor después de conectar.
- No hay reintentos automáticos.
- Un resultado negativo válido se devuelve como información funcional, no como
  falla interna del endpoint.
- El tiempo reportado se mide en el backend desde antes de validar y conectar.

## Estados funcionales

```text
CONNECTED
CONNECTION_REFUSED
TIMEOUT
NO_ROUTE
ERROR
INVALID_REQUEST
```

## Persistencia

No utiliza PostgreSQL, Informix, ConfigMap como almacén ni historial. Cada
resultado existe solamente en la respuesta y en los logs operativos.

## Seguridad y permisos

- No requiere credenciales ni Secrets.
- No requiere cambios al ServiceAccount o RBAC.
- Utiliza la conectividad saliente disponible para el pod.
- IP y puerto libres están aprobados sólo para el alcance interno de esta demo.
- No ejecuta shell, `curl` ni concatena entradas del usuario en comandos.
- La implementación usa `Socket.connect` con dirección IPv4 binaria y puerto
  validado.

## UI y contrato visual

- Dos campos: `IP destino` y `Puerto`.
- El timeout se informa como fijo y no puede modificarse desde la UI.
- El botón muestra estado de carga durante la conexión.
- El resultado presenta estado, IP, puerto, duración, timeout, fecha y
  correlation ID.
- `CONNECTED` se muestra como éxito; `TIMEOUT` como advertencia; el resto como
  error o solicitud inválida.

## API

```http
POST /api/v1/tcp-check
Content-Type: application/json
```

- `200`: resultado de red válido, positivo o negativo;
- `400`: IP o puerto inválidos;
- validación server-side obligatoria.

## Configuración

ConfigMap:

```text
TCP_CHECK_TIMEOUT=10S
```

Propiedad:

```text
ocp-tools.tcp-check.timeout=${TCP_CHECK_TIMEOUT:10S}
```

No hay configuración sensible.

## Observabilidad

Cada intento registra:

```text
tool=tcp-check
action=CONNECT
ip
port
result
durationMs
timeoutMs
correlationId
user, si la Route lo proporciona
```

Los errores esperados de red no registran payload ni datos sensibles.

## Pruebas de aceptación

1. Destino que acepta conexión: `CONNECTED` sin esperar datos.
2. Puerto cerrado: `CONNECTION_REFUSED`.
3. Destino que no responde en diez segundos: `TIMEOUT`.
4. Red sin ruta: `NO_ROUTE`.
5. Error de I/O no clasificado: `ERROR` sin exponer detalle interno.
6. IPv4 inválida o puerto fuera de rango: `INVALID_REQUEST`/HTTP 400.
7. Cambio de utilidad no afecta Mapa operativo OCP ni Validación CMS.
8. Validación real desde el pod contra `10.10.71.174:8003`.

## Gates de implementación

- suite Docker: 20 pruebas sin fallos, incluidas seis propias;
- sintaxis JavaScript: validada;
- imagen runtime `linux/amd64`: construida y smoke-tested localmente;
- prueba posterior al despliegue contra un destino conectado, uno rechazado y
  uno que alcance el timeout;
- regresión de CMS, mapa, `/admin` y health checks.

## Decisiones cerradas

- 2026-08-26: IP y puerto serán libres, sin allowlist.
- 2026-08-26: timeout de conexión de 10 segundos.
- 2026-08-26: la prueba se ejecuta desde el pod de `ms-ocp-tools`.
- 2026-08-26: no persistir resultados ni ejecutar comandos del sistema.

## Pendientes

- Validar la utilidad contra la red real después de desplegar `0.4.0`.
