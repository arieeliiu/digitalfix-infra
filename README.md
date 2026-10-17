# DigitalFix — Infraestructura

Repositorio encargado de centralizar la configuración de despliegue del backend de DigitalFix.

Contiene el único `docker-compose.yml` utilizado para construir y ejecutar:

```text
BFF
Catalog
Workorders
```

Infraestructura no es un microservicio.

## Integrantes

- Ariel Molina
- Lucas Ferrada

## Arquitectura actual

El flujo desplegado de DigitalFix es:

```text
Angular
   ↓
Microsoft Entra ID
   ↓
Access Token JWT
   ↓
AWS API Gateway
   ↓
BFF en Amazon EC2
   ├──→ Catalog
   │       ↓
   │    Oracle RDS
   │
   └──→ Workorders
            ├──→ Catalog
            │       ↓
            │    Oracle RDS
            │
            ↓
         Oracle RDS
```

El frontend utiliza API Gateway como punto de entrada al backend.

Los microservicios se ejecutan mediante Docker Compose dentro de Amazon EC2.

Catalog y Workorders utilizan Oracle Database en Amazon RDS para persistencia.

## Componentes desplegados

Actualmente el backend funcional está formado por:

```text
digitalfix-ms-bff
digitalfix-ms-catalog
digitalfix-ms-workorders
```

Los repositorios:

```text
digitalfix-ms-notify
digitalfix-ms-audit
digitalfix-ms-report
```

permanecen fuera del despliegue funcional de esta etapa.

## Estructura requerida

Los repositorios deben encontrarse como directorios hermanos:

```text
digitalfix-backend/
├── digitalfix-infra/
│   ├── docker-compose.yml
│   └── apps/
│       ├── .env.example
│       └── .env
│
├── digitalfix-ms-bff/
│   └── Dockerfile
│
├── digitalfix-ms-catalog/
│   └── Dockerfile
│
└── digitalfix-ms-workorders/
    └── Dockerfile
```

Los contextos de construcción utilizados por Docker Compose dependen de esta estructura.

Los comandos de despliegue deben ejecutarse desde:

```text
digitalfix-infra
```

## Docker Compose

El archivo:

```text
docker-compose.yml
```

construye y ejecuta los tres servicios del backend.

Servicios:

```text
bff
catalog
workorders
```

Todos comparten la red:

```text
digitalfix-backend
```

de tipo:

```text
bridge
```

## Puertos

La distribución actual es:

| Servicio | Puerto interno | Publicado al host |
|---|---:|---:|
| BFF | 8080 | 8080 |
| Catalog | 8081 | No |
| Workorders | 8082 | No |

Solo el BFF publica:

```text
8080:8080
```

Catalog y Workorders utilizan `expose` para documentar sus puertos internos.

Por lo tanto, el acceso normal es:

```text
Internet
↓
API Gateway
↓
EC2:8080
↓
BFF
```

y desde el BFF:

```text
BFF
→ catalog:8081
→ workorders:8082
```

## Comunicación interna

Docker proporciona resolución DNS utilizando los nombres de los servicios.

La comunicación configurada es:

| Origen | Destino | Configuración |
|---|---|---|
| BFF | `http://catalog:8081` | `DIGITALFIX_CATALOG_URL` |
| BFF | `http://workorders:8082` | `DIGITALFIX_WORKORDERS_URL` |
| Workorders | `http://catalog:8081` | `CATALOG_URL` |
| Catalog | Oracle RDS | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` |
| Workorders | Oracle RDS | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` |

Dentro de un contenedor:

```text
localhost
```

hace referencia al propio contenedor.

Por esta razón, los microservicios utilizan los nombres:

```text
catalog
workorders
```

para comunicarse entre sí.

## Variables de entorno

Las credenciales de Oracle se almacenan localmente en:

```text
apps/.env
```

Este archivo no debe subirse a GitHub.

Ejemplo:

```dotenv
DB_URL=jdbc:oracle:thin:@//TU_ENDPOINT_RDS:1521/TU_SERVICIO_ORACLE
DB_USERNAME=usuario_oracle
DB_PASSWORD='TU_PASSWORD_ORACLE'
```

El archivo:

```text
apps/.env.example
```

puede utilizarse como referencia sin incluir credenciales reales.

## Oracle RDS

Catalog y Workorders utilizan la misma instancia Oracle RDS.

Ambos reciben:

```text
DB_URL
DB_USERNAME
DB_PASSWORD
```

desde Docker Compose.

Oracle RDS no forma parte de Docker Compose.

La arquitectura es:

```text
EC2
├── BFF
├── Catalog ───────→ RDS
└── Workorders ────→ RDS
```

