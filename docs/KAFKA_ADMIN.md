# Kafka Admin (Infra #13)

El servicio [digitalfix-ms-kafka-admin](https://github.com/arieeliiu/digitalfix-ms-kafka-admin/pull/1)
administra tópicos/configuración mediante AdminClient. Exige JWT válido de Entra,
scope `access_as_user` y rol **Admin**, también para lectura. Auditor no administra.
Es independiente de Rabbit Admin y no consume eventos ni consulta Oracle.

## Arranque aislado

Requiere integrar el PR del servicio y clúster Infra #17. La red Kafka ya debe
existir. Desde la raíz de Infra, copiar `apps/kafka-admin.env.example` a
`apps/kafka-admin.env`, revisar el nombre real de la red y completar issuer/audience.
En el clúster de verificación usado aquí la red es
`digitalfix-kafka-verificacion_kafkanet`; no asumir ese nombre en otro entorno.

```powershell
docker compose -p digitalfix-admin --env-file apps/kafka-admin.env -f apps/compose.kafka-admin.yml config --quiet
docker compose -p digitalfix-admin --env-file apps/kafka-admin.env -f apps/compose.kafka-admin.yml up -d --build kafka-admin
```

La API escucha en `127.0.0.1:8086` por defecto. No inicia Workorders, Audit,
Catalog ni otros servicios con conexión a RDS. `GET /healthz` solo comprueba el
proceso HTTP; consultar tópicos con JWT prueba además la conexión administrativa.

También puede combinarse con Compose de aplicaciones **después de Infra #20**:

```powershell
docker compose --env-file apps/.env -f apps/compose.yml -f apps/compose.kafka-admin.yml config --quiet
# Solo el administrador; no arranca aplicaciones de negocio.
docker compose --env-file apps/.env -f apps/compose.yml -f apps/compose.kafka-admin.yml up -d --build --no-deps kafka-admin
```

Si se usa un checkout aislado, `KAFKA_ADMIN_BUILD_CONTEXT` acepta una ruta absoluta.
Cada Compose resuelve el contexto respecto a su primer archivo `-f`; ambos archivos
de este ejemplo están en `apps/`.

## Verificación y demostración

En el repositorio del servicio, JDK 21 y brokers locales disponibles:

```powershell
.\mvnw.cmd '-Ddigitalfix.pruebas.kafka=true' test
```

La prueba usa un emisor RSA/JWKS local y HTTP real. Crea exclusivamente un tópico
aleatorio `validacion-admin-*`, verifica tres particiones/RF3/ISR3, lista/describe,
modifica retención conservando minISR/cleanup, comprueba su presencia en la API de
Kafka UI y lo elimina. Verifica 400/401/403/404/409, incluyendo rechazo de Auditor,
Operador y Cliente. No modifica tópicos de negocio. Con JWT Entra real se debe
repetir manualmente el recorrido usando un tópico de demostración; falta esa
validación externa, igual que el despliegue AWS.

La imagen `digitalfix/kafka-admin:local` se construyó y arrancó en esta red Docker:
`/healthz` respondió 200 y la API sin token 401, con usuario de runtime `digitalfix`.
Solo se inició ese contenedor con issuer/JWKS locales de prueba; se retiró al terminar.
El CRUD autenticado se verificó mediante el servidor HTTP real de la prueba Java,
no mediante tokens de Entra ni una sesión autenticada dentro del contenedor.

Ejemplos de endpoints y cuerpos están en el README del servicio. Suprimir un
tópico borra sus registros; la demostración debe utilizar su propio tópico temporal.
