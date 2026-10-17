# Evidencias EP3/EP4 — Infraestructura RabbitMQ

Fecha: 2026-10-17. Entorno: Windows + Docker Desktop, contenedores Linux.
Docker Engine 29.8.2; Compose 5.6.0; RabbitMQ 4.2.9; Erlang 27.3.4.19.
Rama: `feature/rabbitmq-cluster`. Evidencias reproducibles con `mq/verify.py`.

## Registro inicial de funcionalidades

Esta tabla cubre únicamente el Paso 1; no es el denominador completo de la matriz
del proyecto y no implica obtener el 90% del alcance EP3/EP4.

| Funcionalidad | Estado | Responsable | Evidencia verificable |
|---|---|---|---|
| Compose independiente y credenciales externas | Implementada | Infra | `mq/compose.yml`; `config --quiet` exit 0; `git check-ignore mq/.env` |
| Dos nodos en un clúster real | Implementada localmente | Infra | `cluster_status` desde ambos; dos `running_nodes`, sin particiones |
| Management UI y API | Implementada localmente | Infra | HTTP 200 en `/`; API autenticada `/api/nodes` muestra ambos nodos |
| DNS y conectividad interna | Implementada localmente | Infra | Clustering 4369/25672 y conexión TCP cruzada a `rabbitmq1:5672` / `rabbitmq2:5672` |
| Salud y versión | Implementada localmente | Infra | Ambos `healthy`; versión 4.2.9 en ambos |
| Persistencia por nodo | Implementada localmente | Infra | Una cola durable classic por nodo; payload persistente recuperado tras recreación |
| Recuperación de clúster | Implementada localmente | Infra | Reinicio secuencial y recreación completa, membresía comprobada después |
| Conservación de despliegue Evaluación 1 | Verificada estáticamente | Infra | Compose anterior válido y diff vacío en `docker-compose.yml` y `apps/` |
| Integración con futuras aplicaciones | Preparada, no integrada | Infra + microservicios | Red `digitalfix-messaging`; instrucciones `external: true` en README |
| Despliegue y demostración EC2 | No implementada en este paso | Infra | Procedimiento documentado; sin cambios en AWS |
| Topología funcional y consumidores | Pendiente de siguientes pasos | Notify / Report / Workorders | No se crearon colas ni servicios de negocio |

## Prueba real ejecutada

Desde la raíz del repositorio:

```sh
docker compose --env-file mq/.env -f mq/compose.yml config --quiet
docker compose --env-file mq/.env -f mq/compose.yml up -d --wait --wait-timeout 300
python mq/verify.py --restart
```

Resultado exitoso del script (resumen de salida):

```text
PASS: both nodes, cluster membership, internal listeners, Management API/UI, 4.2.9
PASS: durable classic queue and persistent test message on EACH node
PASS: both nodes, cluster membership, internal listeners, Management API/UI, 4.2.9
PASS: both nodes, cluster membership, internal listeners, Management API/UI, 4.2.9
PASS: both nodes, cluster membership, internal listeners, Management API/UI, 4.2.9
PASS: sequential restart and full container recreation (volumes retained)
PASS: both original payloads recovered after recreation
Temporary test queues removed; volumes and business topology untouched
```

Se verificó adicionalmente conexión TCP AMQP entre ambos nodos en ambas direcciones
y el comando de espera `rabbitmq-diagnostics --timeout 10 ... check_running`.
El script incluye ahora también esa comprobación TCP en cada validación.

Extracto del `rabbitmqctl cluster_status --formatter json` final:

```json
{
  "cluster_name": "digitalfix-mq",
  "disk_nodes": ["rabbit@rabbitmq1", "rabbit@rabbitmq2"],
  "running_nodes": ["rabbit@rabbitmq1", "rabbit@rabbitmq2"],
  "partitions": {},
  "alarms": []
}
```

Ambos contenedores quedaron `healthy`. La única publicación al host es
`127.0.0.1:15672->15672/tcp`. La comprobación final `list_queues -p digitalfix`
no mostró colas: todos los recursos temporales fueron retirados, sin borrar volúmenes.

## Incidencias y límites

- El primer intento del script se interrumpió al perderse la conexión con Docker
  Desktop. El motor recuperó su servicio y los nodos arrancaron con sus volúmenes;
  se volvió a ejecutar la prueba completa y terminó con exit 0. No se utilizó reset
  de RabbitMQ, `force_boot` ni eliminación de volúmenes para recuperarlo.
- No se ejecutó una regresión funcional del backend/frontend; se verificó la
  configuración del Compose existente sin modificarlo ni reconstruir sus servicios.
- La UI se comprobó por HTTP y su API autenticada, no mediante una revisión visual
  interactiva del navegador. Capturas para la presentación quedan pendientes.
- La prueba usa HTTP API del broker real para crear/publicar/recuperar mensajes
  sintéticos. No valida los futuros listeners Spring, ACK/NACK de negocio o DLQ.
- Sin pruebas de carga, fallos de disco, failover garantizado ni despliegue EC2.
  Classic queues no replican mensajes y este clúster de dos nodos no ofrece HA.

## Verificación del despliegue anterior

```sh
docker compose --env-file apps/.env.example -f docker-compose.yml config --quiet
git diff --exit-code main -- docker-compose.yml apps
git diff --check
git check-ignore mq/.env
```

Los tres primeros comandos finalizaron con exit 0; el último confirmó que el
archivo de secretos local está ignorado. `.env.example` contiene solo placeholders.
