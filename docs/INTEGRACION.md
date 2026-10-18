# Operación integrada del Paso 6

Ejecutar desde `digitalfix-infra`. Esta guía prepara el despliegue; los comandos de
arranque no se ejecutaron contra Oracle/AWS durante la implementación.

## 1. Preparar configuración, imágenes y volúmenes

Completar `apps/.env` sin sobrescribir secretos existentes. Los prefijos
`WORKORDERS_`, `NOTIFY_`, `REPORT_`, `ADMIN_` se mapean a las variables RabbitMQ de
cada aplicación. Mantener cuatro usuarios distintos, sin tags administrativos.
SMTP requiere HOST, PORT, FROM y, cuando AUTH=true, USERNAME/PASSWORD. Para 587:
STARTTLS=true y SSL=false; para 465: SSL=true y STARTTLS=false. Usar remitente y
destinatarios autorizados. Nunca probar tráfico real con credenciales SMTP falsas.

```powershell
docker compose --env-file apps/.env -f docker-compose.yml config --quiet
docker volume inspect digitalfix-mq_rabbitmq1-data digitalfix-mq_rabbitmq2-data digitalfix-notify_notify-data digitalfix-report_report-data --format '{{.Name}}'
docker ps -a --filter label=com.docker.compose.project=apps
docker ps --filter volume=digitalfix-notify_notify-data
docker ps --filter volume=digitalfix-report_report-data
docker compose --env-file apps/.env -f docker-compose.yml build
```

Los volúmenes existentes observados el 18/10/2026 son los cuatro indicados arriba.
Notify usa UID/GID 10001 y Report 10003, `/data` privado; se reutilizan los archivos
y metadatos sin copiarlos ni cambiar propietarios. Las variables NOTIFY_VOLUME_NAME
y REPORT_VOLUME_NAME permiten seleccionar otros nombres **previamente inspeccionados**.
`external: true` evita crear silenciosamente un volumen nuevo vacío.

Solo en un host nuevo, después de confirmar que no hay datos que recuperar, crear
los volúmenes Notify/Report con `docker volume create NOMBRE`. Al montarse por primera
vez, Docker inicializa `/data` desde la imagen. Verificar UID/permisos antes de recibir
mensajes. No crear volúmenes nuevos para ocultar un error de nombre en un host existente.
Respaldar los volúmenes completos antes de una migración, con consumidores detenidos;
incluyen documentos, `.identidad` y `.ok`. No se requiere migración física en este host.

## 2. RabbitMQ y cuentas

```powershell
docker compose --env-file mq/.env -f mq/compose.yml up -d
docker compose --env-file mq/.env -f mq/compose.yml exec -T rabbitmq1 rabbitmqctl cluster_status
```

Deben figurar rabbit@rabbitmq1 y rabbit@rabbitmq2 activos y sin particiones.
No cambiar nombre de proyecto `digitalfix-mq`, cookie ni volúmenes del clúster existente.
Acceder a Management en `http://127.0.0.1:15672` con una cuenta operadora autorizada.
En Admin → Users, crear únicamente las cuentas ausentes, sin tags y con contraseña
única; no reemplazar usuarios/contraseñas existentes. Conceder permisos solo en `digitalfix`:

| Cuenta | Configure | Write | Read |
|---|---|---|---|
| digitalfix_workorders | `^$` | `^cmd\.(direct\|topic)$` | `^$` |
| digitalfix_notify | `^(cmd\.(direct\|topic\|dead\.dlx)\|q\.cmd\.(email\|dispatch)(\.dlq)?)$` | `^(cmd\.dead\.dlx\|q\.cmd\.(email\|dispatch)(\.dlq)?)$` | `^(cmd\.(direct\|topic\|dead\.dlx)\|q\.cmd\.(email\|dispatch))$` |
| digitalfix_report | `^(cmd\.(direct\|topic\|dead\.dlx)\|q\.cmd\.report(\.dlq)?)$` | `^(cmd\.dead\.dlx\|q\.cmd\.report(\.dlq)?)$` | `^(cmd\.(direct\|topic\|dead\.dlx)\|q\.cmd\.report(\.dlq)?)$` |

En la tabla las barras verticales están escapadas para Markdown. Copiar las expresiones
sin escapes de presentación desde los README originales de Notify/Report y
`digitalfix-ms-workorders/docs/MENSAJERIA.md`, o desde el verificador Python.
Para Rabbit Admin usar esta expresión en los tres permisos:

