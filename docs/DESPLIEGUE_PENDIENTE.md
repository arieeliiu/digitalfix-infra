# Preparación de despliegue: requiere intervención autorizada posterior

No se cambiaron AWS ni Oracle durante el Paso 6. La URL histórica de Gateway es
`https://9ijsvq2s6j.execute-api.us-east-1.amazonaws.com`; su tipo, rutas, stage y
configuración actual deben inspeccionarse en la cuenta antes de modificarla.

## API Gateway y CORS

1. Abrir la API existente en us-east-1, identificar si es HTTP API o REST API y
   exportar/anotar sus rutas, integración, autorizadores, stage y CORS actuales.
   Conservar la URL/stage que usa `src/environments/environment.ts` de Angular.
2. Comprobar si una ruta ANY/proxy ya cubre `PATCH /api/workorders/{id}/contactos`.
   Si no, agregar esa ruta/método con la misma integración BFF de las demás órdenes.
   Mapear `{id}` al path real, método PATCH, JSON sin modificar y reenviar Authorization.
   No exponer Workorders directamente ni añadir identidad desde parámetros del cliente.
3. HTTP API: añadir PATCH a allowMethods conservando GET, POST, PUT, DELETE y OPTIONS;
   conservar los orígenes autorizados (desarrollo http://localhost:4200) y headers
   authorization/content-type. No reemplazar la lista por PATCH solamente. Con CORS
   configurado, Gateway responde preflight. Si `$default` tiene autorizador, preparar
   una ruta OPTIONS `/{proxy+}` sin autorización conforme a la guía oficial.
4. REST API: crear/configurar OPTIONS sin exigir JWT para el recurso, con origen
   permitido, métodos anteriores más PATCH y headers authorization/content-type.
   En integración HTTP proxy, el backend debe devolver los headers CORS de la respuesta
   real: habilitar OPTIONS en consola no los añade al proxy. Revisar el mecanismo
   existente antes de decidir cambios de BFF; no asumir que el preflight basta.
5. Desplegar solo después de autorización: REST requiere deployment al stage existente;
   HTTP depende de si auto-deploy está activo. Probar también errores 400/401/403/404
   con CORS, además del PATCH exitoso. Mantener JWT, issuer, audience, scope y roles.

Referencias oficiales consultadas para preparar estos pasos:
[CORS HTTP API](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-cors.html),
[CORS REST API](https://docs.aws.amazon.com/apigateway/latest/developerguide/how-to-cors.html),
[integración HTTP proxy](https://docs.aws.amazon.com/apigateway/latest/developerguide/setup-http-integrations.html).

Prueba posterior de preflight (sustituir URL/stage e ID, sin token):

```powershell
curl.exe -i -X OPTIONS 'https://API/STAGE/api/workorders/42/contactos' -H 'Origin: http://localhost:4200' -H 'Access-Control-Request-Method: PATCH' -H 'Access-Control-Request-Headers: authorization,content-type'
```

Esperar respuesta 2xx y origen, PATCH y headers permitidos. Después, probar desde
Angular con sesión real de propietario y Admin/Operador. Un cliente ajeno debe recibir
403/404 y sin token 401. Verificar rutas anteriores de Catalog y órdenes. El frontend
no requiere cambiar MSAL ni su interceptor para añadir PATCH.

## Oracle RDS

El script existente `digitalfix-ms-workorders/docs/oracle-contactos.sql` agrega
EMAIL_CONTACTO y EMAIL_TECNICO nullable, VARCHAR2(254 CHAR), a ORDENES_TRABAJO.
Consulta USER_TAB_COLUMNS y omite columnas existentes. No elimina datos ni requiere
rellenar órdenes antiguas. Su idempotencia **no verifica el tipo de una columna ya existente**.

Procedimiento para el responsable autorizado:

1. Verificar respaldo/snapshot, endpoint, servicio Oracle y propietario del esquema.
   Consultar columnas antes de ejecutar; si ya existen con tipo/longitud/nullabilidad
   diferentes, detenerse e investigar. No ejecutar sobre un esquema elegido por error.
2. En ventana de mantenimiento y con Workorders detenido, ejecutar el script con el
   propietario mediante SQLcl/SQL*Plus. Oracle DDL confirma implícitamente; no confiar
   en rollback transaccional de ALTER TABLE.
3. Repetir la consulta posterior y verificar ambas filas, DATA_TYPE=VARCHAR2,
   CHAR_LENGTH=254, CHAR_USED=C, NULLABLE=Y. Si las creó Hibernate con semántica BYTE,
   evaluar compatibilidad de longitud explícitamente antes de aprobar el despliegue.
4. Iniciar la versión revisada y verificar lectura de órdenes antiguas, alta y PATCH
   usando datos autorizados. Confirmar que contactos no cambian estado, fechas ni stock.

Consulta de solo lectura para el propietario:

```sql
SELECT column_name, data_type, char_length, char_used, nullable
FROM user_tab_columns
WHERE table_name = 'ORDENES_TRABAJO'
  AND column_name IN ('EMAIL_CONTACTO', 'EMAIL_TECNICO')
ORDER BY column_name;
```

Workorders conserva `spring.jpa.hibernate.ddl-auto=update`, que puede ejecutar DDL
al arrancar. Por eso no se inició contra RDS durante esta integración ni se presenta
el arranque como una comprobación inocua. H2 y mocks no sustituyen validar Oracle real.
Se conservan DB_URL/DB_USERNAME/DB_PASSWORD y CATALOG_URL del despliegue anterior.
