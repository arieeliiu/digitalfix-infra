# Kafka de DigitalFix

Clúster de streaming para analítica y auditoría (Caso 6, §7 y §9).
Base: Guía 3.2.1 (1 Zookeeper + 1 broker), ampliada a 3 nodos de cada uno.

> **Estado:** configuración local para Docker Desktop. El despliegue cloud y su
> demostración, exigidos por EP6, quedan pendientes de una fase coordinada.
> [Flujo y contratos objetivo](CONTRATOS.md): responsabilidades de ambos tópicos,
> diferencias con la configuración actual y verificaciones pendientes (Infra #15).

## Arquitectura

```text
Tu PC (IntelliJ: Workorders y ms-audit; ms-report sigue usando RabbitMQ)
  localhost:9092 -> kafka1      localhost:8090 -> Kafka UI
  localhost:9093 -> kafka2
  localhost:9094 -> kafka3

red kafkanet (interna)
  zookeeper1, zookeeper2, zookeeper3   (ensamble, puerto 2181, sin publicar)
  kafka1, kafka2, kafka3               (listener interno kafkaN:29092)
  kafka-ui                             (usa kafkaN:29092)
  kafka-init-topicos                   (crea los tópicos y termina)
```

| Servicio | Imagen | Puerto en tu PC | Volumen |
|---|---|---|---|
| zookeeper1-3 | `confluentinc/cp-zookeeper:7.5.0` | Ninguno | `zookeeperN_data`, `zookeeperN_log` |
| kafka1-3 | `confluentinc/cp-kafka:7.5.0` | 9092, 9093, 9094 | `kafkaN_data` |
| kafka-ui | `provectuslabs/kafka-ui:latest` | 8090 (el 8080 lo usa el BFF) | — |
| kafka-init-topicos | `confluentinc/cp-kafka:7.5.0` | — | — |

### Diferencias con la Guía 3.2.1

| Configuración | Guía | DigitalFix | Por qué |
|---|---|---|---|
| Zookeeper | 1 nodo | 3 nodos (`ZOOKEEPER_SERVER_ID`, `ZOOKEEPER_SERVERS`) | Caso §7. Con 2 de 3 vivos el ensamble sigue funcionando |
| Brokers | 1 | 3 (`KAFKA_BROKER_ID` 1-3) | Caso §7 |
| `KAFKA_ZOOKEEPER_CONNECT` | 1 host | Los 3 Zookeeper | Si cae uno, el broker usa otro |
| Listener externo | `localhost:9092` | 9092 / 9093 / 9094 | Dos contenedores no pueden publicar el mismo puerto |
| `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR` | 1 | 3 | PPT 3.3.1: RF=3 es estándar de producción |
| `KAFKA_AUTO_CREATE_TOPICS_ENABLE` | `false` | `false` | Los tópicos se crean con la configuración del caso |
| Kafka UI | 8080 | 8090 | El BFF ocupa 8080 en local |

## Tópicos (Caso §9)

Los crea `kafka-init-topicos` con `kafka-topics --create --if-not-exists` (se puede repetir sin error).
La tabla describe el Compose actual, no todos los flujos implementados.
`--if-not-exists` no modifica la configuración de un tópico existente.

| Tópico | Part. | RF | `cleanup.policy` | `retention.ms` | Propósito |
|---|---|---|---|---|---|
| `workorders.events` | 3 | 3 | `delete` | 604800000 (7 días) | Eventos de la orden. Key = `workorderId` (orden por orden de trabajo) |
| `audit.timeline` | 3 | 3 | `compact,delete` | 2592000000 (30 días) | Pendiente: auditoría de ediciones (ver contrato objetivo) |
| `workorders.events.audit.DLT` | 3 | 3 | `delete` | 1209600000 (14 días) | Fallidos definitivos de ms-audit |
| `workorders.events.report.DLT` | 3 | 3 | `delete` | 1209600000 (14 días) | Reservado; Report no tiene consumidor Kafka |

- La retención usa el máximo de cada rango del caso (3-7, 14-30, 7-14 días).
- **DLT por consumidor y origen:** Audit configura un destino propio en vez del
  valor por defecto `<tópico>.DLT`. Si otro grupo se incorpora en el futuro, deberá
  tener un DLT independiente para no mezclar sus fallos con los de Audit.
- `audit.timeline` aún no tiene productor/consumidor. El [contrato objetivo](CONTRATOS.md)
  define ediciones publicadas por Workorders y consumidas por Audit, su DLT propio y
  el cambio pendiente de `compact,delete` a `delete` para conservar el historial.

### Consumer groups

| Servicio | `group.id` | Lee |
|---|---|---|
| ms-audit (implementado) | `audit` | `workorders.events` |
| ms-audit (pendiente) | `audit` | `audit.timeline` |

Dentro de un grupo, las instancias se reparten las particiones. La suscripción actual
del código abarca 3 particiones y hasta 3 consumidores con trabajo asignado; al añadir
el segundo tópico habrá 6 particiones en la suscripción. Otro grupo leería de forma
independiente. No existe todavía un grupo `report` en el código de Report.

## Uso (desde la raíz de `digitalfix-infra`)

```powershell
docker compose -f kafka/compose.yml config --quiet   # valida sintaxis
docker compose -f kafka/compose.yml up -d
docker compose -f kafka/compose.yml ps               # kafka-init-topicos queda "Exited (0)": es normal
docker logs kafka-init-topicos                       # lista de tópicos creados
```

Kafka UI: <http://localhost:8090>.

Si tienes corriendo el laboratorio de la Guía 3.2.1, detenlo antes (`docker compose stop` en su carpeta):
usa los puertos 9092 y 8080.

Para detener sin perder datos:

```powershell
docker compose -f kafka/compose.yml stop
```

**Nunca** `docker compose down -v` ni `docker volume prune`: borra los tópicos y sus mensajes.

## Verificaciones

| Qué | Comando | Esperado |
|---|---|---|
| Rol de cada Zookeeper | `docker logs zookeeperN 2>&1 \| Select-String "LEADING\|FOLLOWING"` | 1 `LEADING`, 2 `FOLLOWING` |
| Brokers registrados | `docker exec zookeeper1 zookeeper-shell localhost:2181 ls /brokers/ids` | `[1, 2, 3]` |
| Controller | `docker exec zookeeper1 zookeeper-shell localhost:2181 get /controller` | `"brokerid":N` |
| Tópicos | `docker exec kafka1 kafka-topics --bootstrap-server kafka1:29092 --describe --topic workorders.events` | 3 particiones, `ReplicationFactor: 3`, ISR con 3 réplicas |

### Pruebas de fallo realizadas en local (22/11/2026)

- **Zookeeper:** al detener el líder, uno de los otros dos pasó a `LEADING`.
- **Controller:** al detener `kafka1` (controller), `/controller` pasó a `brokerid 3`; al volver,
  `kafka1` quedó como broker normal.
- **Leader de partición (PPT 3.3.1), pendiente de registrar:** al detener un broker, el controller
  debe elegir un nuevo leader entre los ISR. La partición no cambia, solo qué broker la atiende.

## Al desplegar en EC2

Pendiente para la fase de despliegue; no se realiza en la issue #15:

- En `KAFKA_ADVERTISED_LISTENERS`, reemplazar `localhost` por la IP privada de `ec2-kafka`
  en el listener `PLAINTEXT_HOST`.
- Security Group: abrir solo el puerto de Kafka hacia las EC2 de las apps (Caso §7: 9092).
  Revisar 9093/9094 o dejar un único puerto externo.
- Kafka UI no debe quedar expuesto a Internet.
- Las apps reciben los brokers por variable de entorno (`bootstrap-servers`), nunca fijos en el código.
