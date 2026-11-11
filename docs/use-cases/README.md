# Casos de uso del servicio de catálogo y sincronización

Casos de uso del servicio de catálogo, escritos antes de implementar. Cada caso indica la sección
del enunciado (`PROJECT_STATEMENT-v1.md`) o del contrato (`INTEGRATION_REFERENCE-v2.md`) que lo
respalda, y las decisiones de diseño que el enunciado deja a cargo del alumno quedan registradas
como **Decisión del proyecto (DP-xx)** en el caso que corresponde.

## Supuesto general

La copia local del catálogo sirve para **mostrar, filtrar y armar la agenda**; la cátedra tiene
la **última palabra al reservar**. La copia puede ir unos segundos atrás de la cátedra (entre que
se publica una versión y se aplica), pero eso no habilita reservas inválidas: al crear el hold, la
cátedra valida profesional, slot y ocupación, y responde `PROFESSIONAL_DISABLED`, `INVALID_SLOT`,
`SLOT_ALREADY_RESERVED` o `SLOT_ALREADY_HELD` si el dato local estaba desactualizado (contrato §9 y
§13.1).

## Actores

| Actor | Tipo | Participa en |
| --- | --- | --- |
| Usuario final (app KMP) | Principal | CU-05, CU-06, CU-09 |
| Administrador (usuario con `ROLE_ADMIN`) | Principal | CU-08, CU-09 |
| Servicio de turnos | Principal | CU-07 |
| Kafka de la cátedra (`CatalogUpdated`) | Principal (disparador) | CU-03 |
| Arranque del servicio | Principal (disparador temporal) | CU-01 |
| Temporizador (comprobación periódica) | Principal (disparador temporal) | CU-03 |
| Redis de la cátedra (`catedra:sync:*`) | Secundario | CU-03, CU-04 |
| API REST de la cátedra (snapshot) | Secundario | CU-02 |

Validar el JWT no es un caso de uso: es una precondición transversal de los casos expuestos por
REST. Este servicio **no** registra ni autentica usuarios; eso vive en el servicio de turnos, que
emite el JWT que el catálogo valida. Cómo se valida y cómo se comparte la clave de firma con turnos
está en el [ADR 0003](../adr/0003-validacion-jwt.md).

## Índice

| CU | Nombre | Actor principal | Objetivo |
| --- | --- | --- | --- |
| [CU-01](CU-01-inicializar-catalogo-al-arrancar.md) | Inicializar el catálogo al arrancar | Arranque del servicio | Dejar la copia utilizable y al día al iniciar |
| [CU-02](CU-02-ejecutar-sincronizacion-completa.md) | Ejecutar la sincronización completa | (incluido) | Reemplazar la copia y guardar `snapshotVersion` en una transacción |
| [CU-03](CU-03-incorporar-cambios-del-catalogo.md) | Incorporar cambios del catálogo | Kafka / Arranque / Temporizador / Administrador | Llevar la copia a la última versión, con deduplicación |
| [CU-04](CU-04-aplicar-sincronizacion-incremental.md) | Aplicar la sincronización incremental | (incluido) | Aplicar versiones en orden, una transacción por versión |
| [CU-05](CU-05-buscar-profesionales.md) | Buscar profesionales con filtros | Usuario final | Filtrar por categoría, nombre, habilitado y disponibilidad sobre la copia local |
| [CU-06](CU-06-listar-categorias.md) | Listar categorías | Usuario final | Ofrecer las categorías para el filtro |
| [CU-07](CU-07-consultar-agenda-vigente.md) | Consultar la agenda vigente de un profesional | Servicio de turnos | Dar las reglas semanales vigentes para armar y validar turnos |
| [CU-08](CU-08-disparar-sincronizacion-manual.md) | Disparar la sincronización manualmente | Administrador | Forzar una sincronización normal o un snapshot |
| [CU-09](CU-09-consultar-estado-de-sincronizacion.md) | Consultar el estado de sincronización | Usuario final / Administrador | Ver versión, última sincronización y último error |

## Diagrama de casos de uso

Mermaid no tiene un diagrama de casos de uso nativo; se representa con un `flowchart`: los
rectángulos son actores, las cápsulas son casos de uso, las líneas continuas son participación y
las punteadas son relaciones `include`.