```text
^(?!(amq\.|rabbitmq\.|cmd\.|q\.cmd\.))[A-Za-z0-9][A-Za-z0-9._-]*$
```

El usuario inicial del broker no debe utilizarse en aplicaciones. En la inspección
local falta `digitalfix_workorders`; los otros tres usuarios ya tienen los permisos
esperados. No se modificó ninguno durante el Paso 6.

## 3. Cambiar de Compose sin consumidores duplicados

Antes de transferir servicios, detener nuevos cambios de estado desde la aplicación.
Completar la preparación Oracle autorizada descrita en DESPLIEGUE_PENDIENTE.md antes
de iniciar la nueva versión de Workorders. Los contenedores antiguos de Notify/Report
pueden mantenerse detenidos para rollback, sin eliminar sus volúmenes.

```powershell
# Solo al realizar efectivamente el cambio, una vez listos SMTP/cuentas/esquema.
docker compose --env-file apps/.env -f docker-compose.yml stop bff workorders
docker compose --env-file ../digitalfix-ms-notify/.env -f ../digitalfix-ms-notify/compose.yml stop notify
docker compose --env-file ../digitalfix-ms-report/.env -f ../digitalfix-ms-report/compose.yml stop report
docker compose --env-file ../digitalfix-ms-rabbit-admin/.env -f ../digitalfix-ms-rabbit-admin/compose.yml stop rabbit-admin
docker compose --env-file apps/.env -f docker-compose.yml up -d --no-deps notify report
```

Esperar que ambas aplicaciones conecten y declaren topología. No arrancar Notify
con SMTP inválido sobre las colas de negocio; procesaría mensajes reales como fallidos.

```powershell
docker compose --env-file apps/.env -f docker-compose.yml logs --tail 60 notify report
python scripts/verificar_paso6.py --env-file apps/.env --broker --exigir-listo
```

Exigir salida 0: exchanges durables direct/topic/DLX, seis colas classic durables,
bindings direct/topic/DLQ, un consumidor por principal, volúmenes y permisos correctos.
Esto verifica preparación, no demuestra entrega SMTP. Si falla, corregir y mantener
el productor detenido. `depends_on` por sí solo no verifica bindings ni preparación.

## 4. Iniciar productores y API privada

```powershell
docker compose --env-file apps/.env -f docker-compose.yml up -d catalog workorders bff rabbit-admin
docker compose --env-file apps/.env -f docker-compose.yml ps
curl.exe -i http://127.0.0.1:8080/healthz
curl.exe -i http://127.0.0.1:8085/healthz
curl.exe -i http://127.0.0.1:8080/api/perfil
curl.exe -i http://127.0.0.1:8085/api/rabbit/queues/prueba
```

Health endpoints deben responder 200, endpoints protegidos sin token 401.
Para acceso remoto a Rabbit Admin, usar VPN o túnel SSH con puerto local 8085 hacia
127.0.0.1:8085 del host. No añadir reglas públicas de ingreso a 8085 ni rutas en Gateway.
La prueba autorizada requiere JWT Entra, scope access_as_user y rol Admin; el health
200 no valida eso. BFF conserva su publicación 8080 y su protección de red anterior.

## 5. Evidencias funcionales posteriores

Con usuarios, órdenes y correos de prueba autorizados, registrar IDs y resultados:

| Operación | Resultado esperado |
|---|---|
| Crear orden | emailContacto persistido, identidad oid del JWT |
| ASIGNADA | Catalog descuenta una vez; email cliente + ticket PDF guardado/enviado al técnico |
| Repetir ASIGNADA | Rechazo sin segundo descuento ni publicación |
| EN_DESPLAZAMIENTO | Email cliente por topic email.en_camino |
| CERRADA | Email cliente + informe PDF de Report |
| PATCH contactos en CERRADA | Solo contactos; sin stock/estado/publicaciones adicionales |

Guardar evidencia de `.ok` y hash del PDF, reiniciar únicamente el consumidor integrado
y verificar que persisten y que un mismo eventId no repite el efecto. No borrar registros
para forzar la prueba. Probar errores/DLQ en vhost y almacenamiento aislados, no en colas
con datos reales. ACK manual, NACK sin requeue y reintentos limitados permanecen en los
consumidores existentes; no se redefinen desde Compose.

Si se revierte la centralización, detener primero Notify/Report/Rabbit Admin centrales,
mantener los mismos volúmenes y luego iniciar sus proyectos anteriores. Nunca ejecutar
ambos en paralelo. Una reversión del código no debe borrar columnas Oracle ni datos.
