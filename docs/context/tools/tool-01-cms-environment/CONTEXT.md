# Herramienta 01 — Validación CMS / Ambiente

Fecha de vigencia: 2026-09-10  
Estado: evolución incluida en `0.4.0`, desplegada y operativa

## Objetivo

Consultar una AccessID en CMS Informix, validar su PID y EXECID mediante
`queryESb` y permitir un registro explícito mediante `createEsb` únicamente
cuando se confirme que la transacción no existe. Después del registro solicita
un reenvío desde CMS marcando `estado_env='P'` para la AccessID.

La Herramienta 01 debe conservarse operativa y sin regresiones al agregar nuevas
herramientas.

## Entradas

```json
{
  "accessId": "1400068580",
  "environment": "PMX3"
}
```

- AccessID se trata como texto para conservar ceros iniciales.
- El ambiente se selecciona del catálogo configurado.

## Endpoints

```http
GET  /api/v1/cms/environments
POST /api/v1/cms/validate
POST /api/v1/cms/register
```

El endpoint de registro vuelve a consultar CMS y `queryESb`. No confía en PID,
EXECID ni External ID enviados por el navegador.

## Flujo cerrado

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
                      estado_env = 'P' en CMS
                                     |
                                     v
                             queryESb de verificación
```

El registro siempre es una acción explícita del usuario.

## Contrato CMS Informix

Conexión confirmada:

```text
JDBC URL: jdbc:informix-sqli://10.226.0.92:1448/gescab:INFORMIXSERVER=cmt_tcp
Host: 10.226.0.92
Puerto: 1448
Database: gescab
Informix Server: cmt_tcp
Driver: com.ibm.informix:jdbc:4.50.14
```

Tabla:

```text
nil_trama_da_cmsnil
```

Campos:

```text
interaction_date_c
cfs_access_id
external_id
pid
exceid
estado_env
```

La columna se llama `exceid`; funcionalmente se representa como `execId`.

Consulta parametrizada:

```sql
SELECT FIRST 1
       interaction_date_c,
       cfs_access_id,
       external_id,
       pid,
       exceid