```mermaid
flowchart LR
    usuario["Usuario final<br/>(app KMP)"]
    admin["Administrador<br/>(ROLE_ADMIN)"]
    turnos["Servicio de turnos"]
    kafka["Kafka cátedra<br/>(CatalogUpdated)"]
    arranque["Arranque del servicio"]
    timer["Temporizador<br/>(comprobación periódica)"]

    subgraph catalogo["Servicio de catálogo y sincronización"]
        CU01(["CU-01 Inicializar el catálogo al arrancar"])
        CU02(["CU-02 Ejecutar la sincronización completa"])
        CU03(["CU-03 Incorporar cambios del catálogo"])
        CU04(["CU-04 Aplicar la sincronización incremental"])
        CU05(["CU-05 Buscar profesionales con filtros"])
        CU06(["CU-06 Listar categorías"])
        CU07(["CU-07 Consultar la agenda vigente"])
        CU08(["CU-08 Disparar la sincronización manual"])
        CU09(["CU-09 Consultar el estado de sincronización"])
    end

    redis["Redis cátedra<br/>(catedra:sync:*)"]
    api["API REST cátedra<br/>(snapshot)"]

    usuario --- CU05
    usuario --- CU06
    usuario --- CU09
    admin --- CU08
    admin --- CU09
    turnos --- CU07
    kafka --- CU03
    timer --- CU03
    arranque --- CU01

    CU01 -. include .-> CU02
    CU01 -. include .-> CU03
    CU03 -. include .-> CU04
    CU03 -. include .-> CU02
    CU08 -. include .-> CU03
    CU08 -. include .-> CU02

    CU03 --- redis
    CU04 --- redis
    CU02 --- api
```

## Decisiones del proyecto

