# ADR 0001: PostgreSQL como base de datos del servicio de catálogo

- **Estado:** aceptada
- **Fecha:** 2026-10-08

## Contexto

El servicio de catálogo y sincronización guarda una copia local de categorías, profesionales y
horarios semanales (incluidas las bajas lógicas) y la versión de catálogo aplicada. Sobre esa
copia resuelve las búsquedas (enunciado §4.1).

Requisitos del enunciado (`PROJECT_STATEMENT-v1.md`) que condicionan la elección:

- **§3:** la persistencia principal debe usar un gestor de base de datos **servidor**. **No se
  aceptan H2, SQLite ni otras bases embebidas o en memoria.** El motor lo elige el alumno.
- **§3:** cada servicio es propietario exclusivo de sus datos y administra **sus propias
  migraciones**. Se puede compartir una instancia física solo con bases lógicas o esquemas
  separados y usuarios sin permisos cruzados.
- **§3:** la infraestructura local se levanta con Docker Compose.
- **§6.1 y contrato §7:** el snapshot (tres colecciones) y la `snapshotVersion` se aplican de
  forma consistente: si algo falla, la versión local no avanza.
- **§4.1:** búsqueda local con filtros por categoría, nombre, estado habilitado y disponibilidad.
- **§12:** hay que documentar el modelo de datos y las decisiones técnicas.

El repositorio de referencia de la cátedra (arquitectura hexagonal) usa H2, que no se puede
usar como persistencia principal. Por eso hace falta elegir otro motor.

## Decisión

Usar **PostgreSQL** (imagen `postgres:18.1-alpine`, versión fija) como base propia del servicio,
levantada con Docker Compose: base `catalogo` y usuario `catalogo_app`, con credenciales por
variables de entorno (`.env`, no versionado).

## Alternativas consideradas

| Alternativa | Motivo para descartarla |
| --- | --- |
| **MySQL / MariaDB** | Cumple §3, pero las sentencias DDL hacen commit implícito: una migración que falla puede dejar el esquema a medias. La comparación de nombres sin mayúsculas ni acentos depende del *collation* de la columna. |
| **MongoDB** | Se aparta de JPA/Hibernate, que es la herramienta de persistencia de la materia. Para aplicar el snapshot de forma atómica necesita transacciones multi-documento, que requieren un *replica set*. |
| **H2 / SQLite** | Prohibidas por §3 como persistencia principal. |

## Consecuencias

- **Positivas:**
  - Las transacciones cubren también el DDL: una migración que falla se revierte completa.
  - Tipos que calzan con el contrato: `time` para horas `HH:mm:ss`, `date` para `yyyy-MM-dd` y
    `timestamptz` para instantes ISO-8601 UTC.
  - Búsqueda por nombre con `ILIKE`, y opcionalmente la extensión `unaccent` para ignorar acentos.
  - Si el servicio de turnos compartiera la instancia física, se separa con `CREATE DATABASE` y un
    rol por servicio sin permisos cruzados, como pide §3.
  - Buen soporte en Testcontainers para tests de integración, en Spring Boot y en JHipster.
- **Negativas:**
  - Hace falta Docker para desarrollar y correr los tests de integración.
  - Las consultas que usen funciones propias de PostgreSQL (`ILIKE`, `unaccent`) no son portables
    a otro motor sin ajustes.
- **Seguridad:** el puerto se expone solo en `127.0.0.1` y es configurable (por defecto `5433`),
  para no chocar con la base del servicio de turnos.
