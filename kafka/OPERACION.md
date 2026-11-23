# Operación y verificación local de Kafka

Implementación de Infra #12. Conserva los nombres definidos en el
[PR de contratos #16](https://github.com/arieeliiu/digitalfix-infra/pull/16):
`workorders.events` y `audit.timeline`. No usa los nombres propuestos en la issue
original porque ya existe código integrado con `workorders.events`.

## Configuración y arranque

Desde la raíz de Infra:

```powershell
docker compose --env-file kafka/.env.example -f kafka/compose.yml config --quiet
docker compose --env-file kafka/.env.example -f kafka/compose.yml up -d
docker compose --env-file kafka/.env.example -f kafka/compose.yml ps -a
docker logs kafka-init-topicos
```

Para valores propios, crear `kafka/.env` sin sobrescribir uno existente y usarlo
en `--env-file`. Consultar los proyectos y volúmenes existentes antes de cambiar
`-p`: otro proyecto crea volúmenes distintos. No iniciar dos clústeres con los
mismos nombres de contenedores. La prueba de este PR usó expresamente el proyecto
aislado `digitalfix-kafka-verificacion`, sin datos anteriores de Kafka.

| Cliente | Bootstrap servers |
|---|---|
| Java en Windows, configuración por defecto | `localhost:9092,localhost:9093,localhost:9094` |
| Microservicio en la red Docker `kafkanet` de este proyecto | `kafka1:29092,kafka2:29092,kafka3:29092` |

`KAFKA_LISTENERS` fija los sockets internos; `KAFKA_ADVERTISED_LISTENERS` informa
las direcciones que utilizará el cliente después del bootstrap. No basta publicar
un puerto si el broker anuncia una dirección inaccesible.

`KAFKA_HOST`, `KAFKA_PORT1/2/3` y `KAFKA_UI_PORT` permiten configurar el acceso
desde el host. `KAFKA_BIND_ADDRESS=127.0.0.1` limita los puertos publicados a este
equipo. Para una red privada futura habrá que configurar host anunciado y bind
alcanzables y revisar las reglas de red; no se modifica AWS en esta tarea.
Kafka UI usa una versión fija, `v0.7.2`, y escucha por defecto en `localhost:8090`.

Los brokers tienen healthcheck y el inicializador espera los tres antes de actuar.
Si hay un error, termina con código distinto de cero; no presenta un arranque
parcial como una inicialización correcta.

## Tópicos y reconciliación

Todos tienen 3 particiones, RF 3 y `min.insync.replicas=2`:

| Tópico | `cleanup.policy` | Retención | Uso |
|---|---|---|---|
| `workorders.events` | `delete` | 7 días | Ciclo de vida de órdenes |
| `audit.timeline` | `delete` | 30 días | Ediciones auditables; servicios pendientes |
| `workorders.events.audit.DLT` | `delete` | 14 días | Fallos de Audit sobre ciclo de vida |
| `audit.timeline.audit.DLT` | `delete` | 14 días | Fallos de Audit sobre ediciones |
| `workorders.events.report.DLT` | `delete` | 14 días | Reserva histórica, sin consumidor Report Kafka |

`inicializar-topicos.sh` crea recursos ausentes y usa `kafka-configs --alter` para
actualizar propiedades en los existentes. Comprueba particiones/RF y falla ante
discrepancias, sin borrar ni recrear el tópico. Repetir `--if-not-exists` por sí
solo no aplicaría las configuraciones nuevas.

El cambio de `compact,delete` a `delete` en timeline permite conservar distintas
ediciones con la misma key `ordenId`. No recupera registros compactados anteriormente.
Con `acks=all`, el ISR mínimo de 2 rechaza escrituras cuando solo queda una réplica
sincronizada. Esto no vuelve atómicas la transacción Oracle y la publicación Kafka.

Para reconciliar explícitamente después del arranque:

```powershell
docker compose --env-file kafka/.env.example -f kafka/compose.yml run --rm --no-deps kafka-init-topicos
```

## Prueba real desde Windows

Requiere JDK 21 para ejecutar el archivo fuente. Los JAR se copian de la imagen
ya utilizada por el clúster, a un directorio ignorado por Git:

```powershell
New-Item -ItemType Directory -Force evidencias-locales | Out-Null
docker cp kafka1:/usr/share/java/kafka/kafka-clients-7.5.0-ccs.jar evidencias-locales/kafka-clients.jar
docker cp kafka1:/usr/share/java/kafka/slf4j-api-1.7.36.jar evidencias-locales/slf4j-api.jar
java --class-path 'evidencias-locales/kafka-clients.jar;evidencias-locales/slf4j-api.jar' kafka/VerificarKafka.java
```

Se puede pasar otro bootstrap como argumento. El aviso de SLF4J sin implementación
de logging es esperado al usar solo esos dos JAR; no afecta el resultado.
El verificador exige tres brokers, comprueba particiones, ISR y configuraciones de
los cinco tópicos; produce y consume tres mensajes en un tópico aleatorio propio,
verifica orden/offsets y elimina únicamente ese tópico y su grupo temporal.
No produce ni consume mensajes en los tópicos del caso ni en RabbitMQ.

## Evidencias del 23/11/2026

- Compose válido y `bash -n kafka/inicializar-topicos.sh` correcto.
- Tres ZooKeeper activos y tres brokers saludables; inicializador terminado con 0.
- Kafka UI `/api/clusters`: `online`, `brokerCount=3`, cinco tópicos y 15 particiones
  de usuario online después de limpiar los recursos de prueba.
- Verificador desde Windows: cinco configuraciones correctas, tres mensajes
  ordenados en partición 2, offsets `[0, 1, 2]` y offset confirmado por el grupo.
- Mismo verificador compilado con `javac --release 11`, copiado al broker y ejecutado
  con bootstrap interno: resultado correcto.
- En el clúster aislado se simuló timeline con `compact,delete` e ISR mínimo 1.
  El inicializador restauró `delete`/ISR 2 conservando su `TopicId`; el verificador
  volvió a pasar. No se borraron ni recrearon tópicos del caso.
- Las aplicaciones antiguas reactivadas al abrir Docker Desktop se detuvieron;
  los nodos RabbitMQ se dejaron sin intervención. No se validó su flujo funcional.

Los logs y JAR locales están en `evidencias-locales/`, ignorados por Git. Esto prueba
el clúster y ambos modos de conexión, no Workorders → Audit → Oracle, JWT ni SMTP.
Los PR de servicios y su validación conjunta siguen pendientes.

Para detener conservando datos, usar `docker compose ... stop` con el mismo proyecto
y archivos. No usar `down -v`, ni purgar volúmenes para repetir la demostración.

Referencias: [listeners de Confluent](https://docs.confluent.io/platform/current/kafka/listeners.html)
y [configuración de tópicos Kafka](https://kafka.apache.org/35/generated/topic_config.html).