| DP | Decisión | Casos |
| --- | --- | --- |
| DP-01 | "Disponibilidad" en la búsqueda significa que el profesional atiende en un día o franja según sus horarios semanales habilitados; no considera ocupaciones. Si no se indica el filtro "habilitado", se devuelven solo los habilitados; con `habilitado=false`, los deshabilitados. | CU-05 |
| DP-02 | La app KMP consulta al catálogo directamente con el JWT de usuario emitido por turnos. | CU-05, CU-06, CU-09 |
| DP-03 | Durante una reconstrucción se sirve la última copia confirmada y se informa la versión local; con la base vacía, `503` con `Retry-After` igual al intervalo de la comprobación periódica (sin el header si está apagada). | CU-01, CU-02, CU-05, CU-06, CU-07 |
| DP-04 | Comprobación periódica de versión contra Redis como disparador adicional de CU-03 (`PERIODIC`), con la misma lógica e idempotencia: `sync.periodic.interval` (`SYNC_PERIODIC_INTERVAL`, 5 min por defecto, mínimo 1 min), con demora fija desde que termina la pasada anterior; se apaga con `SYNC_PERIODIC_ENABLED=false`. El arranque con versión local también corre CU-03 (`STARTUP`). Implementada en el #35. | CU-01, CU-03 |
| DP-05 | Estado de sincronización: `ROLE_USER` o `ROLE_ADMIN`. Sincronización manual: solo `ROLE_ADMIN`. | CU-08, CU-09 |
| DP-06 | Turnos usa un JWT técnico de servicio con `aud=catalogo-service`, `iss=turnos-service` y `ROLE_SERVICE` (DPT-16 de turnos); el endpoint de agenda exige los tres como regla de autorización, no de la validación general del token, y si falta uno responde `403`; también exige vida corta (`iat` y `exp`, `exp - iat` de hasta 5 min): en turnos la vida es configurable pero no puede superar los 5 min, y si los supera el catálogo responde `403`; `X-User-Login` solo para trazabilidad; los endpoints de usuario rechazan el token técnico, que se reconoce por `ROLE_SERVICE` aunque traiga otro rol. Todo token sin `exp` da `401`. Ver el [ADR 0007](../adr/0007-autorizacion-token-de-servicio.md). | CU-05 a CU-09 |
| DP-07 | `eventId` procesados en una tabla de PostgreSQL (`processed_event`: `eventId`, `newVersion` del evento y fecha de procesamiento), en la misma transacción que los cambios; la comparación de versiones es una segunda barrera. El registro es "insertar si no existe": un `eventId` repetido no deshace la transacción. | CU-03, CU-04 |
| DP-08 | Timeouts configurables, hasta 3 reintentos con backoff de 1 s, 2 s y 4 s; si se agotan, rollback de la versión en curso, se conservan las versiones completas, el `eventId` no se registra, se registra el error y se commitea el offset. Lo inesperado en el listener de Kafka se reintenta con las mismas esperas y termina igual, aunque no se pueda registrar el error ([ADR 0005](../adr/0005-consumidor-kafka.md)). Con Redis (#33), el presupuesto de 3 reintentos es por tramo y no por paso: la lectura previa de CU-03 (metadata, versión remota y cambios), el lote entero de CU-04 y CU-02 tienen uno cada uno, y un reintento repite solo el paso que falló. Redis: 5 s de conexión y 3 s por comando ([ADR 0006](../adr/0006-cliente-redis.md)). | CU-02, CU-03, CU-04 |
| DP-09 | Una sola incorporación de cambios a la vez; los demás disparadores esperan hasta un máximo configurable (`sync.lock.wait-timeout`, 12 min por defecto) y, si se supera, terminan sin cambios y lo registran como `LOCK_TIMEOUT`. La comprobación periódica no espera: si la exclusión está tomada o alguien la espera, saltea la pasada sin registrar nada (#35). Los 12 min cubren el peor caso de CU-03 con Redis, 618 s (tabla abajo), que tiene cota gracias al plazo del lote de CU-04 (`sync.incremental.time-budget`, 60 s). Por eso CU-08, que es sincrónico, puede tardar hasta unos 22 min. | CU-01, CU-03, CU-04, CU-08 |
| DP-10 | La sincronización manual corre la lógica de CU-03 por defecto y puede forzar un snapshot con un parámetro explícito. | CU-08 |

### Peor caso de CU-03 con la exclusión tomada (DP-09)

| Tramo | s |
| --- | --- |
| Leer la versión local (Hikari) | 30 |
| Metadata (14) + `remoteVersion` (30) + cambios (14) | 58 |
| 3 reintentos de la lectura previa (hasta 30 cada uno) + esperas | 97 |
| CU-04 hasta el plazo del lote | 60 |
| La versión en curso al vencer el plazo (espera de 4 + 3 × 14 + 30) | 76 |
| CU-02 después (ya incluye registrar su error) | 297 |
| **Total** | **618** |

Un intento contra Redis son 14 s (5 de conexión y 3 por cada comando del saludo y del pedido). Con 12
min de espera sobran 102 s. La cuenta es de quien tiene la exclusión, y es la misma con cualquier
disparador: el arranque y la comprobación periódica (#35) corren lo mismo que CU-08 en modo normal (sin
versión local, 30 + 297 = 327 s). Los 12 min cubren **un** dueño adelante: por eso la periódica no entra
en la cola. `SyncWorstCase` arma esta cuenta en los tests con los valores reales de
la configuración: si cambia un timeout, el test lo nota. El `max.poll.interval.ms` de Kafka (25 min)
sale de la misma cuenta ([ADR 0005](../adr/0005-consumidor-kafka.md)).

## Mejoras futuras

- Purga de `eventId` procesados antiguos (DP-07).

## Decisiones todavía pendientes que afectan a estos casos

Ninguna. Ya no están pendientes:

- El contrato con el servicio de turnos para CU-07: ruta, DTO y `code` en
  [CU-07](CU-07-consultar-agenda-vigente.md#contrato-del-endpoint-issue-35), y la autorización en el
  [ADR 0007](../adr/0007-autorizacion-token-de-servicio.md) (issue #35). El documento de contratos entre
  los dos servicios (enunciado §12) va a resumirlo.
- La clave de firma del JWT compartida con turnos: ver el [ADR 0003](../adr/0003-validacion-jwt.md).
- El formato de los changelogs de Liquibase: XML, uno por tabla, en carpetas por feature (ver el
  [ADR 0002](../adr/0002-liquibase.md)).
