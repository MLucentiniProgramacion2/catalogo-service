[![CI](https://github.com/MLucentiniProgramacion2/catalogo-service/actions/workflows/ci.yml/badge.svg)](https://github.com/MLucentiniProgramacion2/catalogo-service/actions/workflows/ci.yml)

# catalogo-service

Servicio de **catálogo y sincronización** del proyecto integrador 2026 (Programación II). Guarda
una copia local del catálogo de la cátedra (categorías, profesionales y horarios semanales), la
mantiene sincronizada y resuelve las búsquedas sobre esa copia. Es uno de los dos backends del
proyecto, junto con el servicio de turnos.

Este servicio **no** registra ni autentica usuarios: solo **valida** el JWT que emite el servicio
de turnos (ver [ADR 0003](docs/adr/0003-validacion-jwt.md)).

La base (seguridad, JWT, configuración y Liquibase) se generó con JHipster 9.4 como microservicio.
Las funcionalidades de negocio se escriben a mano con arquitectura hexagonal.

## Requisitos

- Docker con Docker Compose (probado con Engine 29.8 y Compose v5.5; el Compose usa `env_file` con
  `format: raw`, que las versiones viejas no entienden)
- Java 25 (Temurin) solo para desarrollar y correr los tests, por ejemplo con SDKMAN:
  `sdk install java 25.0.4-tem`. Para levantar el servicio con Docker Compose no hace falta: la
  imagen se compila adentro de Docker.

No hace falta instalar Maven: se usa el wrapper `./mvnw`.

## Configuración

```bash
cp .env.example .env
```

Completar el `.env` (ignorado por Git):

- `CATALOGO_DB_*`: base PostgreSQL propia del servicio. `CATALOGO_DB_HOST` es `localhost` por
  defecto.
- `JWT_BASE64_SECRET`: clave de firma del JWT. Se genera con
  `openssl rand -base64 64 | tr -d '\n'` y tiene que ser **exactamente la misma** que en
  turnos-service. Si falta o está vacía, la aplicación no arranca.
- `CATEDRA_API_URL` y `CATEDRA_JWT`: URL base de la API de la cátedra y JWT técnico de la cuenta
  del proyecto (ver [ADR 0004](docs/adr/0004-cliente-http-catedra.md)). Si falta alguno o está
  vacío, la aplicación no arranca. El JWT nunca va en logs, issues ni PRs.
- `CATEDRA_CONNECT_TIMEOUT` y `CATEDRA_READ_TIMEOUT` (opcionales, `5s` y `30s` por defecto): tiempo
  para conectar y para recibir la respuesta completa.
- `CATEDRA_KAFKA_BOOTSTRAP_SERVERS`, `CATEDRA_KAFKA_CATALOG_TOPIC` y `CATEDRA_KAFKA_CONSUMER_GROUP`:
  acceso al Kafka de la cátedra (ver [ADR 0005](docs/adr/0005-consumidor-kafka.md)). Si falta alguna o
  está vacía, la aplicación no arranca. Bootstrap y topic salen de `integration`; el grupo es el
  `kafkaConsumerGroupId` de `integration` + `-catalogo`: cada servicio usa uno propio.
- `CATEDRA_REDIS_HOST`, `CATEDRA_REDIS_PORT`, `CATEDRA_REDIS_USERNAME` y `CATEDRA_REDIS_PASSWORD`:
  acceso de solo lectura al Redis de la cátedra (ver [ADR 0006](docs/adr/0006-cliente-redis.md)). Si
  falta alguna o está vacía, la aplicación no arranca. `CATEDRA_REDIS_CONNECT_TIMEOUT` y
  `CATEDRA_REDIS_COMMAND_TIMEOUT` son opcionales (`5s` y `3s`).
- Sincronización (opcionales, ver [docs/sincronizacion.md](docs/sincronizacion.md)):
  - `SYNC_PERIODIC_ENABLED` (`true`) y `SYNC_PERIODIC_INTERVAL` (`5m`, mínimo `1m`): la comprobación
    periódica de versión contra Redis, que recupera un aviso de Kafka perdido. El intervalo es también el
    `Retry-After` del `503` con la base vacía.
  - `SYNC_STARTUP_ENABLED` (`true`): al arrancar, armar o poner al día la copia local.
  - `SYNC_LOCK_WAIT_TIMEOUT` (`12m`), `SYNC_RETRY_DELAYS` (`1s,2s,4s`) y
    `SYNC_INCREMENTAL_TIME_BUDGET` (`60s`): si se cambian, hay que recalcular el peor caso.
- Solo para Docker Compose (opcionales):
  - `CATALOGO_APP_BIND` (`127.0.0.1`): interfaz del host donde se publica el `8081`. Para un teléfono
    físico, la IP de la red local; nunca `0.0.0.0`.
  - `DOCKER_GATEWAY_IP` (`172.17.0.1`): el `8081` se publica también en la IP del gateway de Docker,
    para que el contenedor de turnos llegue por `host.docker.internal` (a un puerto publicado solo en
    `127.0.0.1` un contenedor no llega).

La cátedra solo es accesible por ZeroTier: si falla la conexión, verificar primero
`sudo zerotier-cli listnetworks`.

## Uso

### Con Docker Compose (base y aplicación)

```bash
docker compose up -d --build --wait   # construye la imagen y levanta catalogo-db y catalogo-app (perfil prod)
docker compose logs -f catalogo-app   # los logs de la aplicación (Ctrl+C no la detiene)
docker compose stop                   # apaga en orden; el contenedor sale con 143 (SIGTERM)
```

- `--build` reconstruye la imagen ([`Dockerfile`](Dockerfile), en dos etapas: compila con el
  wrapper y el perfil `prod` y corre en un JRE 25). Sin `--build`, el Compose usa la imagen que ya
  está (`catalogo-service:local`). La primera vez tarda unos 2 minutos (baja las dependencias de
  Maven); después, unos 20 segundos.
- El contenedor usa el mismo `.env`. El [`docker-compose.yml`](docker-compose.yml) pisa la base
  (`catalogo-db:5432`) y el perfil (`prod`).
- `catalogo-app` queda `healthy` cuando responde `/management/health/readiness` (unos 20 s); la
  sincronización de arranque sigue en segundo plano. La cátedra solo se alcanza por ZeroTier, también
  desde el contenedor.
- Levantarlo antes que turnos y apagarlo después.
- `docker compose stop` espera hasta 90 s el cierre ordenado. **Nunca `docker compose down -v`**:
  borra el volumen `catalogo-service_catalogo-db-data`, con la copia del catálogo.

### Para desarrollar (base en Docker, aplicación con `./mvnw`)

```bash
docker compose stop catalogo-app    # si estaba corriendo: usa el mismo puerto 8081
docker compose up -d catalogo-db    # solo la base (PostgreSQL 18, puerto 5433 del host)
./mvnw                              # la app en el perfil dev, en http://localhost:8081
```

Al arrancar, Liquibase aplica las migraciones y la sincronización arma o pone al día la copia local
(snapshot por REST si la base está vacía; si no, lo que falte desde Redis). Para comprobar que está en
marcha:

```bash
curl http://localhost:8081/management/health
```

### Endpoints

| Endpoint | Token | Caso de uso |
| --- | --- | --- |
| `GET /api/categories` | Usuario (`ROLE_USER` o `ROLE_ADMIN`) | [CU-06](docs/use-cases/CU-06-listar-categorias.md) |
| `GET /api/professionals` | Usuario | [CU-05](docs/use-cases/CU-05-buscar-profesionales.md) |
| `GET /api/sync/status` | Usuario | [CU-09](docs/use-cases/CU-09-consultar-estado-de-sincronizacion.md) |
| `POST /api/admin/sync?mode=NORMAL\|SNAPSHOT` | `ROLE_ADMIN` | [CU-08](docs/use-cases/CU-08-disparar-sincronizacion-manual.md) |
| `GET /api/service/professionals/{id}/agenda` | Técnico de servicio de turnos ([ADR 0007](docs/adr/0007-autorizacion-token-de-servicio.md)) | [CU-07](docs/use-cases/CU-07-consultar-agenda-vigente.md) |

Los tokens de usuario los emite turnos-service. El de servicio también, con `ROLE_SERVICE`,
`aud=catalogo-service`, `iss=turnos-service` y 5 minutos de vida como máximo; los endpoints de usuario lo
rechazan.

## Tests

```bash
./mvnw clean verify            # todos: unitarios y de integración
./mvnw test -Dtest=NombreTest  # un test unitario
```

Los tests de integración levantan su propio PostgreSQL con Testcontainers, así que Docker tiene que
estar corriendo. No usan el `.env`: el perfil de test tiene una clave JWT fija y valores falsos
para la cátedra, que se simula con un servidor HTTP local.

Los de integración usan también un Kafka y un Redis (con la misma ACL que la cuenta técnica de la
cátedra) de Testcontainers. Las pruebas de la sincronización de punta a punta están en `SyncEndToEndIT`
(ver [docs/sincronizacion.md](docs/sincronizacion.md#12-las-pruebas)).

GitHub Actions corre `./mvnw clean verify` con Java 25 en cada PR hacia `develop` y `main`.

### Comprobación manual contra la cátedra real

No corre en el CI ni con `./mvnw verify`. Con ZeroTier conectado y `CATEDRA_API_URL` y
`CATEDRA_JWT` en el `.env`:

```bash
./mvnw test -Dtest=CatedraSnapshotManualCheck -Dcatedra.manual=true
```

Imprime solo `snapshotVersion` y la cantidad de categorías, profesionales y horarios. Si falla,
imprime una línea con el tipo de error y el status/code, sin el token.

## Notas

- El Compose del servicio es el `docker-compose.yml` de la raíz. Lo que queda en `src/main/docker/`
  (monitoreo, Sonar, Zipkin, JHipster Control Center) es lo que generó JHipster y no se usa.
- La clave JWT de `src/main/docker/jhipster-control-center.yml` (y de `.yo-rc.json` y del perfil de
  test) es la **clave de test, pública a propósito**: no sirve para validar tokens reales.
- Decisiones de arquitectura en [`docs/adr/`](docs/adr) y casos de uso en
  [`docs/use-cases/`](docs/use-cases/README.md).
- Estrategia de sincronización completa e incremental, idempotencia y recuperación ante mensajes
  duplicados, perdidos o fuera de orden: [`docs/sincronizacion.md`](docs/sincronizacion.md).
