# RabbitMQ de DigitalFix — Paso 1

## Alcance y arquitectura

Dos nodos de la imagen oficial `rabbitmq:4.2.9-management`, en un único host Docker:

```text
Host (desarrollo o EC2)
  127.0.0.1:15672 -> rabbitmq1:15672 (Management UI / HTTP API)
  red bridge digitalfix-messaging
    rabbitmq1 / rabbit@rabbitmq1 -> volumen rabbitmq1-data
    rabbitmq2 / rabbit@rabbitmq2 -> volumen rabbitmq2-data
    aplicaciones futuras -> rabbitmq1:5672, rabbitmq2:5672
```

AMQP (5672), EPMD (4369) y distribución Erlang (25672) quedan internos;
no se publican en el host. El puerto de Management se publica solo en loopback.
Los nodos comparten cookie y versión, pero tienen identidad y datos independientes.
Los nombres efectivos de los volúmenes llevan el prefijo estable `digitalfix-mq_`.

`rabbitmq.conf` usa peer discovery `classic_config`. `start-node.sh` espera al
primer nodo **solo en el primer arranque de un segundo nodo sin directorio de
datos**. Si el seed no responde, falla y Docker reintenta; no crea deliberadamente
un segundo broker independiente. Después ejecuta el entrypoint oficial, que
ajusta permisos y ejecuta RabbitMQ como usuario `rabbitmq`.

Con datos existentes, RabbitMQ recupera la membresía persistida. No se fuerza
`join_cluster`, `reset` ni `force_boot`. Tampoco hay un `depends_on: service_healthy`
que impida arrancar al segundo nodo cuando el primero necesita recuperarse con él.
Los healthchecks verifican aplicación, alarmas y listeners locales. La membresía
de **ambos** nodos se verifica separadamente; un nodo healthy no demuestra clúster.

## Archivos

| Archivo | Propósito |
|---|---|
| `compose.yml` | Servicios, volúmenes, red, healthchecks y logging limitado |
| `rabbitmq.conf` | Descubrimiento estático, listeners y colas classic por defecto |
| `start-node.sh` | Validación básica de secretos y arranque inicial coordinado |
| `.env.example` | Variables requeridas, sin secretos válidos |
| `verify.py` | Prueba con RabbitMQ real; recursos temporales únicos |
| `EVIDENCIAS.md` | Resultados reales y pendientes para EP3/EP4 |

## Preparación en un entorno limpio

Requisitos: Docker Engine con contenedores Linux (o Docker Desktop iniciado),
Docker Compose v2 con `--wait`, acceso al registro de imágenes y puerto local
15672 libre. Python 3.10+ solo es necesario para la verificación automatizada.
Ejecutar los comandos siguientes **desde la raíz de digitalfix-infra**.

```sh
cp mq/.env.example mq/.env
```

En PowerShell, usar `Copy-Item mq/.env.example mq/.env`. Editar `mq/.env`:

| Variable | Valor requerido |
|---|---|
| `RABBITMQ_DEFAULT_USER` | Administrador inicial distinto de `guest` |
| `RABBITMQ_DEFAULT_PASS` | Contraseña aleatoria única |
| `RABBITMQ_ERLANG_COOKIE` | Secreto aleatorio compartido por ambos nodos |

Generar dos valores independientes de al menos 32 bytes, por ejemplo mediante
un gestor de contraseñas o `openssl rand -hex 32`. No usar los placeholders.
En Linux: `chmod 600 mq/.env`. En Windows restringir sus permisos al propietario.
`.gitignore` ya excluye `.env` en cualquier directorio y permite `.env.example`.
No publicar `docker compose config` sin `--quiet`, `docker inspect` completo ni
capturas de variables: pueden incluir credenciales. Los administradores del host
Docker pueden leer las variables de contenedor; este diseño no las oculta de ellos.

El usuario/contraseña inicial se aplica a datos nuevos. Cambiar el archivo `.env`
no cambia automáticamente una contraseña ya almacenada en RabbitMQ. La rotación
de usuario/cookie requiere una operación coordinada, no borrar volúmenes.
El vhost de trabajo es `digitalfix`. Más adelante, crear usuarios de aplicación
con permisos limitados a ese vhost; no usar el administrador en los microservicios.

