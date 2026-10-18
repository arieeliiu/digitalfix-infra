# Estado del Paso 6

Inicio: 18 de octubre de 2026. No crear PR, hacer merge ni desplegar en AWS.

| Fase | Repositorio / rama | Estado | Commit / pruebas |
|---|---|---|---|
| A | digitalfix-ms-bff / feature/eval2-integracion-bff | Completado y push | ef3de55; Maven verify: 65 aprobadas, 0 fallidas |
| B | digitalfix-frontend / feature/eval2-integracion-frontend | Completado y push | ac841d3; 109 pruebas aprobadas; build correcto con advertencias de tamaño |
| C | digitalfix-infra / feature/eval2-integracion-infra | Implementado, validado; commit de esta revisión | Compose válido; seis imágenes construidas; verificador real correcto con pendientes explícitos |
| D | Validación conjunta | Pendiente | Pendiente |

Las tres ramas nacen de origin/main y los repositorios estaban limpios. Se preservan los pasos 1–5. Cada fase se guardará con commit y push antes de continuar. Oracle RDS, AWS y SMTP externo no se modificarán durante pruebas.

## Evidencias de infraestructura

`docker compose --env-file apps/.env.example -f docker-compose.yml config --quiet` y
`build` correctos para los seis servicios. `scripts/verificar_paso6.py --broker`
confirma dos nodos activos, tres exchanges, seis colas classic durables, nueve bindings,
DLQ, volúmenes existentes y ausencia de consumidores duplicados. Cero mensajes al
inspeccionar. No certifica el flujo extremo a extremo.

RabbitMQ usa `digitalfix-mq_rabbitmq1-data` y `digitalfix-mq_rabbitmq2-data`, sin cambios
en su Compose. Notify/Report reutilizan `digitalfix-notify_notify-data` y
`digitalfix-report_report-data` como externos. Se conserva proyecto backend `apps`.

Pendiente operativo: cuenta `digitalfix_workorders`; completar `apps/.env` integrado
con credenciales existentes y SMTP válido; Notify detenido (cero consumidores de
email/dispatch). Report mantiene un consumidor. No se iniciaron servicios centrales
ni se cambiaron usuarios/volúmenes/mensajes de negocio. Docker Desktop estaba cerrado
al retomar y se inició para completar verificaciones. Se corrigió una ruta del nuevo
verificador de permisos; su ejecución final es correcta.

Guías: [arranque y permisos](INTEGRACION.md), [Gateway/CORS y Oracle](DESPLIEGUE_PENDIENTE.md).
Variables sin secretos: [apps/.env.example](../apps/.env.example).

## Continuación

No repetir fases A/B/C. Falta fase D: ejecutar suites existentes de Catalog,
Workorders, Notify, Report y Rabbit Admin; validar publicación real con el script
aislado de Workorders; completar matriz/evidencias y commit/push final de Infra.
No iniciar consumidores duplicados ni borrar volúmenes. No ejecutar scripts de
Notify/Report que inyecten fallos en las colas de negocio existentes.