Catalog y Workorders necesitan conectividad hacia Oracle por:

```text
TCP 1521
```

## Security Groups

La instancia EC2 debe poder alcanzar la instancia RDS por:

```text
TCP 1521
```

La configuración habitual es permitir en el Security Group de RDS el tráfico proveniente desde el Security Group utilizado por EC2.

Si RDS está detenida o no existe conectividad hacia el puerto `1521`, Catalog y Workorders no podrán iniciar correctamente su conexión con Oracle.

## Credenciales

Las credenciales reales nunca deben almacenarse en:

```text
README.md
application.properties
docker-compose.yml
GitHub
```

Los microservicios reciben las credenciales mediante variables de entorno.

El BFF no utiliza Oracle y por lo tanto no requiere:

```text
DB_URL
DB_USERNAME
DB_PASSWORD
```

## Validar configuración

Desde:

```text
digitalfix-infra
```

ejecutar:

```bash
docker compose --env-file apps/.env -f docker-compose.yml config --quiet
```

Este comando permite validar la configuración sin imprimir intencionalmente las credenciales en el README.

## Construcción y ejecución

Construir y levantar todo el backend:

```bash
docker compose --env-file apps/.env -f docker-compose.yml up -d --build
```

## Construcción secuencial

En una instancia EC2 con recursos limitados puede ser conveniente construir los servicios uno por uno:

```bash
docker compose --env-file apps/.env -f docker-compose.yml build bff
docker compose --env-file apps/.env -f docker-compose.yml build catalog
docker compose --env-file apps/.env -f docker-compose.yml build workorders
docker compose --env-file apps/.env -f docker-compose.yml up -d
```

## Estado de los contenedores

Consultar:

```bash
docker compose --env-file apps/.env -f docker-compose.yml ps
```

El estado esperado es que los tres servicios se encuentren ejecutándose:

```text
bff
catalog
workorders
```

## Logs

Consultar los últimos registros:

```bash
docker compose --env-file apps/.env -f docker-compose.yml logs --tail=100 bff catalog workorders
```

Consultar un servicio específico:

```bash
docker compose --env-file apps/.env -f docker-compose.yml logs --tail=100 bff
```

```bash
docker compose --env-file apps/.env -f docker-compose.yml logs --tail=100 catalog
```

```bash
docker compose --env-file apps/.env -f docker-compose.yml logs --tail=100 workorders
```

## Reiniciar un servicio

Ejemplo para reconstruir únicamente BFF:

```bash
docker compose --env-file apps/.env -f docker-compose.yml up -d --build bff
```

Esto permite actualizar un servicio sin reconstruir necesariamente todo el backend.

## Detener el backend

Ejecutar:

```bash
docker compose --env-file apps/.env -f docker-compose.yml down
```

No es necesario eliminar los datos almacenados en RDS, ya que Oracle funciona como servicio externo.

## Dependencias de arranque

Docker Compose utiliza:

```text
depends_on
```

para establecer el orden básico de inicio.

Actualmente:

```text
BFF
depende de
Catalog + Workorders

Workorders
depende de
Catalog
```

`depends_on` no garantiza que Spring Boot o RDS ya estén completamente disponibles.

Por esta razón, después de iniciar los contenedores se debe esperar a que las aplicaciones terminen su arranque antes de realizar las pruebas funcionales.

## Health check del BFF

El BFF dispone de:

```http
GET /healthz
```

Desde EC2 puede comprobarse mediante:

```bash
curl -i http://localhost:8080/healthz
```

La respuesta esperada es:

```text
200 OK
```

## Comprobación de seguridad básica

Sin token:

```bash
curl -i http://localhost:8080/api/perfil
```

la respuesta esperada es:

```text
401 Unauthorized
```

Con un access token válido de DigitalFix se pueden probar las operaciones protegidas.

## API Gateway

AWS API Gateway actúa como punto de entrada externo del backend.

La API utilizada actualmente por el frontend es:

```text
https://9ijsvq2s6j.execute-api.us-east-1.amazonaws.com
```

El flujo es:

```text
Angular
↓
API Gateway
↓
BFF en EC2:8080
```

API Gateway se configura directamente en AWS y no es provisionado mediante este repositorio.

## Rutas de API Gateway

API Gateway reenvía las solicitudes de DigitalFix hacia el BFF.

Entre los recursos utilizados se encuentran:

```text
/api/perfil
/api/workorders
/api/catalog/services
/api/catalog/spare-parts
```

El BFF mantiene la autorización definitiva de las operaciones mediante JWT, scopes y roles.

## CORS

La configuración actual de API Gateway permite el origen de desarrollo:

```text
http://localhost:4200
```

Métodos permitidos:

