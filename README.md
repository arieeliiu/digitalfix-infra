# DigitalFix - Infraestructura

Repositorio encargado de la configuración de infraestructura del backend de DigitalFix.

Actualmente contiene la orquestación mediante Docker Compose de los servicios necesarios para la evaluación:

- `digitalfix-ms-bff`
- `digitalfix-ms-catalog`
- `digitalfix-ms-workorders`

Los servicios se ejecutan mediante contenedores Docker y se despliegan en una instancia Amazon EC2.

## Arquitectura actual

```text
Cliente
  |
  | HTTP :8080
  v
Amazon EC2
  |
  └── Docker Compose
       |
       ├── BFF :8080
       |
       ├── Catalog :8080
       |      |
       |      └──────────┐
       |
       └── Workorders :8082
              |          |
              └──────────┴──> Oracle Database en Amazon RDS
```

Los tres contenedores comparten la red interna:

```text
digitalfix-backend
```

Solo el BFF publica un puerto hacia el exterior.

Catalog y Workorders permanecen accesibles únicamente dentro de la red Docker y se conectan directamente a Oracle RDS para persistencia.

## Estructura

```text
digitalfix-infra/
├── apps/
│   ├── compose.yml
│   └── .env.example
├── .gitignore
└── README.md
```

Los `Dockerfile` pertenecen a sus respectivos repositorios:

```text
digitalfix-ms-bff/
digitalfix-ms-catalog/
digitalfix-ms-workorders/
```

## Requisitos

Para ejecución local:

- Docker
- Docker Compose
- Acceso de red a Oracle RDS

Para despliegue:

- Instancia Amazon EC2
- Docker instalado en EC2
- Docker Compose instalado en EC2
- Acceso desde EC2 hacia Oracle RDS por TCP 1521

## Variables de entorno

La configuración sensible se administra mediante:

```text
apps/.env
```

Este archivo no se almacena en Git.

Se incluye:

```text
apps/.env.example
```

para documentar las variables necesarias.

Variables utilizadas:

```dotenv
DB_URL=jdbc:oracle:thin:@//HOST_RDS:1521/SERVICIO

CATALOG_DB_USERNAME=usuario_catalog
CATALOG_DB_PASSWORD=contraseña_catalog

WORKORDERS_DB_USERNAME=usuario_workorders
WORKORDERS_DB_PASSWORD=contraseña_workorders
```

Docker Compose transforma las variables específicas de cada servicio en las variables que esperan las aplicaciones Spring Boot:

```text
DB_URL
DB_USERNAME
DB_PASSWORD
```

No se deben almacenar contraseñas, tokens, archivos `.env` ni claves privadas en el repositorio.

## Ejecución local

Los siguientes repositorios deben estar ubicados en el mismo directorio padre:

```text
digitalfix/
├── digitalfix-infra/
├── digitalfix-ms-bff/
├── digitalfix-ms-catalog/
└── digitalfix-ms-workorders/
```

Desde `digitalfix-infra`:

```powershell
docker compose --env-file apps\.env -f apps\compose.yml build
```

Levantar los servicios:

```powershell
docker compose --env-file apps\.env -f apps\compose.yml up -d
```

Consultar su estado:

```powershell
docker compose --env-file apps\.env -f apps\compose.yml ps
```

Consultar logs:

```powershell
docker compose --env-file apps\.env -f apps\compose.yml logs
```

Detener los servicios:

```powershell
docker compose --env-file apps\.env -f apps\compose.yml down
```

## Puertos

| Servicio | Puerto interno | Publicado |
|---|---:|---:|
| BFF | 8080 | 8080 |
| Catalog | 8080 | No |
| Workorders | 8082 | No |
| Oracle RDS | 1521 | Fuera de Docker Compose |

Catalog y Workorders no se publican directamente hacia Internet.

El BFF funciona como punto de entrada actual al backend.

## Persistencia

Oracle Database se ejecuta como servicio administrado en Amazon RDS y no forma parte de Docker Compose.

