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

- Java 25 (Temurin), por ejemplo con SDKMAN: `sdk install java 25.0.4-tem`
- Docker con Docker Compose (para la base y para los tests con Testcontainers)

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

## Uso

```bash
docker compose up -d   # levanta la base catalogo-db (PostgreSQL 18, puerto 5433 del host)
./mvnw                 # corre la app en el perfil dev, en http://localhost:8081
```

Al arrancar, Liquibase aplica las migraciones. Para comprobar que está en marcha:

```bash
curl http://localhost:8081/management/health
```

## Tests

```bash
./mvnw clean verify            # todos: unitarios y de integración
./mvnw test -Dtest=NombreTest  # un test unitario
```

Los tests de integración levantan su propio PostgreSQL con Testcontainers, así que Docker tiene que
estar corriendo. No usan el `.env`: el perfil de test tiene una clave JWT fija.

GitHub Actions corre `./mvnw clean verify` con Java 25 en cada PR hacia `develop` y `main`.

## Notas

- `src/main/docker/` es lo que genera JHipster y todavía no se usa: la única base del servicio es la
  del `docker-compose.yml` de la raíz.
- La clave JWT de `src/main/docker/jhipster-control-center.yml` (y de `.yo-rc.json` y del perfil de
  test) es la **clave de test, pública a propósito**: no sirve para validar tokens reales.
- Decisiones de arquitectura en [`docs/adr/`](docs/adr) y casos de uso en
  [`docs/use-cases/`](docs/use-cases/README.md).
