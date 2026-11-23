# Flujo y contratos Kafka de DigitalFix

Decisión de diseño para [Infra #15](https://github.com/arieeliiu/digitalfix-infra/issues/15).
Este documento define el estado objetivo; **no acredita una integración implementada**.
Base revisada: Infra `c966ac9`, Workorders `6ea0d80` y Audit `143b71d`.

## 1. Estado y decisión

Hoy Workorders publica creación, cambios de estado y eliminación en `workorders.events`;
Audit los guarda en Oracle. `audit.timeline` está creado, pero no tiene productor ni
consumidor. Report genera PDF por RabbitMQ: no existe un consumidor Kafka de Report.

Se conservan los nombres existentes y se separan dos necesidades reales:

| Tópico | Finalidad | Productor | Consumidor / grupo | Estado |
|---|---|---|---|---|
| `workorders.events` | Hechos del ciclo de vida de la orden | Workorders | Audit / `audit` | Implementado; falta validación conjunta |
| `audit.timeline` | Trazabilidad de ediciones de datos, contactos y repuestos | Workorders | Audit / `audit` | Pendiente |

```text
API HTTP con JWT → Workorders → commit de orden + historial local
                                  ├→ workorders.events ─┐
                                  └→ audit.timeline ───┴→ Audit (grupo audit)
                                                          ├→ Oracle: auditoria_eventos
                                                          └→ API HTTP de solo lectura
                                     fallos definitivos → DLT propio del tópico

Workorders → RabbitMQ → Notify (correo/ticket) y Report (PDF): se conserva
```

Audit reúne ambos flujos en una única consulta de historial. No se reenvían eventos
de un tópico al otro ni se añade un microservicio para repetir la misma información.
El nombre `audit.timeline` identifica las ediciones auditables; el historial completo
es la unión persistida de ambos tópicos, no solamente ese log.

La pauta de `Encargo_cloud.pdf` (EP5, rúbrica de productores/consumidores) exige
interacción con dos tópicos. `Presentacion_cloud.pdf` corresponde a EP6 y exige
demostrar el flujo, particiones, réplicas, retención, grupos, offsets y lag.
Esas pautas no fijan los nombres de los tópicos ni exigen compactar el historial.
Las referencias anteriores a «Caso 6 §9» no sustituyen esas pautas; si el caso
específico añade condiciones, deberán contrastarse antes de implementar.

## 2. Operaciones y ausencia de duplicaciones

| Operación confirmada en Workorders | Tópico | `type` | `payload.accion` |
|---|---|---|---|
| Crear orden | `workorders.events` | `ORDEN_CREADA` | `CREACION` |
| Transición real de estado | `workorders.events` | `ORDEN_ESTADO_CAMBIADO` | `CAMBIO_ESTADO` |
| Eliminar orden | `workorders.events` | `ORDEN_ELIMINADA` | `ELIMINACION` |
| `PUT /api/workorders/{id}` modifica datos | `audit.timeline` | `ORDEN_DATOS_ACTUALIZADOS` | `EDICION_DATOS` |
| `PATCH /api/workorders/{id}/contactos` modifica contactos | `audit.timeline` | `ORDEN_CONTACTOS_ACTUALIZADOS` | `EDICION_CONTACTOS` |
| `PUT /api/workorders/{id}/repuestos` modifica cantidades | `audit.timeline` | `ORDEN_REPUESTOS_ACTUALIZADOS` | `EDICION_REPUESTOS` |

Solo se emite si el estado persistido cambia, comparando valores normalizados.
Repetir un estado, guardar los mismos datos, reintentar las cantidades ya aplicadas,
una petición rechazada o una transacción revertida no genera un evento nuevo.
Los contactos y repuestos ajustados dentro de una asignación pertenecen a la
transición: no generan además eventos de edición por esa misma operación.

Cada cambio guarda su entrada en `historial_ordenes` dentro de la transacción y
prepara un snapshot inmutable para publicar después del commit. Esto amplía el
historial existente con tres acciones; no crea una segunda tabla en Workorders.
Se mantienen las autorizaciones y reglas de stock actuales.

## 3. Contrato JSON 1.0

Se conserva el sobre actual y el payload `EventoOrden`, sin nuevos campos de datos
personales. Los nuevos tipos solo se aceptan en `audit.timeline`; los tres tipos
existentes continúan sin cambios en `workorders.events`.

| Campo | Regla del contrato objetivo |
|---|---|
| `schemaVersion` | Literal `1.0`; otra versión es un error permanente |
| `type` | Uno de los seis tipos de la tabla anterior; debe coincidir con tópico y acción |
| `eventId` | UUID obligatorio, distinto por cambio real; conservarlo al reenviar el mismo evento |
| `timestamp` | Instant ISO-8601 UTC de la operación, igual a `payload.fecha` |
| `traceId` | UUID de trazabilidad; conservarlo si se reenvía el sobre |
| `correlationId` | `ordenId` como texto decimal; también header Kafka UTF-8 |
| `payload.ordenId` | Long positivo |
| `payload.accion` | Acción de la tabla anterior; máximo 20 caracteres |
| `payload.estadoAnterior`, `payload.estadoNuevo` | Estados de Workorders, máximo 30 caracteres; reglas debajo |
| `payload.tecnicoId` | Identificador actual del técnico o null; máximo 100 caracteres |
| `payload.actorId` | `oid` del JWT validado, obligatorio, máximo 100 caracteres |
| `payload.actorRoles` | Roles conocidos del JWT unidos por coma, o null; máximo 100 caracteres |
| `payload.fecha` | Instant de la operación, obligatorio |

Creación: null → `CREADA`; transición: estado anterior → nuevo; eliminación:
estado final → null. En las ediciones ambos estados son iguales al estado actual.
Estados válidos: `CREADA`, `ASIGNADA`, `EN_DESPLAZAMIENTO`, `EN_EJECUCION`, `CERRADA`
y `CANCELADA`; la validez de una transición la decide Workorders.
La auditoría de una edición registra quién, qué sección y cuándo, no los valores
anteriores/nuevos de correos, descripción, dirección o repuestos.

Ejemplo objetivo de `audit.timeline` (aún no emitido por la aplicación):

```json
{
  "schemaVersion": "1.0",
  "type": "ORDEN_CONTACTOS_ACTUALIZADOS",
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "timestamp": "2026-11-23T15:00:00Z",
  "traceId": "550e8400-e29b-41d4-a716-446655440001",
  "correlationId": "22",
  "payload": {
    "ordenId": 22,
    "accion": "EDICION_CONTACTOS",
    "estadoAnterior": "ASIGNADA",
    "estadoNuevo": "ASIGNADA",
    "tecnicoId": "tec-003",
    "actorId": "00000000-0000-4000-8000-000000000022",
    "actorRoles": "Admin",
    "fecha": "2026-11-23T15:00:00Z"
  }
}
```

Audit necesita validar versión y coherencia semántica, además de sus validaciones
actuales. Se desplegará primero el lector compatible y después el productor nuevo.
En HTTP no se aceptará un actor de auditoría tomado del body/query como respaldo
de un `oid` ausente: habrá que revisar también ese fallback en cambios de estado.
No se envían JWT, contraseñas, correos, direcciones ni entidades JPA en Kafka.

## 4. Particiones, retención y errores

Ambos tópicos usan key String = `ordenId`, 3 particiones y factor de replicación 3.
La clave agrupa una orden dentro de cada tópico mientras se mantenga el número
de particiones. Kafka conserva el orden de escritura en cada partición, no un
orden total entre tópicos, solicitudes concurrentes o reenvíos desde DLT.
La API ordena por fecha e identificador como presentación; no representa causalidad global.

| Tópico | Política objetivo | Retención objetivo | Justificación |
|---|---|---|---|
| `workorders.events` | `delete` | 7 días (`604800000` ms) | Ventana de reproceso del ciclo de vida; ya configurada |
| `audit.timeline` | `delete` | 30 días (`2592000000` ms) | Conservar todas las ediciones dentro de la ventana |
| `workorders.events.audit.DLT` | `delete` | 14 días (`1209600000` ms) | Investigación de fallos del consumidor de ciclo de vida; ya creado |
| `audit.timeline.audit.DLT` | `delete` | 14 días (`1209600000` ms) | Investigación de fallos del consumidor de ediciones; pendiente de crear |

La configuración **actual** de `audit.timeline` es `compact,delete`. Con key
`ordenId`, compactar puede eliminar ediciones anteriores de la misma orden antes
de 30 días. Se cambia a `delete` en la tarea de Infra, manteniendo la clave para
particionar por orden. No se elegirá `eventId` como clave solo para eludir la
compactación. La retención se aplica por segmentos y también puede estar limitada
por tamaño: no es una garantía de borrado exacto al cumplir el plazo.
Oracle conserva el historial consultable; su conservación no depende del log Kafka.

Audit mantiene el grupo `audit`, suscrito a ambos tópicos, con auto-commit desactivado
y commit del offset por registro después de persistir o reconocer un duplicado.
`earliest` solo se aplica cuando no hay un offset válido del grupo; no rebobina cada
reinicio. Las instancias del grupo se reparten las particiones, no reciben todas una copia.

La unicidad de `eventId` en Oracle permite reentregas sin duplicar filas. Solo un
duplicado comprobado se ignora; otro error de integridad debe propagarse al manejo
de errores. Guardar tópico, partición y offset permite distinguir ambos orígenes:
partición y offset por sí solos no identifican un registro entre tópicos.

JSON/contrato inválido va al DLT del tópico de origen; fallos temporales se reintentan
3 veces con espera de 1 s y, si persisten, van al DLT. Cada DLT tendrá 3 particiones
y RF 3; se conserva la partición original, key, valor original y headers de diagnóstico.
Un fallo al publicar al DLT **no debe considerarse recuperación exitosa** ni permitir
avanzar el offset de ese registro; se verificará con un fallo real controlado.
El reproceso de DLT será manual y conservará `eventId`; no borrará mensajes de negocio.

`acks=all` confirma escritura según los ISR, no persistencia Oracle ni procesamiento.
Se requiere `min.insync.replicas=2` en los tópicos de negocio y DLT para no aceptar
escrituras con una sola réplica sincronizada. No se promete exactamente una vez:
sin Outbox hay una ventana de pérdida entre commit y envío, y un timeout puede
tener resultado incierto. Idempotencia del productor no elimina esa ventana.

El DLT existente `workorders.events.report.DLT` queda reservado e inactivo.
No se añade un grupo `report` ni se genera otro PDF desde Kafka por este diseño.

## 5. Cambios pendientes por repositorio

| Repositorio / tarea | Cambio delimitado y dependencia |
|---|---|
| Infra #12 | Completar listeners/configuración externa; aplicar política `delete` de timeline, ISR mínimo y nuevo DLT. Verificar 3 particiones/RF 3. `--if-not-exists` no actualiza tópicos ya creados: consultar y cambiar configs explícitamente, sin recrearlos |
| Workorders #13 | Añadir las tres acciones de edición, comparación para evitar no-op y ruteo a ambos tópicos; reutilizar sobre, historial y callback tras commit. Propagar actor/roles desde JWT, eliminando fallback al query param. Depende de este contrato y del lector Audit compatible |
| Audit: corrección de integridad | Distinguir duplicado real de otros errores de persistencia; verificar que el resto alcanza reintentos/DLT. Puede hacerse de forma independiente |
| Audit #1 | Aceptar ambos tópicos/tipos; validar contrato, conservar tópico de origen en Oracle y resolver DLT por origen. Mantener API y roles Admin/Auditor. Depende del contrato e Infra; debe estar listo antes de habilitar los eventos nuevos |
| Infra: Compose de aplicaciones | Integrar Audit y red Kafka, activar productor y proporcionar `CATALOG_URL`/brokers por entorno. `localhost` de Kafka sirve a Java en el host, no a un contenedor. Depende de los PR de servicios |
| Infra: validación conjunta | Ejecutar la matriz siguiente con servicios reales, registrar evidencia y pendientes. Depende de los PR anteriores |

Los cambios Oracle se prepararán y probarán en un entorno aislado; no se ejecutará
DDL ni arranque con `ddl-auto=update` contra RDS como parte de estas tareas locales.
BFF #23 y Frontend #32 consumirán HTTP, no Kafka. Antes de implementarlos se debe
resolver el alcance de acceso por propietario frente a los roles Admin/Auditor
actuales, sin ampliar permisos implícitamente.

## 6. Matriz de comprobación posterior (no ejecutada en esta issue)

| Prueba real | Evidencia esperada |
|---|---|
| Crear, transicionar y eliminar una orden de prueba | Un evento por cambio en `workorders.events`; misma key por orden; ninguna copia en timeline |
| Editar datos, contactos y repuestos | Un evento por edición real en `audit.timeline`; Audit agrega filas consultables sin reemplazar las anteriores |
| Repetir valores/estado o provocar rollback | Cero eventos nuevos y cero entradas nuevas de historial |
| Consultar Audit con JWT | `/api/audit/ordenes/{id}/timeline` reúne ambos flujos; Admin/Auditor autorizados; sin token 401 y sin rol 403 |
| Reentregar el mismo sobre | Una fila por `eventId`, incluso tras reiniciar Audit |
| Contrato inválido y base de prueba indisponible | DLT correcto por origen; reintentos solo en fallos temporales; sin datos personales en logs |
| Impedir publicación al DLT | No se omite el registro; el offset no avanza como si se hubiese recuperado |
| Kafka UI / CLI | Dos tópicos de negocio con 3 particiones/RF 3, configs verificadas, grupo `audit` asignado a ambos, offsets progresan y lag llega a 0 al completar el lote |
| Asignar, pasar a desplazamiento y cerrar | RabbitMQ conserva correos/ticket/PDF con sus reglas e idempotencia; Kafka no crea envíos/PDF adicionales |

Guardar resultados y comandos solo después de ejecutarlos. H2/mocks ayudan a probar
contratos, pero no certifican Oracle, brokers, SMTP ni JWT de Entra reales.
AWS, migraciones RDS y el despliegue cloud exigido por EP6 quedan para una fase
posterior coordinada; esta decisión no los considera completados.

Referencias: [diseño de Kafka: orden y compactación](https://kafka.apache.org/35/design/design/)
y [configuración de tópicos](https://kafka.apache.org/35/generated/topic_config.html).