Catalog y Workorders reciben la configuración JDBC mediante variables de entorno.

Durante las pruebas se verificó correctamente la conexión con:

- Oracle JDBC Driver
- Oracle Database 19c
- Amazon RDS
- Hibernate / Spring Data JPA

Los datos permanecen almacenados en RDS independientemente del ciclo de vida de los contenedores.

## Despliegue en Amazon EC2

Se utilizó una instancia Amazon Linux 2023 dentro de la misma VPC que Oracle RDS.

La EC2 ejecuta:

```text
Docker
└── Docker Compose
    ├── BFF
    ├── Catalog
    └── Workorders
```

Los repositorios se clonan dentro de:

```text
~/digitalfix/
```

### Construcción de imágenes

Debido a los recursos limitados de la instancia utilizada, las imágenes se construyeron individualmente:

```bash
docker compose --env-file apps/.env -f apps/compose.yml build bff

docker compose --env-file apps/.env -f apps/compose.yml build catalog

docker compose --env-file apps/.env -f apps/compose.yml build workorders
```

Posteriormente se levantaron en conjunto:

```bash
docker compose --env-file apps/.env -f apps/compose.yml up -d
```

### Memoria auxiliar

La instancia utilizada dispone de memoria limitada, por lo que se configuraron 2 GiB de swap para evitar que el proceso de compilación agotara la memoria disponible.

La compilación individual de los servicios evita además ejecutar simultáneamente tres procesos Maven.

## Seguridad de red

El Security Group de EC2 permite:

```text
TCP 22   → acceso SSH administrativo
TCP 8080 → acceso al BFF
```

Catalog y Workorders no poseen puertos públicos.

El Security Group asociado a RDS permite:

```text
TCP 1521 → desde el Security Group de la EC2
```

De esta forma, los servicios desplegados en EC2 pueden conectarse a Oracle sin publicar la base de datos de forma general hacia Internet.

## Seguridad JWT

El BFF funciona como OAuth 2.0 Resource Server y valida tokens JWT emitidos por Microsoft Entra ID.

Como prueba del despliegue se consultó:

```http
GET /api/perfil
```

sin enviar un access token.

El BFF desplegado en EC2 respondió:

```text
HTTP/1.1 401
WWW-Authenticate: Bearer
```

Este resultado verifica que:

- el BFF está accesible desde fuera de EC2;
- el contenedor funciona correctamente;
- Spring Security permanece activo después de la dockerización;
- un endpoint protegido rechaza solicitudes sin JWT.

## Verificaciones realizadas

Se comprobó:

- construcción correcta de las imágenes Docker de BFF, Catalog y Workorders;
- ejecución simultánea mediante Docker Compose;
- red interna compartida entre los contenedores;
- exposición pública únicamente del BFF;
- conexión de Catalog con Oracle RDS;
- conexión de Workorders con Oracle RDS;
- persistencia externa mediante RDS;
- exclusión del archivo `.env` de Git;
- despliegue de los tres servicios en Amazon EC2;
- acceso remoto al BFF desplegado;
- respuesta `401 Unauthorized` al consultar un endpoint protegido sin JWT.

## Fuera de alcance de esta configuración

Esta tarea no implementa:

- AWS API Gateway;
- JWT Authorizer de API Gateway;
- integración completa Angular → API Gateway → BFF;
- comunicación funcional BFF → Catalog / Workorders;
- RabbitMQ;
- Kafka.

Estos componentes se implementan en tareas separadas del proyecto.

## Flujo actual

La infraestructura implementada actualmente corresponde a:

```text
Cliente
   |
   v
EC2 :8080
   |
   v
BFF
```

Dentro de EC2 también se encuentran desplegados Catalog y Workorders.

La arquitectura final incorporará posteriormente:

```text
Angular
   |
   v
API Gateway
   |
   v
BFF
   |
   +---------> Catalog
   |
   +---------> Workorders
                  |
                  v
              Oracle RDS
```