## Inicio y comprobación

```sh
docker compose --env-file mq/.env -f mq/compose.yml config --quiet
docker compose --env-file mq/.env -f mq/compose.yml up -d --wait --wait-timeout 300
docker compose --env-file mq/.env -f mq/compose.yml ps
docker compose --env-file mq/.env -f mq/compose.yml exec -T rabbitmq1 rabbitmqctl cluster_status
docker compose --env-file mq/.env -f mq/compose.yml exec -T rabbitmq2 rabbitmqctl cluster_status
```

Ambos deben mostrar `rabbit@rabbitmq1` y `rabbit@rabbitmq2` entre los nodos en
ejecución, sin particiones. Healthchecks: ambos `healthy`. UI:
[http://127.0.0.1:15672](http://127.0.0.1:15672), con las credenciales de `mq/.env`.
Overview debe mostrar los dos nodos. Exchanges, Queues, Connections y Channels
mostrarán actividad cuando existan clientes. Management UI no es Rabbit Admin.

```sh
docker compose --env-file mq/.env -f mq/compose.yml exec -T rabbitmq1 rabbitmq-diagnostics -q check_port_connectivity
docker compose --env-file mq/.env -f mq/compose.yml exec -T rabbitmq2 rabbitmq-diagnostics -q check_port_connectivity
docker compose --env-file mq/.env -f mq/compose.yml logs --tail 100
python mq/verify.py
```

El script comprueba versión, UI/API, membresía desde ambos nodos y listeners;
crea una cola classic durable **en cada nodo**, publica mensajes de prueba con
`delivery_mode=2`, los recupera y elimina únicamente sus propias colas temporales.
Publica mediante la HTTP API del broker real; no representa una prueba de los
futuros productores/consumidores Spring ni de sus ACK/NACK de negocio.

## Reinicios y persistencia

En un entorno de pruebas sin trabajo de negocio en curso:

```sh
python mq/verify.py --restart
```

Además de las comprobaciones anteriores, reinicia cada nodo, recrea ambos
contenedores conservando los volúmenes y verifica que los dos payloads originales
siguen disponibles. Durante esta prueba hay interrupciones; no ejecutarla sobre
una instancia con consumidores activos sin coordinar la ventana.

Operación manual:

```sh
docker compose --env-file mq/.env -f mq/compose.yml restart rabbitmq2
docker compose --env-file mq/.env -f mq/compose.yml up -d --wait --wait-timeout 300
docker compose --env-file mq/.env -f mq/compose.yml stop
docker compose --env-file mq/.env -f mq/compose.yml up -d --wait --wait-timeout 300
```

Para una parada completa usar `stop` y luego levantar ambos juntos. Mantener los
volúmenes, cookie, nombre del proyecto y nombres de nodo. No usar `down -v`,
`docker volume prune`, `reset` o `force_boot` como solución automática. `down` sin
`-v` conserva volúmenes, pero puede intentar eliminar la red compartida: preferir
`stop` mientras haya aplicaciones conectadas.

Las colas classic **no replican sus mensajes**. Si cae el nodo dueño de una cola,
esa cola puede quedar indisponible hasta recuperarlo. Dos nodos en el mismo host
tampoco protegen contra perder ese host. Un volumen no sustituye un backup.
Los mensajes de los futuros productores deben ser persistentes y sus colas y
exchanges durables; infraestructura no puede imponer `delivery_mode=2` a clientes.

## Integración futura (sin cambiar el Compose actual)

El Compose MQ crea y mantiene `digitalfix-messaging`. En el futuro, el Compose
de aplicaciones la referenciará como externa y conectará los servicios necesarios:

```yaml
services:
  notify:
    # Imagen, variables y demás configuración se definirán en su propio paso.
    networks:
      - messaging
networks:
  messaging:
    external: true
    name: digitalfix-messaging
```

Este fragmento es documentación, no un servicio desplegado. Workorders conservará
también su red backend para comunicarse con Catalog. Direcciones AMQP internas:
`rabbitmq1:5672,rabbitmq2:5672`, vhost `digitalfix`; nunca `localhost` desde una app.
Arrancar primero MQ y verificar membresía, después las aplicaciones. Compose
independientes no coordinan entre sí `depends_on`; los clientes deben reconectar.

Notify y Report declararán beans durables de Queue, Exchange y Binding. Este paso
no instala topología de negocio ni políticas que puedan contradecir sus argumentos:

| Cola | Direct (`cmd.direct`) | Topic (`cmd.topic`) | DLQ vía `cmd.dead.dlx` (direct) |
|---|---|---|---|
| `q.cmd.email` | `email.send` | `email.*` | `email.dead` -> `q.cmd.email.dlq` |
| `q.cmd.dispatch` | `dispatch.ticket` | `dispatch.#` | `dispatch.dead` -> `q.cmd.dispatch.dlq` |
| `q.cmd.report` | `report.gen` | `report.*` | `report.dead` -> `q.cmd.report.dlq` |

No iniciar publicación de negocio antes de declarar esta topología. Confirms,
mandatory/returns, ACK manual y DLQ corresponden a las tareas posteriores.

## EC2

1. Instalar Docker Engine y Compose v2, clonar esta rama y preparar `mq/.env` en
   la instancia sin subirlo a GitHub. Usar almacenamiento EBS persistente para Docker.
2. Comprobar RAM, CPU y disco libres contando también las JVM del backend. Reservar
   margen para ambos nodos; el límite de alarma de disco es 1 GB libre. Revisar
   `docker stats` y alarmas bajo carga antes de decidir que la instancia alcanza.
3. Permitir salida al registro de imágenes. Para el diseño de un único host,
   **no abrir** 5672, 4369, 25672 ni 15672 a Internet en Security Groups.
4. Restringir SSH al origen administrativo y acceder a Management mediante túnel:

```sh
ssh -N -L 15672:127.0.0.1:15672 -i /ruta/clave.pem usuario@HOST_EC2
```

Abrir `http://127.0.0.1:15672` en el equipo local. Si allí ya hay RabbitMQ, usar
`-L 15673:127.0.0.1:15672` y abrir el puerto 15673.

Una red bridge **no conecta hosts diferentes**. Si las aplicaciones pasan a otra
EC2, habrá que habilitar un listener/publicación en la interfaz privada, DNS/IP
privada estable, rutas VPC, reglas entre Security Groups concretos y evaluar TLS
para AMQP. Este Compose no publica AMQP y no habilita esa conexión por sí solo.
Separar los propios nodos entre hosts requiere además DNS de nodos y conectividad
privada 4369/25672; es otro diseño. No se modifican recursos AWS en este paso.

## Diagnóstico

| Síntoma | Comprobación / acción |
|---|---|
| Docker no responde | Iniciar Engine/Desktop y seleccionar contenedores Linux |
| Placeholders/variables ausentes | Completar `mq/.env`; no cambiar secretos en volúmenes existentes sin rotación |
| Nodo 2 esperando seed | Revisar logs de nodo 1, DNS `rabbitmq1` y cookie compartida |
| Nodos healthy pero separados | Comparar `cluster_status` en ambos; no borrar datos ni ejecutar reset; revisar historia de volúmenes/cookie |
| Reinicio tarda | Arrancar ambos nodos, revisar logs de recuperación y alarmas; no bloquear uno esperando la salud del otro |
| Alarmas de disco/memoria | Liberar recursos de forma controlada o ampliar el host; no borrar volúmenes RabbitMQ |
| UI no accesible | Confirmar loopback/puerto y túnel; no abrir Management a Internet |
| Login falla tras editar `.env` | Las credenciales iniciales no sobrescriben usuarios persistidos |
| Red externa no existe | Levantar primero el Compose MQ en ese mismo Docker Engine |
| Script falla y quedan colas temporales | Revisar solo `q.infra.verify.*` de esa ejecución antes de eliminarlas; nunca purgar colas de negocio |

## Fuentes técnicas

- [Descubrimiento en RabbitMQ 4.2](https://www.rabbitmq.com/docs/4.2/cluster-formation)
- [Clustering y recuperación](https://www.rabbitmq.com/docs/4.2/clustering)
- [Classic queues](https://www.rabbitmq.com/docs/4.2/classic-queues)