```text
GET
POST
PUT
DELETE
OPTIONS
```

Headers utilizados:

```text
authorization
content-type
```

Esta configuración permite que Angular realice las solicitudes necesarias para los CRUD desde el navegador.

Si posteriormente el frontend se despliega bajo otro dominio, ese origen deberá agregarse a la configuración CORS.

## Microsoft Entra ID

La autenticación se administra mediante Microsoft Entra ID.

El flujo es:

```text
Usuario
↓
Angular + MSAL
↓
Microsoft Entra ID
↓
Access Token JWT
↓
API Gateway
↓
BFF
```

El BFF valida:

```text
issuer
audience
scope
roles
```

y reenvía el Bearer token hacia los microservicios internos cuando corresponde.

La configuración de Entra ID no se provisiona desde este repositorio.

## Roles utilizados

La aplicación utiliza actualmente:

```text
Admin
Operador
Cliente
```

Los permisos se aplican principalmente en:

```text
Frontend
+
BFF
```

y las reglas de dominio adicionales se encuentran en los microservicios.

## Despliegue en EC2

La instancia EC2 debe disponer de:

- Git;
- Docker Engine;
- Docker Compose;
- acceso HTTPS hacia servicios externos necesarios;
- conectividad hacia Oracle RDS;
- los repositorios del backend;
- archivo `apps/.env`.

La estructura esperada es:

```text
~/digitalfix-backend/
├── digitalfix-infra
├── digitalfix-ms-bff
├── digitalfix-ms-catalog
└── digitalfix-ms-workorders
```

Desde:

```bash
cd ~/digitalfix-backend/digitalfix-infra
```

se ejecutan los comandos Docker Compose.

## Actualización del código en EC2

Antes de reconstruir un servicio, su repositorio debe encontrarse actualizado respecto de la versión estable utilizada para la entrega.

Ejemplo:

```bash
git pull origin main
```

Después se reconstruye el servicio correspondiente.

Ejemplo para BFF:

```bash
docker compose --env-file apps/.env -f docker-compose.yml up -d --build bff
```

## Prueba funcional completa

El flujo que debe comprobarse antes de la presentación es:

```text
Login con Entra ID
        ↓
Angular obtiene JWT
        ↓
Angular llama a API Gateway
        ↓
API Gateway reenvía al BFF
        ↓
BFF valida JWT y rol
        ↓
BFF llama a Catalog o Workorders
        ↓
Microservicio accede a Oracle RDS
        ↓
Respuesta vuelve al frontend
```

Actualmente este flujo permite realizar desde el panel administrativo:

- CRUD de servicios;
- CRUD de repuestos;
- CRUD de órdenes;
- cambios de estado;
- asignación de técnico;
- asociación de repuestos;
- persistencia de datos en Oracle;
- descuento real de stock.

## Flujo de stock

Para una orden con repuestos:

```text
Angular
↓
API Gateway
↓
BFF
↓
Workorders
↓
Catalog
↓
Oracle RDS
```

Catalog valida el stock antes de descontarlo.

Workorders solo completa la asignación después de que Catalog acepta la operación.

## Problemas de conectividad con RDS

Si Catalog o Workorders reinician continuamente, una de las primeras comprobaciones debe ser la conectividad hacia RDS.

Desde EC2 puede verificarse el puerto configurado de Oracle.

Conceptualmente:

```text
EC2
↓ TCP 1521
RDS
```

También debe comprobarse que la instancia RDS se encuentre en estado:

```text
Disponible
```

## Estado actual

Actualmente se encuentra operativo el despliegue:

```text
Angular
→ AWS API Gateway
→ BFF en EC2
→ Catalog / Workorders en EC2
→ Oracle RDS
```

Se verificó funcionalmente:

- comunicación frontend → API Gateway;
- comunicación API Gateway → BFF;
- autenticación mediante Microsoft Entra ID;
- recepción y validación de JWT;
- comunicación BFF → Catalog;
- comunicación BFF → Workorders;
- comunicación Workorders → Catalog;
- conectividad EC2 → Oracle RDS;
- persistencia real;
- CRUD de servicios;
- CRUD de repuestos;
- CRUD de órdenes;
- funcionamiento de CORS desde Angular;
- funcionamiento de los contenedores Docker.

## Servicios fuera de esta etapa

Los siguientes módulos no participan en el Compose actual:

```text
Notify
Audit
Report
```

Su implementación con RabbitMQ o Kafka corresponde a una etapa posterior del caso DigitalFix.

No deben agregarse al despliegue actual para esta entrega.

## Flujo de trabajo

Los cambios de infraestructura se integran a `main` mediante Pull Request.

La rama `main` representa la configuración estable utilizada para el despliegue y la entrega.