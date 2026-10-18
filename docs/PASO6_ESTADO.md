# Estado del Paso 6

Inicio: 18 de octubre de 2026. No crear PR, hacer merge ni desplegar en AWS.

| Fase | Repositorio / rama | Estado | Commit / pruebas |
|---|---|---|---|
| A | digitalfix-ms-bff / feature/eval2-integracion-bff | Completado y push | ef3de55; Maven verify: 65 aprobadas, 0 fallidas |
| B | digitalfix-frontend / feature/eval2-integracion-frontend | Completado y push | ac841d3; 109 pruebas aprobadas; build correcto con advertencias de tamaño |
| C | digitalfix-infra / feature/eval2-integracion-infra | Completado y push | 6421406; Compose válido; seis imágenes construidas; verificador real correcto con pendientes explícitos |
| D | Validación conjunta | Completada en el alcance local; externos pendientes | Evidencias finales en el commit que contiene esta revisión; consultar git log -1 |

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

## Matriz de verificación final (18/10/2026)

| Componente | Código integrado | Build | Pruebas | Integración real | Pendiente |
|---|---|---|---|---|---|
| BFF | ef3de55, push | Maven verify + Docker OK | 65 aprobadas, 0 fallidas | HTTP downstream simulado, JWT firmado localmente | Entra/Gateway/Workorders desplegados juntos |
| Frontend | ac841d3, push | Angular OK | 109 aprobadas, 0 fallidas | DOM e interceptor MSAL con dobles; no navegador autenticado en AWS | MSAL real y CORS PATCH en Gateway |
| Infra | 6421406 + evidencia final, push | Seis imágenes OK, Compose válido | Verificador normal exit 0; barrera exigir-listo exit 1 esperado | Clúster, topología, puertos, redes y volúmenes inspeccionados | Variables reales, cuenta Workorders, cambio a Compose central |
| Catalog | Sin cambios de código | Docker OK | 10 aprobadas, H2/mocks | Contenedor anterior responde TCP 8080, imagen nueva configura 8081 | Recrear versión nueva en despliegue autorizado; validar Oracle |
| Workorders | Contrato existente conservado | Docker OK | 33 locales + 2 Rabbit reales aprobadas | Cinco comandos persistentes, confirms, retorno mandatory, repetición sin duplicados | Oracle real; flujo conjunto con consumidores desplegados |
| Notify | Sin cambios de código | Docker OK | 13 aprobadas; PDF/disco reales y SMTP/canal simulados | Colas/bindings/DLQ y volumen reales; consumidor detenido | SMTP autorizado, entrega/adjunto y ACK/DLQ en flujo integrado |
| Report | Sin cambios de código | Docker OK | 15 aprobadas; PDF/disco reales, canal simulado | Un consumidor real; 12 archivos PDF/identidad/éxito sin cambios de hash tras reiniciar | Cierre de orden real hasta PDF en despliegue integrado |
| Rabbit Admin | Sin cambios de código | Docker OK | 11 aprobadas, 2 integraciones externas omitidas | HTTP local: health 200 y ruta protegida sin JWT 401; binding loopback | JWT Entra real con scope/rol Admin y operación permitida |

**Total: 258 pruebas aprobadas, 0 fallidas en las ejecuciones finales.**
Workorders muestra 35 tests/2 skipped en la suite local: esos dos se ejecutaron
después contra RabbitMQ real y pasaron. Rabbit Admin muestra 13/2 skipped: esos dos
siguen omitidos, no se suman como aprobados. No se repitieron suites que ya pasaron.
Los tests de otros repositorios no modificaron su código ni sus ramas.

Comandos reproducibles utilizados:

- BFF: `./mvnw.cmd -B verify`.
- Frontend: `npm test -- --watch=false` y `npm run build`.
- Catalog, Workorders, Notify, Report, Rabbit Admin: `./mvnw.cmd -B test` en cada repo.
- Infra: `docker compose --env-file apps/.env.example -f docker-compose.yml build`.
- Workorders: `docker build --target pruebas -t digitalfix-workorders:pruebas .`,
  luego `python scripts/verificar_rabbit.py`. Crea usuario/vhost únicos temporales,
  compara topología digitalfix antes/después y elimina solo sus recursos de prueba.
- Infra: `python scripts/verificar_paso6.py --env-file apps/.env.example --broker`.
  Repetido con `--exigir-listo`: rechazo esperado por SMTP/configuración ilustrativa,
  ausencia de consumidores Notify y cuenta publicadora sin crear.

Las evidencias locales completas permanecen en logs ignorados `paso6-*.log` en Infra,
logs de BFF/Frontend y `digitalfix-ms-workorders/evidencias-locales/rabbit-real.log`.
El resumen versionado no incluye secretos, cuerpos de correos ni documentos privados.

