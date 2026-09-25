# Herramienta NN — Nombre

Fecha de vigencia: AAAA-MM-DD  
Estado: propuesta | contrato | implementación | desplegada

## Objetivo

Problema operativo que resuelve y usuarios destinatarios.

## Alcance

- Incluido.
- Excluido.
- Acciones de solo lectura.
- Acciones modificatorias.

## Ubicación en OCP Tools

- Familia de navegación.
- Nombre visible.
- Vistas internas.

## Entradas y salidas

Definir tipos, validaciones, ejemplos y datos que nunca deben confiarse al
navegador.

## Fuentes de datos e integraciones

Por cada origen:

- propietario;
- protocolo y endpoint configurable;
- autenticación;
- timeout;
- contrato de éxito;
- ausencia confirmada;
- error técnico;
- disponibilidad y permisos pendientes.

## Reglas funcionales

Enumerar precondiciones, decisiones cerradas, idempotencia y resultados
indeterminados.

## Estados funcionales

Usar códigos explícitos, no textos libres.

## Persistencia

- entidades y relaciones;
- fuente de verdad;
- retención;
- migraciones;
- concurrencia;
- backup/restore;
- datos de seed.

## Seguridad y permisos

- usuario final;
- ServiceAccount;
- privilegio mínimo;
- Secrets y ConfigMap;
- auditoría de modificaciones.

## UI y contrato visual

- referencia del mockup;
- navegación;
- búsquedas;
- estados de carga/error/vacío;
- responsive y accesibilidad;
- acciones prohibidas.

## API

- endpoints;
- requests/responses;
- códigos HTTP;
- revalidación server-side.

## Configuración

Separar variables sensibles y no sensibles. Indicar valores predeterminados y
qué parámetros son dinámicos.

## Observabilidad

- correlation ID;
- logs estructurados;
- métricas/duraciones;
- health/readiness;
- datos prohibidos en logs.

## Pruebas de aceptación

Incluir éxito, ausencia, timeout, errores, concurrencia, reinicio, regresión y
seguridad.

## Gates de implementación

Validaciones de permisos, conectividad, infraestructura, licencias o contratos
que deben completarse antes de integrar datos reales.

## Decisiones cerradas

Lista fechada y concisa.

## Pendientes

Solo dudas o tareas aún abiertas; no mezclar con historia resuelta.