FROM nil_trama_da_cmsnil
WHERE cfs_access_id = ?
ORDER BY interaction_date_c DESC;
```

Si varias filas comparten la fecha máxima, se puede utilizar cualquiera en la
beta. No concatenar AccessID en el SQL.

### Reenvío posterior al registro

Cuando `createEsb` responde exitosamente, la aplicación espera el intervalo
configurado y ejecuta una única sentencia parametrizada:

```sql
UPDATE nil_trama_da_cmsnil
SET estado_env = 'P'
WHERE cfs_access_id = ?;
```

Reglas cerradas:

- la AccessID procede de la nueva consulta CMS realizada por `/register`;
- se actualizan todas las filas coincidentes;
- cero filas actualizadas se considera un reenvío fallido;
- un error o timeout del `UPDATE` no oculta el resultado real de `createEsb` ni
  impide la verificación final;
- cuando no puede solicitarse el reenvío, la respuesta agrega exactamente
  `No se pudo reenviar desde CMS`;
- no hay reintento automático del `UPDATE`;
- el número de filas afectadas y el resultado se registran sin exponer
  credenciales.

Clasificación:

- sin filas: `CMS_NOT_FOUND`;
- conexión o SQL inválido: `DATABASE_ERROR`;
- tiempo excedido: `TIMEOUT`.

## Contrato SOAP `queryESb`

```text
POST http://10.4.67.58:10002/Parallelism/queryESb
Content-Type: text/xml; charset=utf-8
SOAPAction: "selectDB"
Namespace: http://xmlns.oracle.com/pcbpel/adapter/db/sp/selectDB
```

Entrada:

```text
IN_PID    <- pid de CMS
IN_EXECID <- exceid de CMS
```

Salida:

```text
OUT_SUBSYSTEM
OUT_CODRES
OUT_MSGRES
```

### Encontrado

```text
OUT_CODRES = 0
OUT_MSGRES = REGISTROS ENCONTRADOS
```

Estado: `ENVIRONMENT_FOUND`.

La consulta no recibe el ambiente seleccionado. Si encuentra una transacción
en cualquier subsistema, se muestra el ambiente real y se bloquea cualquier
nuevo registro. Un subsistema desconocido se muestra de forma controlada y
también bloquea el registro.

### Ausencia confirmada

```text
OUT_CODRES = -1
OUT_SUBSYSTEM = nil
OUT_MSGRES contiene ORA-01403: no data found
```

Solo esta combinación habilita el registro. Estado:
`ENVIRONMENT_NOT_FOUND`.

### Error de consulta

`ORA-01422`, cualquier código no reconocido, SOAP Fault, HTTP no exitoso, XML
vacío/inválido, campos ausentes, error de conexión o timeout bloquean el
registro.

No depender únicamente de `OUT_CODRES = -1`, ya que se utiliza tanto para
`ORA-01403` como para `ORA-01422`.

## Contrato SOAP `createEsb`

```text
POST http://10.4.67.58:10002/Parallelism/createEsb
Content-Type: text/xml; charset=utf-8
SOAPAction: "ESBPRD"
Namespace: http://xmlns.oracle.com/pcbpel/adapter/db/sp/ESBPRD
```

Entrada:

```text
IN_PID          <- pid de CMS
IN_EXECID       <- exceid de CMS
IN_SUBSYSTEM    <- valor interno del ambiente seleccionado
IN_EXTERNALID   <- external_id de CMS
IN_APPTNUMBER   <- vacío por ahora
```

Éxito confirmado:

```text
OUT_CODRES = 0
OUT_MSGRES = TRANSACCION REALIZADA CON EXITO
```

Cualquier otra respuesta se clasifica como `REGISTER_FAILED`.

Después del éxito se ejecuta nuevamente `queryESb`. Solo se muestra éxito si
la verificación encuentra el mismo subsistema solicitado.

Secuencia por resultado de `createEsb`:

- `SUCCESS`: esperar, actualizar CMS y ejecutar la verificación final;
- `FAILED`: no esperar, no actualizar CMS y no reintentar;
- `TIMEOUT`: esperar, verificar primero con `queryESb` y actualizar CMS sólo si
  se confirma el mismo subsistema solicitado.

## Catálogo de ambientes

```text
Etiqueta UI   Valor SOAP
PMX3          DIVPMX2
PMX1          DIVPMX1
PMX4          DIV01
```

El mapeo se mantiene en ConfigMap y no se duplica en el código.

El proyecto OpenShift `testing-pmx3` no debe confundirse con los ambientes
funcionales PMX1, PMX3 y PMX4.

## Timeouts e idempotencia

```text
CMS_QUERY_TIMEOUT   = 5 segundos
SOAP_QUERY_TIMEOUT  = 5 segundos
SOAP_REGISTER_TIMEOUT = 5 segundos
CMS_POST_REGISTER_UPDATE_DELAY = 1 segundo
```

No hay reintentos automáticos de `createEsb`.

Si `createEsb` excede el timeout:

1. no repetir el registro;
2. consultar `queryESb`;
3. confirmar si fue creado;
4. si no puede verificarse, terminar como `REGISTER_UNVERIFIED` y requerir
   revisión operativa.

Mientras el destino no tenga una restricción única confirmada:

- aplicación con una réplica;
- estrategia `Recreate`;
- registro serializado dentro del pod;
- revalidación con `queryESb` inmediatamente antes de `createEsb`.

Estas medidas reducen duplicados, pero no sustituyen una garantía de unicidad
del sistema de destino.

## Configuración y secretos

ConfigMap:

- URL JDBC de Informix;
- URLs SOAP;
- timeouts;
- tamaño del pool;
- mapeo de ambientes.
- retraso entre `createEsb` y el reenvío CMS.

Secret `tools`:

```text
CMS_DB_USER
CMS_DB_PASSWORD
```

El datasource Informix utiliza una conexión JDBC normal para ejecutar el
`UPDATE` parametrizado de reenvío. El permiso efectivo lo controla la
credencial de Informix; la sentencia individual utiliza auto-commit y la
integración JTA permanece deshabilitada. PostgreSQL continúa siendo un
datasource separado y este cambio no modifica el Mapa operativo OCP.

## Estados funcionales

```text
CMS_NOT_FOUND
CMS_FOUND
ENVIRONMENT_FOUND
ENVIRONMENT_NOT_FOUND
REGISTER_SUCCESS
REGISTER_FAILED
REGISTER_UNVERIFIED
QUERY_ERROR
DATABASE_ERROR
TIMEOUT
```

La UI transforma los códigos en mensajes amigables y no basa la lógica en
textos libres.

## Pruebas de regresión obligatorias

1. AccessID inexistente: `CMS_NOT_FOUND`.
2. Encontrada en otro subsistema: mostrar ambiente real y bloquear registro.
3. `ORA-01403`: habilitar registro.
4. `ORA-01422`: `QUERY_ERROR`, sin registro.
5. Timeout Informix o SOAP alrededor de cinco segundos, sin registro.
6. Éxito solo después de verificación en el subsistema solicitado.
7. Timeout de `createEsb`: sin reintento; verificar o
   `REGISTER_UNVERIFIED`.
8. Cambiar AccessID o ambiente después de validar oculta el botón y exige nueva
   consulta.
9. `createEsb` exitoso: esperar, actualizar todas las coincidencias CMS y luego
   verificar el subsistema.
10. `UPDATE` con cero filas, error o timeout: conservar el resultado del
    registro y mostrar `No se pudo reenviar desde CMS`.
11. Timeout de `createEsb` confirmado por `queryESb`: actualizar CMS después de
    confirmar; si no se confirma, no ejecutar el `UPDATE`.

## Evidencia y pendientes

El target Docker `test` de `0.3.2` compiló 39 fuentes Java 17 y ejecutó 14
pruebas sin fallos, incluidas seis pruebas nuevas del flujo de registro y
reenvío CMS. Todavía se requiere validar el permiso `UPDATE`, las filas
afectadas y los resultados SOAP contra el entorno real.
