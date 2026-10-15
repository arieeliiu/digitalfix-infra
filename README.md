# DigitalFix - Infraestructura

Este repositorio contiene el único `docker-compose.yml` del backend. Infraestructura no es un microservicio: Compose construye las imágenes desde los Dockerfile de BFF, Catalog y Workorders y ejecuta los tres contenedores en EC2 o en local.

## Estructura requerida

```text
digitalfix-backend/
├── digitalfix-infra/
│   ├── docker-compose.yml
│   └── apps/
│       ├── .env.example
│       └── .env              # Credenciales reales, excluido de Git
├── digitalfix-ms-bff/
│   └── Dockerfile
├── digitalfix-ms-catalog/
│   └── Dockerfile
└── digitalfix-ms-workorders/
    └── Dockerfile
```

Los contextos de construcción son relativos al archivo Compose. Ejecuta los comandos siguientes desde `digitalfix-infra`. No ejecutes un Compose separado desde el BFF.

## Credenciales

Copia `apps/.env.example` a `apps/.env` y completa los tres valores:

```dotenv
DB_URL=jdbc:oracle:thin:@//TU_ENDPOINT_RDS:1521/TU_SERVICIO_ORACLE
DB_USERNAME=usuario_oracle
DB_PASSWORD='TU_PASSWORD_ORACLE'
```

El usuario y sus permisos deben existir previamente en Oracle. El despliegue actual utiliza las mismas credenciales para Catalog y Workorders. Compose entrega a cada servicio `DB_URL`, `DB_USERNAME` y `DB_PASSWORD`, que sus archivos `application.properties` consumen. Las variables obligatorias vacías o ausentes detienen Compose antes del despliegue. No subas `.env` a Git; créalo también en EC2.

## Comunicación

| Origen | Destino | Configuración en Compose |
|---|---|---|
| BFF | `http://catalog:8081` | `DIGITALFIX_CATALOG_URL` |
| BFF | `http://workorders:8082` | `DIGITALFIX_WORKORDERS_URL` |
| Workorders | `http://catalog:8081` | `CATALOG_URL` |
| Catalog y Workorders | Oracle RDS | `DB_URL`, `DB_USERNAME` y `DB_PASSWORD` compartidos |

Los servicios comparten la red bridge `digitalfix-backend`. Docker resuelve los nombres `catalog` y `workorders`. `localhost` dentro de un contenedor apunta al propio contenedor.

Solo BFF publica `8080:8080`. Catalog escucha en `8081` y Workorders en `8082`, sin publicar puertos al host. `expose` documenta los puertos internos; no cambia el puerto de Spring ni es una barrera entre contenedores de la misma red.

`depends_on` ordena el arranque, pero no garantiza que Spring o RDS estén listos. Espera a que las aplicaciones terminen de iniciar antes de probar.

## Construcción y ejecución

Validar sin mostrar credenciales:

```bash
docker compose --env-file apps/.env -f docker-compose.yml config --quiet
```

Construir y levantar:

```bash
docker compose --env-file apps/.env -f docker-compose.yml up -d --build
```

En una EC2 con poca memoria, construir secuencialmente:

```bash
docker compose --env-file apps/.env -f docker-compose.yml build bff
docker compose --env-file apps/.env -f docker-compose.yml build catalog
docker compose --env-file apps/.env -f docker-compose.yml build workorders
docker compose --env-file apps/.env -f docker-compose.yml up -d
```

Inspeccionar y detener:

```bash
docker compose --env-file apps/.env -f docker-compose.yml ps
docker compose --env-file apps/.env -f docker-compose.yml logs --tail=100 bff catalog workorders
docker compose --env-file apps/.env -f docker-compose.yml down
```

Al migrar desde cualquiera de los Compose anteriores, detén primero ese despliegue con su archivo y nombre de proyecto originales para liberar el puerto 8080. No elimines volúmenes. El nuevo archivo en la raíz puede usar un nombre de proyecto distinto al antiguo archivo dentro de `apps`.

## Comprobación funcional

Desde el host que ejecuta Docker:

```bash
curl -i http://localhost:8080/healthz
curl -i http://localhost:8080/api/perfil
```

Se espera `200` en salud y `401` en perfil sin token. Esto solo verifica el BFF. Para comprobar comunicación y persistencia, usa un access token válido de Entra con el scope y rol correspondientes y consulta `/api/catalog/services` y `/api/workorders` a través del BFF. Prueba también una operación de órdenes que utilice Catalog.

## EC2 y dependencias externas

EC2 necesita Docker Engine y Compose, acceso a las imágenes base y salida HTTPS a Entra. Catalog y Workorders requieren acceso a RDS por TCP 1521; permite ese acceso en el Security Group de RDS desde el Security Group de EC2. Oracle RDS no es un contenedor de este Compose.

API Gateway y su integración con EC2 se configuran por separado. Este archivo no provisiona AWS, certificados TLS ni Entra ID. El BFF valida el JWT y reenvía el Bearer a los servicios. La configuración de red de Docker no sustituye la autorización de las aplicaciones.

Notify, Audit y Report no forman parte de este despliegue.
