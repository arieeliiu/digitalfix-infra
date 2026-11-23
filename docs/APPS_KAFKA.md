# Aplicaciones con Kafka y Audit (Infra #18)

La entrada `docker-compose.yml` incluye `apps/compose.yml` y fija el proyecto `apps`.
Requiere Docker Compose con soporte `include` (2.20 o posterior). No levanta brokers,
crea volúmenes de negocio ni ejecuta migraciones por sí mismo.

## Conexiones

| Servicio | Redes | Dependencias configuradas |
|---|---|---|
| BFF | appnet | Catalog `http://catalog:8081`, Workorders `http://workorders:8082`, Audit `http://audit:8083` |
| Catalog | appnet | Oracle por entorno |
| Workorders | appnet, messaging, kafka | Catalog interno, RabbitMQ y Kafka por entorno, Oracle |
| Audit | appnet, kafka | Kafka y conexión Oracle propia por entorno; grupo `audit` |
| Notify / Report | messaging | RabbitMQ, volúmenes externos existentes; SMTP solo Notify |
| Rabbit Admin | messaging | Usuario Rabbit propio, issuer/audience de Entra; HTTP en `127.0.0.1:8085` |

Audit no publica puertos al host; se consulta desde BFF en la red privada.
La variable de URL Audit del BFF prepara BFF #23, pero no añade por sí sola una ruta
de historial. El healthcheck TCP solo indica que el proceso escucha; no certifica
Oracle, autenticación, entrega de correo ni consumo Kafka.

## Variables y redes

Revisar `apps/.env.example` y completar un `.env` local sin sobrescribir los valores
existentes. El ejemplo tiene endpoints inválidos y credenciales ilustrativas:
sirve para validar estructura, no para arrancar aplicaciones funcionales.

- `RABBITMQ_NETWORK_NAME`: red existente. En el entorno inspeccionado es
  `digitalfix-messaging`, con aliases `rabbitmq1` y `rabbitmq2` y puerto interno 5672.
- `RABBITMQ_ADDRESSES`: direcciones alcanzables desde contenedores. Sustituye el
  antiguo `RABBITMQ_HOST`/`extra_hosts`; revisar el `.env` anterior antes de migrar.
  Si se usan redes/aliases distintos, configurarlos explícitamente. Para brokers en
  otro host, usar sus direcciones privadas y puertos publicados; no confundirlos
  con los puertos internos de una red Docker compartida.
- `KAFKA_NETWORK_NAME`: red existente del proyecto Kafka. Por defecto del ejemplo,
  `kafka_kafkanet`; en la prueba aislada se llamó
  `digitalfix-kafka-verificacion_kafkanet` y debe indicarse ese valor si se reutiliza.
- `KAFKA_BOOTSTRAP_SERVERS`: `kafka1:29092,kafka2:29092,kafka3:29092` dentro de esa red.
  No usar los listeners `localhost` anunciados para Java en el host.
- `DB_*` para Catalog/Workorders y `AUDIT_DB_*` para Audit. Se declaran por separado
  para evitar asumir credenciales o permisos de esquema compartidos.
- `ENTRA_ISSUER`/`ENTRA_AUDIENCE`: se propagan a los resource servers y Rabbit Admin.
  Los permisos efectivos siguen definidos por cada servicio.
- Cuatro cuentas Rabbit independientes: Workorders, Notify, Report y Rabbit Admin.
  No se crean usuarios ni se ajustan permisos al validar Compose.

Los build contexts por defecto esperan los repositorios hermanos habituales.
Para probar ramas en worktrees sin copiarlas, pueden definirse
`BFF_BUILD_CONTEXT`, `CATALOG_BUILD_CONTEXT`, `WORKORDERS_BUILD_CONTEXT`,
`AUDIT_BUILD_CONTEXT`, `NOTIFY_BUILD_CONTEXT`, `REPORT_BUILD_CONTEXT` y
`RABBIT_ADMIN_BUILD_CONTEXT`. Usar rutas absolutas o relativas a `apps/`.
Esto no cambia ramas ni guarda modificaciones de otros repositorios.

## Validación y secuencia posterior

```powershell
# Desde la raíz de Infra; no conecta a Oracle ni inicia servicios.
docker compose --env-file apps/.env.example -f docker-compose.yml config --quiet
docker compose --env-file apps/.env.example -f apps/compose.yml config --quiet
# Solo construye imágenes; usar fuentes revisadas para los dos servicios.
docker compose --env-file apps/.env.example -f docker-compose.yml build audit workorders
```

Comprobado el 23/11/2026: ambas entradas Compose válidas; configuración resuelta de
siete servicios con Catalog interno, Kafka habilitado, Audit sin puertos publicados,
Rabbit Admin en loopback, redes y volúmenes externos. Se construyeron las imágenes de
Audit y Workorders desde sus worktrees. No se arrancó este Compose contra RDS.

Antes de activar aplicaciones:

1. Revisar/mergear los PR de Infra #16/#17, Audit #4/#5 y Workorders #15. Este PR
   solo configura el conjunto y no integra esas ramas en `main`.
2. Revisar migraciones de historial y origen Kafka en un entorno autorizado de Oracle.
   Los servicios conservan `ddl-auto=update`: arrancarlos puede ejecutar DDL.
3. Inspeccionar redes, volúmenes y contenedores activos. No iniciar consumidores
   duplicados con los Compose independientes. No recrear RabbitMQ desde un archivo
   histórico sin contrastar su versión y volúmenes con el clúster existente.
4. Confirmar brokers/tópicos listos y permisos de los cuatro usuarios Rabbit; preparar
   SMTP de prueba autorizado antes de activar Notify.
5. Iniciar consumidores compatibles antes del productor nuevo y exigir la validación
   conjunta de Infra #19. El orden no se garantiza con `depends_on` entre proyectos.

AWS, Security Groups, RDS, Entra y SMTP externos no se modificaron. El despliegue
posterior necesita revisar hosts anunciados/puertos privados y orígenes CORS, sin
introducir direcciones o credenciales de AWS en el repositorio.
