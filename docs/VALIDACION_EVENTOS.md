# Validación local de eventos (Infra #19)

## Dependencias y reproducción

Integrar primero Infra PR #16 (contratos), #17 (clúster), Workorders PR #15,
Audit PR #4 (integridad) y #5 (consumidor), BFF PR #24 y Frontend PR #33.
Las features siguen independientes; este PR no fusiona ramas ni modifica issues.
Infra #20 prepara Compose de aplicaciones, pero esta prueba arranca procesos Java
aislados con H2 y **no ejecuta el Compose de aplicaciones con Oracle**.

Requisitos: Windows/PowerShell, JDK 21 (JAVA_HOME y bin en PATH), Maven wrappers,
dependencias disponibles, Node/npm y `npm ci` ejecutado si se incluye Angular.
Arrancar únicamente el clúster local siguiendo la guía de Kafka del PR #17 y su
inicializador. No reutilizar un clúster con datos de negocio: se publican eventos
sintéticos, se leen los dos tópicos desde el principio y los mensajes inválidos
históricos pueden volver a generar registros en sus DLT.

Desde la raíz de Infra, indicando checkouts con los PR anteriores integrados:

```powershell
.\validacion\Verificar-Eventos.ps1 `
  -Workorders ..\digitalfix-ms-workorders `
  -Audit ..\digitalfix-ms-audit `
  -Bff ..\digitalfix-ms-bff `
  -Frontend ..\digitalfix-frontend
```

Los wrappers usan la configuración H2 de pruebas; el verificador HTTP fuerza H2
en memoria, `create-drop`, direcciones loopback, puertos aleatorios y JWKS local.
No carga archivos `.env`. Al terminar cierra sus procesos y elimina únicamente
el grupo Kafka aleatorio `validacion-http-*`. En Windows puede esperar hasta el
timeout de sesión antes de borrar ese grupo. Conserva los eventos sintéticos y
no elimina tópicos de negocio, volúmenes ni grupos existentes.

## Resultados obtenidos el 23-11-2026

Entorno: Kafka local Confluent 7.6, tres brokers/tres ZooKeeper, tópicos con tres
particiones/RF3, Kafka UI v0.7.2. Workorders y Audit con H2; clientes Kafka y HTTP
reales. Catalog aislado en la prueba de Workorders; JWT firmados con RSA local.

| Comprobación | Resultado y alcance |
|---|---|
| Workorders | Creación + asignación en `workorders.events`, tres ediciones en `audit.timeline`; cinco historiales transaccionales; no-op y rollback sin eventos |
| Audit | Consume los dos orígenes; guarda eventId, actor, fecha, tópico/partición/offset; consulta HTTP real |
| Idempotencia | Reentrega conserva una fila. PR #4 añade además pruebas reales JPA de colisión concurrente y error de integridad ajeno a duplicados |
| Fallos de consumo | Mensajes inválidos llegan al DLT del origen, preservando partición; fallo temporal de tabla H2 hace intento inicial + tres reintentos |
| Fallo del DLT | Sin destino disponible el offset no avanza; al crear el DLT temporal se recupera y avanza. Se borra solo ese tópico temporal |
| Grupo/offsets | Verificación real de offsets confirmados frente al final de las seis particiones; lag cero, contemplando particiones vacías |
| BFF | Cinco eventos reales de Workorders leídos desde Audit; Admin/Auditor 200, Cliente/Operador 403, sin token/firma inválida 401, sin scope 403 |
| Indisponibilidad | Audit detenido: BFF devuelve 503 controlado |
| Angular | Vista renderizada en Vitest con HttpClient real a BFF; muestra transición y edición y realiza nueva consulta. No utiliza mock del cliente ni del backend |

Herramientas y resultados detallados permanecen en `evidencias-locales/` (ignorado).
La primera ejecución del verificador detectó dos errores en la herramienta:
particiones vacías sin offset confirmado y salida de grupo diferida al terminar
procesos en Windows. Ambos se corrigieron y la ejecución HTTP completa pasó.
Los registros iniciales con fallos no se presentan como evidencia aprobada.

## Límites y comprobaciones pendientes

- **Oracle**: no se conectó a RDS ni se ejecutaron los SQL preparados. Revisar y
  aplicar las migraciones de Workorders/Audit, probar constraints, tipos Oracle,
  duplicado concurrente, rollback y reinicio con datos persistidos en el entorno
  coordinado. H2, incluso en modo Oracle, no certifica estas diferencias.
- **Entra/MSAL**: la firma, issuer, audience, scope y roles se probaron localmente;
  falta login real Admin/Auditor, tokens delegados y recorrido por Gateway/Angular.
- **RabbitMQ**: sus dos nodos permanecieron saludables y sin recrearse. Notify
  sigue con correos/tickets y Report con PDFs; no se les trasladó a Kafka.
  Las pruebas externas Rabbit quedaron omitidas: faltan verificaciones reales de
  creación/cancelación, colas, correo SMTP, PDF y volúmenes existentes.
- **Entrega de eventos**: la publicación es posterior al commit; no hay outbox.
  Una caída entre commit y envío puede dejar una orden sin evento. Decidir su
  tratamiento antes de exigir garantía de entrega frente a caídas del productor.
- **Despliegue**: listeners alcanzables, red privada, puertos, capacidad de EC2 y
  permisos se comprobarán manualmente. No se creó ni modificó infraestructura AWS.

## Orden recomendado de revisión

1. Contratos y clúster (Infra #16/#17).
2. Audit #4 y #5 antes de habilitar ediciones del productor Workorders #15.
3. Compose Infra #20, BFF #24 y Frontend #33; reproducir este verificador.
4. Kafka Admin y su Compose independiente; usar un tópico temporal para la demo.
5. Migraciones Oracle y prueba Rabbit/SMTP/PDF coordinadas; luego despliegue manual.
