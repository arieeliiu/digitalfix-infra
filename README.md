# DigitalFix — Infraestructura integrada, Evaluación 2

El Compose raíz integra BFF, Catalog, Workorders, Notify, Report y Rabbit Admin.
RabbitMQ conserva su Compose independiente en [mq/compose.yml](mq/compose.yml).
El código/configuración del Paso 6 no equivale a un despliegue AWS realizado.

- [Estado, commits, pruebas y pendientes del Paso 6](docs/PASO6_ESTADO.md)
- [Arranque y persistencia](docs/INTEGRACION.md)
- [API Gateway, CORS y Oracle: preparación sin cambios remotos](docs/DESPLIEGUE_PENDIENTE.md)
- [Operación del clúster](mq/README.md) y [evidencias del Paso 1](mq/EVIDENCIAS.md)
- [Referencia histórica de Evaluación 1](docs/EVALUACION1_HISTORICA.md): no usar sus instrucciones de arranque para esta versión.

## Arquitectura

```text
Angular + MSAL → API Gateway → BFF → Catalog → Oracle RDS
                               └→ Workorders → Oracle RDS
                                      ├→ Catalog (stock síncrono)
                                      └→ RabbitMQ (publicación después del commit)
                                            ├→ Notify → SMTP + ticket PDF
                                            └→ Report → informe PDF
                         Rabbit Admin → RabbitMQ (API privada, JWT + rol Admin)
```

| Servicio | Redes | Puerto publicado | Persistencia |
|---|---|---|---|
| BFF | digitalfix-backend | 8080, conservar protección de red del despliegue | No |
| Catalog | digitalfix-backend | Ninguno | Oracle externo |
| Workorders | digitalfix-backend + digitalfix-messaging | Ninguno | Oracle externo |
| Notify | digitalfix-messaging | Ninguno | Volumen existente /data |
| Report | digitalfix-messaging | Ninguno | Volumen existente /data |
| Rabbit Admin | digitalfix-messaging | 127.0.0.1:8085 | No |

Se conserva el proyecto `apps` y su red física `apps_digitalfix-backend`,
inspeccionados en el entorno local. En un host con otro proyecto, verificar sus
labels antes de utilizar `-p`; no crear un segundo backend accidentalmente.
`digitalfix-messaging` es externa y pertenece al Compose de RabbitMQ.

## Configuración y validación

Mantener como hermanos los siete repositorios backend/infra y el frontend.
Completar `apps/.env` a partir de [apps/.env.example](apps/.env.example), sin
sobrescribir un archivo real existente. Se requieren credenciales Oracle,
cuatro cuentas RabbitMQ independientes, SMTP y configuración Entra para Rabbit Admin.
Workorders recibe explícitamente `DIGITALFIX_RABBIT_ENABLED=true`.

```powershell
# Solo valida sintaxis/estructura con valores ilustrativos; NO inicia servicios.
docker compose --env-file apps/.env.example -f docker-compose.yml config --quiet
python scripts/verificar_paso6.py --env-file apps/.env.example --broker
```

El verificador solo lee Compose, Management API y Docker. No publica ni consume
mensajes, no crea cuentas y no imprime contraseñas. Informa los requisitos pendientes.
Para usarlo como barrera de arranque con configuración real:

```powershell
python scripts/verificar_paso6.py --env-file apps/.env --broker --exigir-listo
```

Seguir la secuencia de [INTEGRACION.md](docs/INTEGRACION.md): consumidores y bindings
antes de iniciar Workorders. No ejecutar un `up` global como primer paso.
Los healthchecks HTTP de BFF y Rabbit Admin solo prueban respuesta del proceso;
no certifican Oracle, Entra ni RabbitMQ. Los consumidores sin HTTP se comprueban
mediante su registro en las colas y sus resultados persistidos.

## Límites y conservación

No hay Outbox: una caída entre commit Oracle y publicación puede perder el comando.
Confirms/mandatory detectan fallos de publicación, pero no vuelven atómica esa operación.
La transición confirmada no debe presentarse como fallida por un error RabbitMQ.
Dos nodos con colas classic durables no constituyen alta disponibilidad ni replicación
de mensajes. Mantener PDF y registros de idempotencia juntos; nunca borrar registros,
volúmenes o mensajes para repetir una demostración.

No usar `docker compose down -v` ni `docker volume prune`. No escalar Notify/Report
ni iniciar simultáneamente sus Compose independientes y el central.
Los PR y merges los realiza el usuario; el Paso 6 solo se publica en ramas feature.
