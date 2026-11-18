# digitalfix-infra

Contexto global en `../CLAUDE.md`. Aquí va solo lo propio de este repo.

Orquestación Docker de DigitalFix: Compose de aplicaciones, clúster RabbitMQ, plantillas de Dockerfile y documentación de despliegue.
**Es el repo con más riesgo de pérdida de datos**: los volúmenes guardan mensajes, PDF e idempotencia.

## Estructura

| Ruta | Contenido |
|---|---|
| `apps/compose.yml` | Aplicaciones para la EC2 `ec2-apps` (hoy no existe: ver *Pendientes*): bff, catalog, workorders, notify y report. Construye desde `../../digitalfix-ms-*` (los repos deben ser carpetas hermanas de `digitalfix-infra`). Se usa desde `apps/`: `docker compose --env-file .env up -d --build` |
| `apps/.env.example` | Placeholders sin secretos: `RABBITMQ_HOST` (IP privada de `ec2-mq`), usuario y contraseña RabbitMQ por servicio (`WORKORDERS_`, `NOTIFY_`, `REPORT_`), `DB_*`, `SMTP_*` y nombres de volúmenes |
| `mq/compose.yml` | Broker para la EC2 `ec2-mq` (hoy no existe: ver *Pendientes*): `rabbit1` y `rabbit2` (`rabbitmq:3.13-management`), red `mqnet`, volúmenes `rabbit1_data` y `rabbit2_data` |
| `mq/rabbitmq.conf`, `start-node.sh`, `verify.py`, `EVIDENCIAS.md` | Configuración del clúster, verificación y evidencias. Ver *Mensajería: pendiente* |
| `Dockerfile.template`, `dockerignore.template` | Dockerfile estándar que cada microservicio copia a su raíz. Puertos: bff 8080, catalog 8081, workorders 8082; notify y report sin `EXPOSE` |
| `scripts/verificar_paso6.py` | Inspección **de solo lectura** del Compose, broker y Docker |
| `docs/` | `INTEGRACION.md`, `PASO6_ESTADO.md`, `DESPLIEGUE_PENDIENTE.md` y `EVALUACION1_HISTORICA.md` (solo referencia) |

## Red y exposición (`apps/compose.yml`)

- Una sola red bridge, `appnet`. Los nombres `catalog` y `workorders` son los que usan las URLs de Docker (`DIGITALFIX_*_URL` y `CATALOG_URL`).
- Solo BFF publica `8080` (lo consume API Gateway). Catalog y Workorders usan `expose`; Notify y Report no tienen puertos.
- Workorders, Notify y Report llegan al broker como `rabbit1:5672,rabbit2:5673`, resueltos por `extra_hosts` a `RABBITMQ_HOST`. Vhost `digitalfix`. Workorders arranca con `DIGITALFIX_RABBIT_ENABLED=true`.
- `rabbit-admin` **no** está en el Compose actual (su repo tampoco está en esta carpeta).

## Mensajería: pendiente (no tocar sin decidirlo con el equipo)

El estado de `main` no es coherente y queda para resolverlo más adelante:

- `mq/rabbitmq.conf` forma el clúster con `rabbit@rabbitmq1` y `rabbit@rabbitmq2`, pero `mq/compose.yml` usa los hostnames `rabbit1` y `rabbit2`.
- `mq/compose.yml` usa `rabbitmq:3.13`, y los volúmenes del clúster validado se crearon con 4.2.9: no levantes 3.13 sobre esos volúmenes (downgrade no soportado).
- `mq/compose.yml` espera `RABBITMQ_USER` y `RABBITMQ_PASS`, pero `mq/.env.example` define `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS` y `RABBITMQ_ERLANG_COOKIE`: con ese `.env` el broker arranca sin usuario definido y sin cookie común.
- `mq/compose.yml` publica `5672`/`5673` y Management `15672`/`15673` en todas las interfaces. En AWS hay que restringirlos por Security Group (solo desde `ec2-apps` y por túnel SSH).
- La versión validada del clúster (`rabbitmq1`/`rabbitmq2`, 4.2.9, red `digitalfix-messaging`, sin AMQP publicado) es el commit `8962100`. Hay una rama local sin subir, `fix/revertir-mq-compose`, que revierte `mq/compose.yml` a esa versión; no es compatible con `apps/compose.yml` tal como está.
- `README.md`, `docs/INTEGRACION.md`, `scripts/verificar_paso6.py` y `mq/*` todavía describen el layout anterior (`docker-compose.yml` en la raíz, redes `digitalfix-backend` y `digitalfix-messaging`).

## Reglas (no negociables)

- **Prohibido:** `docker compose down -v`, `docker volume prune`, `docker volume rm` y borrar o recrear los volúmenes del broker (`rabbit1_data`, `rabbit2_data` y los del clúster validado), `digitalfix-notify_notify-data` y `digitalfix-report_report-data`.
- Los volúmenes de Notify y Report son `external: true` a propósito: nunca crees uno vacío para "arreglar" un error de nombre.
- No escales Notify ni Report, ni levantes su Compose propio junto al de `apps/` (consumidores duplicados sobre el mismo volumen).
- Cada aplicación usa su usuario RabbitMQ con permisos mínimos. Nunca el admin del broker en una app.
- Orden de arranque: broker → Notify y Report (declaran la topología) → Catalog → Workorders → BFF.
- Secretos solo en `apps/.env` y `mq/.env` (ignorados por git). Edita solo los `.env.example`. Nunca imprimas contraseñas en logs ni salidas.
- Los cambios de variables o servicios se reflejan en `.env.example`, `README.md`, `docs/INTEGRACION.md` y `scripts/verificar_paso6.py`.

## Validación sin arrancar nada

```powershell
docker compose --env-file apps/.env.example -f apps/compose.yml config --quiet
docker compose --env-file mq/.env.example -f mq/compose.yml config --quiet
```

## Pendientes del caso

- Resolver la mensajería (sección anterior) y actualizar la documentación al layout de `apps/compose.yml`.
- `kafka/compose.yml`: Zookeeper ×3, Kafka ×3 y Kafka UI, con los tópicos `workorders.events` (3/3, delete, 3 a 7 días), `audit.timeline` (3/3, compact+delete, 14 a 30 días) y `*.DLT`.
- Servicio `audit`, `rabbit-admin` y el administrador de Kafka en el Compose de apps.
- Base de datos: la RDS Oracle nueva ya existe y Catalog y Workorders crearon sus tablas. Falta actualizar `DB_*` en `apps/.env`, que aún apunta a la RDS vieja, y antes del despliegue volver la RDS a privada (1521 solo desde el SG de `ec2-apps`).
- Despliegue en AWS pospuesto: primero se terminan los arreglos en local. La cuenta AWS donde estaban `ec2-mq` y `ec2-apps` se quedó sin créditos, así que esas instancias hay que recrearlas y cambiarán sus direcciones (`RABBITMQ_HOST` en `apps/.env`, Security Groups y destino del VPC Link / API Gateway). No uses las IP anteriores. La guía paso a paso de red está en Claude Docs ("Guía de red AWS · DigitalFix").