## Hallazgos y límites

- Frontend build con advertencias: bundle inicial 643,66 kB (presupuesto 500 kB)
  y SCSS de órdenes 4,11 kB (presupuesto 4 kB); build exitoso, presupuestos sin relajar.
- El Catalog **anterior en ejecución** es otra imagen y escucha 8080. TCP 8081 rechazó
  conexión; 8080 respondió. La fuente actual y la imagen construida usan 8081, coherente
  con BFF y Workorders del Compose central. No cambiar CATALOG_URL a 8080 para acomodar
  el contenedor obsoleto: recrear el conjunto actualizado cuando se autorice el despliegue.
- DNS/TCP a ambos brokers 5672 y al Workorders anterior 8082 comprobados. Esto no es
  una validación de CRUD sobre Oracle. No se arrancó el backend nuevo contra RDS.
- Report se reinició sin borrar datos: 12 hashes iguales antes/después, consumidor
  nuevamente registrado. Notify quedó detenido como antes; no se enviaron correos reales.
- No se ejecutaron fallos ni reprocesos sobre DLQ de negocio. ACK/NACK/reintentos se
  comprobaron en suites de consumidores; las pruebas reales de pasos anteriores no se
  presentan como ejecutadas nuevamente aquí.
- No se implementó Outbox ni HA; permanecen las limitaciones documentadas del diseño.
- AWS, esquema Oracle, identidades/roles Entra y SMTP externo no fueron modificados.

## Avance respecto de los once criterios del encargo

Código y configuración solicitados implementados. **9 de 11 criterios verificados
en su alcance completo local (aproximadamente 82% del cierre verificable)**; dos
requieren el entorno externo antes de certificarlos sin reservas:

| Criterio | Estado |
|---|---|
| BFF acepta/valida/reenvía correos | Comprobado con pruebas HTTP |
| BFF expone contactos con autorización | Comprobado con JWT locales y permisos |
| Angular registra/corrige contactos | Comprobado en pruebas DOM/API |
| Angular sigue funcionando con MSAL | Parcial: interceptor/guards pasan; login Entra real pendiente |
| Compose integra servicios con RabbitMQ | Configuración e imágenes verificadas; activación real documentada |
| Workorders conserva acceso Oracle/Catalog | Parcial: configuración/H2/mocks verificados; Oracle integrado pendiente |
| Notify/Report conservan volúmenes | Inspeccionados y reutilizados explícitamente |
| Rabbit Admin sin exposición pública | Loopback comprobado, 401 sin JWT |
| Builds/pruebas automatizadas pasan | 258 aprobadas, seis imágenes y Angular OK |
| Documentación/configuración reproducible | Guías y verificador versionados |
| Validaciones externas identificadas | Detalladas a continuación |

El porcentaje no significa que el 82% del flujo productivo se haya ejecutado; el
flujo Angular → AWS → Oracle → SMTP/PDF todavía necesita la validación conjunta real.

## Continuación

1. Recuperar las ramas `feature/eval2-integracion-bff` (ef3de55),
   `feature/eval2-integracion-frontend` (ac841d3) y `feature/eval2-integracion-infra`
   (6421406 más commit final de evidencias). Consultar `git log -2`; no rehacer fases
   A/B/C ni las 258 pruebas salvo cambios nuevos. El usuario crea los PR y merges.
2. Completar `apps/.env` con secretos correctos, sin sobrescribir los existentes.
   Crear la cuenta Workorders ausente siguiendo INTEGRACION.md. Confirmar SMTP válido
   y destinatarios de prueba autorizados antes de activar Notify.
3. Obtener autorización para aplicar/verificar columnas Oracle y actualizar el
   despliegue. Seguir DESPLIEGUE_PENDIENTE.md; `ddl-auto=update` puede ejecutar DDL
   al iniciar Workorders, por lo que no hacerlo antes de esa preparación.
4. Seguir INTEGRACION.md: detener productores y proyectos independientes, reutilizar
   volúmenes, iniciar consumidores, exigir preflight exitoso, recrear Catalog actualizado
   a 8081 y después Workorders/BFF/Rabbit Admin. No duplicar consumidores.
5. Preparar y autorizar PATCH/CORS en API Gateway, conservar rutas anteriores.
   Probar login real MSAL, propietario y Admin/Operador, rechazos de acceso ajeno.
6. Con datos autorizados, verificar ASIGNADA/EN_DESPLAZAMIENTO/CERRADA, stock único,
   recepción SMTP/adjunto, PDF Report y corrección de contactos en órdenes cerradas.
   Documentar resultados reales y conservar las evidencias de idempotencia.
7. Validar Rabbit Admin con JWT Entra Admin real. Mantener privadas sus rutas y
   probar DLQ solo en un entorno aislado. No purgar mensajes ni eliminar volúmenes.
