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

**Formato de los *changelogs* (decidido el 25/10, issue #13):** XML, igual que los que genera
JHipster.

- **Uno por tabla**, en carpetas por feature (`changelog/catalog/`, `changelog/sync/`), separados
  de los de JHipster, que quedan en `changelog/` sin tocar.
- Se incluyen **uno por uno** en `master.xml`, no con `includeAll`, porque el orden importa: las
  claves foráneas exigen crear categorías, profesionales y horarios en ese orden.
- Identificador del *changeset*: `<feature>-<número>-<tabla>` (por ejemplo,
  `catalog-002-professional`), con autor `maximoLucentini`.
- Lo que el XML de la edición gratuita no cubre (los `CHECK`: `addCheckConstraint` es de
  Liquibase Pro) va con `<sql>` y con su `<rollback>` explícito.

Se descartaron YAML, porque mezclaría dos formatos en el mismo `master.xml`, y SQL plano, porque
pierde el *rollback* automático y la independencia del motor que da el XML.

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

## Nota (25/10)

Revisando la decisión "Hibernate solo valida" (issue #15) aparecieron dos huecos que dejaba la
configuración generada por JHipster:

1. **Los tests no validaban el esquema.** `application-testdev.yml` y `application-testprod.yml`
   tenían `ddl-auto: none` y `hibernate.hbm2ddl.auto: none` (con un TODO de JHipster). El
   `validate` del `application.yml` principal no llega a los tests, porque el
   `src/test/resources/config/application.yml` lo tapa en el classpath. Un test podía pasar con una
   entidad que no coincidía con su tabla.
2. **En el perfil `dev`, Liquibase corría en paralelo con el arranque.** Con
   `application.liquibase.async-start: true` (valor por defecto de JHipster), `AsyncSpringLiquibase`
   ejecuta la migración en otro hilo si está activo `dev`. El `EntityManagerFactory` depende del bean
   de Liquibase, pero solo espera a que se inicialice, y en modo asincrónico eso vuelve enseguida.
   Con una migración lenta, Hibernate validó antes de que existiera la tabla y el arranque falló
   (`Schema validation: missing table`). En `prod` y en los tests ya era sincrónico.

**Se decidió:**

- `ddl-auto: validate` también en los perfiles de test, y se borra `hibernate.hbm2ddl.auto`, que en
  Spring Boot tiene prioridad sobre `ddl-auto`.
- `application.liquibase.async-start: false` en el `application.yml` principal y en el de test:
  Liquibase termina antes de que Hibernate valide, en todos los perfiles.

**Por qué:** la validación solo sirve si corre en todos lados y después de las migraciones. Si no,
una diferencia entre entidad y tabla aparece recién en ejecución. El costo es que el perfil `dev`
arranca un poco más lento, porque espera a Liquibase.

**Comprobación:** con una columna inexistente agregada a una entidad de test, los tests de
integración fallan por la validación. El perfil `dev` arranca contra una base vacía, incluso con un
*changeset* artificialmente lento que antes hacía fallar el arranque.
