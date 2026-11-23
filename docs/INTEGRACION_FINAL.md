# Integración final y demostración EP5/EP6

Verificación del 23 de noviembre de 2026. Este informe sustituye, como estado
actual, los pendientes históricos de los documentos de cada PR. No acredita
despliegue AWS, Oracle RDS, login Entra/MSAL ni correo externo.

## Integración en main

Se revisaron los diffs, dependencias, archivos temporales/credenciales y conflictos
antes de los merges, con comprobación del SHA remoto y merge commit. Las issues
permanecen bajo administración del equipo. Orden ejecutado:

1. [Infra #16](https://github.com/arieeliiu/digitalfix-infra/pull/16), contratos.
2. [Infra #17](https://github.com/arieeliiu/digitalfix-infra/pull/17), clúster.
3. [Audit #4](https://github.com/arieeliiu/digitalfix-ms-audit/pull/4), integridad.
4. [Audit #5](https://github.com/arieeliiu/digitalfix-ms-audit/pull/5), ambos tópicos y DLT.
5. [Workorders #15](https://github.com/arieeliiu/digitalfix-ms-workorders/pull/15), ediciones.
6. [Infra #20](https://github.com/arieeliiu/digitalfix-infra/pull/20), aplicaciones.
7. [BFF #24](https://github.com/arieeliiu/digitalfix-ms-bff/pull/24), API de historial.
8. [Frontend #33](https://github.com/arieeliiu/digitalfix-frontend/pull/33), vista de historial.
9. [Kafka Admin #1](https://github.com/arieeliiu/digitalfix-ms-kafka-admin/pull/1), administración.
10. [Infra #22](https://github.com/arieeliiu/digitalfix-infra/pull/22), Compose del administrador.
11. [Infra #21](https://github.com/arieeliiu/digitalfix-infra/pull/21), verificador conjunto.

[Frontend #34](https://github.com/arieeliiu/digitalfix-frontend/pull/34) añade la
prueba optativa del formulario con backend real, sin cambiar producción.
Esta revisión añade arranque JPA configurable y `validate` por defecto en Compose:
Catalog, Workorders y Audit fallan ante un esquema incompatible sin aplicar DDL.
`update` queda reservado a una base local aislada; fuera de Compose hay que revisar
la configuración Java de cada servicio, que conserva sus valores propios.

## Evidencias obtenidas

Todas las pruebas finales usaron fuentes consolidadas de main. Las integraciones
optativas de Angular utilizaron JWT RSA/JWKS locales; adaptar la identidad de la
vista no sustituye la validación real del Bearer en BFF y los microservicios.

| Componente | Resultado comprobado |
|---|---|
| Kafka | 3 ZooKeeper, 3 brokers saludables; 3 particiones/RF3/ISR3; retención, cleanup y minISR verificados; producción/consumo reales y offsets ordenados |
| Audit | 25 casos pasan, incluidos Kafka real, idempotencia/concurrencia, DLT por origen y offset detenido si falla el DLT |
| Workorders | 79 casos pasan, 2 pruebas Rabbit antiguas omitidas; el recorrido HTTP real posterior verifica Rabbit, Catalog y Oracle sin esos mocks |
| BFF | 82 casos pasan; consulta HTTP real de los eventos persistidos; Admin/Auditor 200, Cliente/Operador 403, token ausente/inválido 401 |
| Angular | 200 pruebas normales pasan; 2 integraciones optativas pasan contra backend real: historial y creación desde formulario seguida de asignación e historial |
| Catalog | 10 casos pasan; stock Oracle real pasa de 10 a 7 tras asignar 2 y cambiar a 3; repetir la solicitud no descuenta otra vez |
| Notify | 13 casos pasan; SMTP local captura 4 correos del recorrido principal y 2 de la orden Angular; genera el ticket PDF real |
| Report | 15 casos pasan; cierre de orden genera PDF real; reentrega no repite el efecto |
| RabbitMQ | Dos nodos activos del clúster existente; vhost, usuario y directorios nuevos; direct/topic, ACK, idempotencia y DLQ reales sin purgar colas previas |
| Rabbit Admin | 11 casos normales pasan, 2 integraciones antiguas omitidas; verificación HTTP real independiente en el vhost aislado |
| Kafka Admin | 8 casos pasan; CRUD autenticado dentro de Docker, configuración incremental, permisos y presencia del tópico temporal en Kafka UI |

El recorrido principal creó dos órdenes sintéticas: 7 eventos de ciclo de vida
(incluida eliminación) y 3 ediciones. La primera orden conservó 8 entradas en
historial transaccional y Audit/Oracle. Reentregar un evento no añadió filas.
Se comprobó la identidad desde `oid` frente a un propietario falsificado en el body.

Se aplicaron **solo en Oracle Free 23.26.4 local**, sobre un volumen nuevo, los
scripts preparados de historial/ediciones y origen Kafka. Repetir la migración de
ediciones fue correcto. Una constraint temporal de prueba provocó un error Oracle
real: intento inicial + 3 reintentos, DLT y posterior recuperación con el mismo
eventId. El DLT original se conserva. Los mensajes inválidos de ambos tópicos
llegaron a sus DLT manteniendo la partición y el lag final fue cero.

Los tres servicios JPA también arrancaron con `ddl-auto=validate` sobre el esquema
Oracle local. Esto valida ese esquema concreto, no una migración sobre RDS ni la
compatibilidad con otra versión de Oracle. La build Angular es correcta y conserva
el aviso de bundle inicial de 730,86 kB frente al presupuesto de aviso de 500 kB.

Las evidencias detalladas están fuera de Git en `.integracion-final/` del workspace:
`oracle-sistema.log`, `validate-oracle.log`, `rabbit-admin-real.log`, JSON de cada
merge y `resultado-*/` (logs por servicio, correos sintéticos y PDFs). Los archivos
de entorno de ese directorio contienen credenciales locales efímeras y no se publican.
Los nueve archivos que ya estaban modificados antes del trabajo se preservaron.

## Ejecución local y demostración

Usar checkouts limpios de main. Revisar antes `docker compose ls`, `docker ps -a`
y `docker volume ls`. Reutilizar el nombre de proyecto del clúster existente:
los contenedores Kafka tienen nombres fijos. No ejecutar `down -v` ni prune.

Desde la raíz de Infra, en el entorno de verificación existente:

```powershell
$KafkaProyecto = 'digitalfix-kafka-verificacion'
docker compose -p $KafkaProyecto --env-file kafka/.env.example -f kafka/compose.yml config --quiet
docker compose -p $KafkaProyecto --env-file kafka/.env.example -f kafka/compose.yml up -d
docker compose -p $KafkaProyecto --env-file kafka/.env.example -f kafka/compose.yml ps -a
docker logs kafka-init-topicos
```

Kafka UI: `http://localhost:8090`, clúster `digitalfix`. La inicialización verifica
topología y reconcilia configuraciones sin recrear los tópicos. Reducir retención
en un clúster con datos requiere revisión previa; las ventanas son 7/30/14 días.

```powershell
docker exec kafka1 kafka-topics --bootstrap-server kafka1:29092 --describe --topic workorders.events
docker exec kafka1 kafka-topics --bootstrap-server kafka1:29092 --describe --topic audit.timeline
docker exec kafka1 kafka-configs --bootstrap-server kafka1:29092 --entity-type topics --entity-name audit.timeline --describe
docker exec kafka1 kafka-consumer-groups --bootstrap-server kafka1:29092 --list
# Usar el grupo real de Audit: audit en despliegue; final-oracle-UUID en la evidencia local.
docker exec kafka1 kafka-consumer-groups --bootstrap-server kafka1:29092 --describe --group audit
```

Observar mensajes desde otra terminal; detener el lector con Ctrl+C. No cambia
los offsets de Audit. No utilizar un grupo de negocio para lectores de demostración.

```powershell
docker exec -it kafka1 kafka-console-consumer --bootstrap-server kafka1:29092 --topic workorders.events --from-beginning --property print.key=true --property print.partition=true --property print.offset=true
docker exec -it kafka1 kafka-console-consumer --bootstrap-server kafka1:29092 --topic audit.timeline --from-beginning --property print.key=true --property print.partition=true --property print.offset=true
docker exec -it kafka1 kafka-console-consumer --bootstrap-server kafka1:29092 --topic audit.timeline.audit.DLT --from-beginning
docker exec -it kafka1 kafka-console-consumer --bootstrap-server kafka1:29092 --topic workorders.events.audit.DLT --from-beginning
```

Para repetir el verificador con H2 y brokers reales, sin RDS:

```powershell
.\validacion\Verificar-Eventos.ps1 -Workorders ..\digitalfix-ms-workorders -Audit ..\digitalfix-ms-audit -Bff ..\digitalfix-ms-bff -Frontend ..\digitalfix-frontend
```

Para arrancar aplicaciones, preparar **sin sobrescribir** `apps/.env` a partir del
ejemplo. Elegir explícitamente la base de destino y comprobar su esquema antes.
Para una base Oracle local nueva y desechable, la creación inicial puede usar
`JPA_DDL_AUTO=update`; después cambiar a `validate`. No usar esa excepción en RDS.
Confirmar redes, volúmenes existentes, usuarios Rabbit por servicio y SMTP autorizado.
En Docker Desktop, Oracle en el host se alcanza por `host.docker.internal`, no por
`localhost` dentro de los contenedores. Java en Windows usa el puerto loopback local.

```powershell
docker compose --env-file apps/.env -f apps/compose.yml -f apps/compose.kafka-admin.yml config --quiet
# Consumidores compatibles antes del productor.
docker compose --env-file apps/.env -f apps/compose.yml -f apps/compose.kafka-admin.yml up -d --build catalog audit notify report
docker compose --env-file apps/.env -f apps/compose.yml -f apps/compose.kafka-admin.yml up -d --build workorders bff rabbit-admin kafka-admin
# En digitalfix-frontend:
npm ci
npm start
```

El frontend local usa el proxy hacia BFF. El login real requiere registrar su URI
de redirección en Entra. Con un token de API válido almacenado solo en el shell:

```powershell
# $env:DIGITALFIX_TOKEN: token delegado de Admin o Auditor, no imprimirlo ni guardarlo en Git.
Invoke-RestMethod 'http://localhost:8080/api/workorders/1/history' -Headers @{Authorization="Bearer $env:DIGITALFIX_TOKEN"}
Invoke-RestMethod 'http://localhost:8086/api/kafka/topics' -Headers @{Authorization="Bearer $env:DIGITALFIX_TOKEN"}
```

Kafka Admin requiere Admin, también en lectura. Auditor solo puede consultar el
historial, sin acceder a crear/editar órdenes. En Angular, crear como Cliente,
editar datos/contactos, asignar como Admin, cambiar repuestos, avanzar estados y
actualizar el historial. El consumo es eventual; esperar al lag cero y actualizar.
Usar los ejemplos de [Kafka Admin](KAFKA_ADMIN.md) sobre un tópico temporal propio.

## Preparación manual de AWS, sin cambios remotos

**Las redes Docker bridge son locales a cada host.** Un bridge con el mismo nombre
en dos EC2 no las conecta. La configuración admite brokers remotos por sus puertos
publicados y direcciones anunciadas; no utilizar aliases `kafka1` o `rabbitmq1`
desde una instancia que no comparte sus redes Docker.

| Preparar con el compañero | Configuración que depende de ello |
|---|---|
| IP/DNS privado del host Kafka | En Kafka: `KAFKA_HOST=IP_PRIVADA_KAFKA`, bind alcanzable y puertos 9092/9093/9094. En apps: bootstrap de esos tres endpoints privados |
| Red privada entre EC2 y reglas de acceso | Permitir TCP 9092–9094 desde el grupo de aplicaciones; AMQP 5672 hacia Rabbit; Oracle 1521 solo desde aplicaciones autorizadas. No abrirlos a Internet |
| Clúster Rabbit existente | Confirmar sus dos nodos, versión, volúmenes, vhost, exchanges/bindings/DLQ y usuarios exclusivos; no recrearlo con el Compose histórico sin contrastarlo |
| Rabbit entre hosts distintos | Direcciones privadas en `RABBITMQ_ADDRESSES`; 4369/25672 restringidos entre nodos Rabbit según su configuración; 15672 solo acceso administrativo privado |
| Redes locales del host de aplicaciones | Crear/identificar bridges locales para `KAFKA_NETWORK_NAME` y `RABBITMQ_NETWORK_NAME`; estos permiten salida, no simulan una red entre EC2 |
| RDS | Endpoint, servicio, versión de Oracle, credenciales/propietario por servicio, requisitos TLS/wallet, conectividad privada y respaldo; revisar scripts y autorizar migraciones antes de `validate` |
| Entra | Tenant/issuer, audience de API, scope delegado, roles Admin/Auditor/Operador/Cliente y asignaciones; redirect/logout URI de la SPA; nunca usar claves de pruebas |
| Gateway y SPA | Dominio HTTPS, integración hacia BFF, rutas `/api/*`, paso de Authorization, CORS del origen exacto con GET/POST/PUT/PATCH/DELETE/OPTIONS y política de red de entrada |
| SMTP y almacenamiento | Host/puerto/TLS/credenciales/remitente autorizado, destinos de prueba; volúmenes Notify/Report y permisos UID 10001/10003, copia de respaldo |
| Capacidad y operación | Medir CPU/RAM/disco para tres brokers/tres ZooKeeper y aplicaciones, definir reinicio, logs y acceso privado a UI/Admin; el tamaño EC2 no se ha validado |

Ejemplo conceptual para apps en otra EC2: `KAFKA_BOOTSTRAP_SERVERS=IP_KAFKA:9092,IP_KAFKA:9093,IP_KAFKA:9094`.
Con tres brokers en hosts distintos, cada uno debe anunciar su propia dirección;
el Compose actual representa tres brokers en un solo host. RF3 allí no tolera la
pérdida de ese host completo. Kafka UI y Kafka Admin permanecen en loopback por
defecto; usar acceso privado/túnel, no publicar administración sin control.

## Pendientes y criterios todavía no demostrados

- EP5: código/configuración y flujos locales verificados. Falta certificar conexión
  y esquema reales de la base cloud, tokens de IDaaS y login de la SPA en el entorno final.
- EP6: falta desplegar y demostrar en nube el Compose, Gateway, login/roles,
  productores/consumidores, historial, métricas de offsets/lag y RabbitMQ conservado.
- Repetir SMTP con proveedor autorizado, PDF/ticket y persistencia/reinicio de los
  volúmenes definitivos; los PDFs locales nuevos no certifican esos volúmenes previos.
- La publicación Kafka es posterior al commit y no tiene outbox. Una caída entre
  ambos puede dejar un evento sin publicar; no se promete entrega exactamente una vez.
- Revisar tipos/constraints con la versión Oracle de RDS antes de aplicar SQL.
  Ningún resultado local autoriza migraciones remotas automáticas.
- Optimizar el bundle Angular es un pendiente de rendimiento; la build no falla.

Issues sugeridas para que el equipo organice manualmente: «Validar despliegue
EP6 con RDS/Entra/Gateway/SMTP reales» y, si se exige garantía ante caídas del
productor, «Definir recuperación de fallos entre commit y publicación Kafka».
