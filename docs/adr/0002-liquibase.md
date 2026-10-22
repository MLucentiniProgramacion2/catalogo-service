# ADR 0002: Liquibase para las migraciones de base de datos

- **Estado:** aceptada
- **Fecha:** 2026-10-08
- **Relacionada con:** [ADR 0001: PostgreSQL](0001-postgresql.md)

## Contexto

El esquema de la base del servicio (ver ADR 0001) va a cambiar a lo largo del proyecto:
catálogo, versión de sincronización, usuarios, eventos procesados, etc. Esos cambios tienen que
ser reproducibles en cualquier entorno, quedar versionados en Git y aplicarse automáticamente.

Requisitos del enunciado (`PROJECT_STATEMENT-v1.md`):

- **§3:** cada servicio es propietario exclusivo de sus datos y **administra sus propias
  migraciones**, independientes de las del otro servicio. No se integra por base compartida.
- **§3.1:** el uso de JHipster es opcional y recomendado. **Todavía está pendiente de
  confirmación del profesor** si este proyecto lo va a usar.
- **§12:** el modelo de datos de cada servicio tiene que estar documentado y coincidir con la
  solución entregada.

Además, generar el esquema con Hibernate (`ddl-auto=update`) no deja historial ni sirve para la
base real. Solo se acepta en tests.

## Decisión

Usar **Liquibase** para versionar y aplicar las migraciones del servicio. Los *changelogs*
viven en este repositorio y se aplican al arrancar la aplicación. Hibernate solo **valida** el
esquema (`ddl-auto=validate`) y nunca lo modifica.

La elección es válida **con o sin JHipster**:

- **Si se usa JHipster:** es la herramienta que JHipster genera y configura, así que no hay que
  cambiar nada.
- **Si se usa Spring Boot puro:** Spring Boot la autoconfigura al agregar la dependencia, igual
  que con Flyway.

Así esta decisión no depende de la respuesta pendiente sobre JHipster.

**Pendiente:** el formato de los *changelogs* (XML como JHipster, o YAML/SQL) se decide al
escribir la primera migración.

## Alternativas consideradas

| Alternativa | Motivo para descartarla |
| --- | --- |
| **Flyway** | Más simple: migraciones en SQL plano numerado (`V1__...sql`). Pero no es lo que genera JHipster: si el profesor confirma JHipster, habría que migrar de herramienta o convivir con dos. Además, el *rollback* no está en la edición gratuita. |
| **`ddl-auto=update` de Hibernate** | No deja historial, no es reproducible y puede alterar o perder datos. Solo se admite en tests. |
| **Scripts SQL manuales** | Sin registro de qué se aplicó en cada base; propenso a errores y difícil de reproducir. |

## Consecuencias

- **Positivas:**
  - Cada cambio de esquema es un *changeset* identificado; Liquibase registra cuáles se
    aplicaron en las tablas `DATABASECHANGELOG` y `DATABASECHANGELOGLOCK`.
  - Los *changelogs* pueden ser independientes del motor y soportan *rollback*.
  - Combinado con PostgreSQL (DDL transaccional), una migración que falla no deja el esquema
    a medias.
  - Los *changelogs* son, a la vez, documentación versionada del modelo de datos (§12).
  - Sirve igual con JHipster o con Spring Boot puro.
- **Negativas:**
  - Agrega una capa de abstracción respecto de escribir SQL directo, y su formato hay que
    aprenderlo.
  - Un *changeset* ya aplicado no se edita: cualquier corrección va en uno nuevo (Liquibase
    valida *checksums* y falla si cambia uno ya aplicado).
