# CU-03: Incorporar cambios del catálogo

## Objetivo

Llevar la copia local hasta la última versión publicada por la cátedra, eligiendo entre aplicar
los incrementos en orden (CU-04) o reconstruir con un snapshot (CU-02), sin aplicar dos veces el
mismo cambio y sin depender de que lleguen todas las notificaciones.

## Actores

- **Principales** (uno por disparador):
  - Kafka de la cátedra (evento `CatalogUpdated`).
  - Arranque del servicio (a través de CU-01).
  - Temporizador (comprobación periódica de versión).
  - Administrador (a través de CU-08, modo normal).
- **Secundario:** Redis de la cátedra.

## Disparador

Cualquiera de estos cuatro, que comparten el mismo flujo:

1. Llega un `CatalogUpdated` al topic `catedra.catalog.{groupId}`.
2. El servicio arranca con una versión local ya guardada (CU-01).
3. Vence la comprobación periódica de versión, cada N minutos configurables por variable de
   entorno (DP-04).
4. Un administrador pide una sincronización manual en modo normal (CU-08).

## Precondiciones

- Las credenciales de Redis y el topic de Kafka están configurados por variables de entorno.

## Flujo principal

1. Ocurre el disparador. Si es Kafka, se recibe el evento con `eventId` y `newVersion`.
2. *(Solo Kafka)* Se valida el evento: tienen que estar `eventId` y `newVersion`. Los campos
   desconocidos se ignoran.
3. *(Solo Kafka)* **Primera barrera de idempotencia:** se busca el `eventId` en la tabla de
   eventos procesados.
4. Se toma la **exclusión mutua** de sincronización, esperando como máximo el tiempo configurado
   (DP-09). La comprobación periódica no espera: si hay otra sincronización en curso o esperando,
   saltea esa pasada sin registrar nada.
5. Se lee la versión local.
6. Si **no hay versión local**, se ejecuta directamente **CU-02** (paso 8) sin leer Redis: la regla
   del camino ya decide un snapshot, y la primera copia viene por REST, así que un Redis caído no
   puede impedirla. `remoteVersion` queda sin escribir hasta la próxima lectura de la metadata (el
   próximo aviso). Si hay versión local, se lee `catedra:sync:metadata` (`currentVersion`,
   `oldestAvailableVersion`) y se registra `currentVersion` como versión remota conocida, en una
   transacción corta. La versión objetivo es `currentVersion` de Redis: Kafka solo notifica, los
   datos y la versión salen de Redis.
7. **Segunda barrera de idempotencia:** se compara la versión local con la objetivo (y, si el
   disparador es Kafka, con `newVersion`).
8. Se decide el camino:
   - si **no hay versión local** (decidido en el paso 6, sin leer Redis), o la versión local es
     **menor que `oldestAvailableVersion`**, o falta alguna `catedra:sync:changes:{v}` entre la
     versión local + 1 y la objetivo, se ejecuta **CU-02 Ejecutar la sincronización completa**;
   - si no, se ejecuta **CU-04 Aplicar la sincronización incremental** desde la versión local + 1
     hasta la objetivo, en orden.
9. *(Solo Kafka)* El `eventId` queda registrado como procesado **dentro de la transacción de la
   última versión aplicada** (o de la del snapshot), para que se confirme o se deshaga junto con
   los datos.
10. Se registra el resultado en el estado de sincronización (CU-09) y se libera la exclusión
    mutua.
11. *(Solo Kafka)* Se commitea el offset, **después** de persistir.

## Flujos alternativos

- **2a. Evento ilegible o sin `eventId`/`newVersion`:** no se aplica nada, se registra el error y
  se commitea el offset para no trabar la partición. Si hubiera una versión nueva, la comprobación
  periódica la detecta por comparación de versiones.
- **3a. El `eventId` ya está registrado** (mensaje duplicado): no se aplican cambios y se commitea
  el offset. El estado final no cambia.
- **4a. Se supera la espera máxima de la exclusión mutua:** el disparador termina **sin aplicar
  cambios** y lo deja registrado en el estado de sincronización. No le pasa a la comprobación
  periódica, que no espera. Si es Kafka, el `eventId`
  **no** se registra y se commitea el offset. La comprobación periódica lo cubre después.
- **6a. Redis no disponible o timeout:** reintentos acotados (DP-08). Si se agotan, no se aplica
  nada, se registra `REDIS_UNAVAILABLE` y, si es Kafka, el `eventId` **no** se registra y se
  commitea el offset. La comprobación periódica recupera el atraso cuando Redis vuelva. Si Redis
  rechaza la credencial (`WRONGPASS` o `NOPERM`), no se reintenta ni se cae en un snapshot: es un
  `INVALID_CREDENTIALS` cuya descripción dice que fue Redis.
- **7a. La copia ya está al día** (versión local mayor o igual a la objetivo, o `newVersion` menor
  o igual a la versión local, por ejemplo porque la comprobación periódica ya la aplicó): no se
  aplican cambios. Si es Kafka, se registra el `eventId` si faltaba (en una transacción corta
  propia) y se commitea el offset.
- **8a. CU-04 o CU-02 terminan con fallo:** se conservan las versiones que ya se habían aplicado
  completas, porque cada versión es su propia transacción. La versión en curso hace rollback. El
  `eventId` **no** se registra (cae con el rollback), el error queda en el estado de sincronización
  y, si es Kafka, se commitea el offset.
- **8b. CU-04 no puede seguir a mitad de camino** (una entidad no está en su Hash o no se puede
  verificar, un `changes:{v}` dice otra versión, o vence el plazo del lote): se conservan las versiones
  ya aplicadas y se continúa con CU-02, con el mismo aviso. Que falte un `changes:{v}` ya no pasa a
  mitad de camino: se decide en el paso 8, con todos los del lote leídos de una vez.
- **Falla inesperada fuera de CU-02 y CU-04** (por ejemplo, la base caída en una barrera): se
  reintenta el evento entero con las esperas de DP-08. Si se agotan, se registra el error, el `eventId`
  **no** se registra y se commitea el offset. Si tampoco se puede registrar el error, queda en el log y
  el offset se commitea igual.

## Postcondiciones

- **De éxito:** la versión local es igual a la `currentVersion` leída de Redis y la copia local
  refleja esa versión. Si el disparador fue Kafka, el `eventId` quedó registrado y el offset,
  commiteado.
- **De fallo:** la copia local queda consistente en la última versión aplicada completa (nunca a
  medio aplicar). El error queda registrado en el estado de sincronización. Ningún disparador se
  reintenta para siempre: la recuperación queda a cargo de la próxima comprobación periódica o del
  próximo evento.

## Decisiones del proyecto

- **DP-04 (comprobación periódica de versión):** además de `CatalogUpdated` y del arranque, cada N
  minutos (configurable por variable de entorno) se compara la versión local con la de Redis. No es
  un caso de uso aparte, sino un disparador más de este caso: reutiliza la misma lógica y la misma
  idempotencia. Como no trae `eventId`, su barrera de idempotencia es la comparación de versiones.
  Cubre la pérdida de una notificación cuando la cátedra no publica otra versión por un tiempo, sin
  esperar al próximo evento ni a un reinicio. *Respaldo: enunciado §8 ("pérdida de notificaciones
  de catálogo") y contrato §16 ("si una notificación se pierde, la comparación de versiones debe
  permitir detectar y recuperar el atraso").*
- **DP-07 (deduplicación con doble barrera):** los `eventId` procesados se guardan en una tabla de
  PostgreSQL, en la misma transacción que aplica los cambios. Segunda barrera: si `newVersion` es
  menor o igual a la versión local, tampoco se aplican cambios. En los dos casos se registra el
  `eventId` si faltaba y se commitea el offset. La purga de `eventId` viejos queda documentada
  como mejora futura; no se implementa ahora.
- **DP-08 (fallas y reintentos):** timeouts configurables, hasta 3 reintentos con backoff
  exponencial (1 s, 2 s, 4 s). Si se agotan: rollback de la versión en curso (las versiones ya
  aplicadas completas se conservan), el `eventId` no se registra, el error queda en el estado de
  sincronización y se commitea el offset. La recuperación queda a cargo de la comprobación
  periódica. Se commitea el offset sin haber aplicado los cambios porque el evento no tiene datos:
  solo avisa. Lo que hay que aplicar se deduce siempre de comparar versiones contra Redis, así que
  no se pierde información y la partición de Kafka no queda trabada reintentando sin límite. Lo
  inesperado también se reintenta de forma acotada, con las mismas esperas (ver "Recepción por
  Kafka").
- **DP-09 (exclusión mutua):** una sola incorporación de cambios a la vez. Un segundo disparador
  espera; al entrar, vuelve a comparar versiones y normalmente ya no tiene nada que hacer. La
  espera tiene un tiempo máximo configurable: si se supera, termina sin aplicar cambios, lo
  registra en el estado de sincronización y la comprobación periódica lo cubre después. La
  comprobación periódica es la excepción: no espera, y si la exclusión está tomada o alguien la
  espera, saltea esa pasada sin registrar nada (issue #35).

## Recepción por Kafka (issue #31)

El disparador Kafka está implementado desde el #31. Los detalles y las mediciones del consumidor están en el
[ADR 0005](../adr/0005-consumidor-kafka.md).

### Configuración

Las tres variables son obligatorias: sin alguna, la aplicación no arranca.

| Variable | Valor |
| --- | --- |
| `CATEDRA_KAFKA_BOOTSTRAP_SERVERS` | `kafkaBootstrapServers` de `integration` (contrato §5.1) |
| `CATEDRA_KAFKA_CATALOG_TOPIC` | `kafkaCatalogTopic` de `integration` |
| `CATEDRA_KAFKA_CONSUMER_GROUP` | `kafkaConsumerGroupId` de `integration` + `-catalogo` |

**Un consumer group por servicio:** este usa el sufijo `-catalogo` y turnos usará `-turnos`. Si
compartieran el grupo, un rebalanceo con el catálogo bloqueado (esperando la exclusión o corriendo un
snapshot) dejaría a turnos sin recibir mensajes hasta que el catálogo termine. El contrato lo llama
"sugerido" (§15.2) y la cátedra acepta otros nombres. El topic no se declara: es de la cátedra.

### Commit del offset

Lo hace el contenedor, mensaje por mensaje (`AckMode.RECORD`), cuando el listener vuelve bien: siempre
después de persistir. Todos los flujos esperados vuelven bien, también los que fallaron, porque el
aviso no trae datos. El único caso sin commit es que el proceso muera: el evento se vuelve a entregar
y la deduplicación lo cubre.

## Con Redis (issue #33)

Los pasos 6 a 10 son los mismos para todos los disparadores: los implementa
`IncorporateChangesUseCase`, que usan el aviso de Kafka, CU-08 en modo normal y, desde el #35, el
arranque (CU-01) y la comprobación periódica. Cada disparador conserva lo suyo: las barreras del aviso, la exclusión y
la lectura de la versión adentro de ella. El cliente de Redis está en el
[ADR 0006](../adr/0006-cliente-redis.md).

- **Sin versión local, CU-02 directo, sin leer Redis** (paso 6): la primera copia viene por REST y un
  Redis caído no la puede impedir. `remoteVersion` se completa con el próximo aviso.
- **La versión objetivo es `currentVersion` de Redis.** `newVersion` es solo un aviso.
- **`remoteVersion`** se escribe en una transacción corta, justo después de leer la metadata, con
  `load` y `save` en la misma transacción. Si la copia está al día y lo disparó un aviso, esa misma
  transacción registra el `eventId` (7a).
- **El camino** (contrato §14.2 y §18.2, literal): snapshot si la local es **menor** que
  `oldestAvailableVersion`, o si falta algún `changes:{v}` entre la local + 1 y la objetivo; si no,
  incremental. Con la local en 3 y `oldest` en 4 es snapshot aunque exista `changes:4`. Los
  `changes:{v}` del lote se leen con un solo `MGET`: sirve para ver si falta alguno y ya trae su
  contenido (no cambia), así que la discontinuidad por falta de un `changes:{v}` se decide acá, antes
  de aplicar nada (antes era el flujo 1a de CU-04).
- **Aviso adelantado a Redis** (`newVersion` mayor que `currentVersion`): se llega hasta la publicada,
  con un WARN, y el `eventId` se registra igual, como el snapshot anterior a `newVersion` del #31. La
  versión que falta llega con el próximo aviso o con la comprobación periódica.
- **Metadata inválida** (falta un campo o no es un número): el estado no se puede verificar, va
  snapshot y `remoteVersion` no se escribe. `oldest` mayor que `current` no se rechaza: el contrato no
  lo prohíbe y la regla del camino da un resultado coherente igual.
- **Reintentos (DP-08):** la lectura previa (metadata, `remoteVersion` y cambios) comparte un
  presupuesto de 3 reintentos, y un reintento repite **solo el paso que falló**: si falla escribir
  `remoteVersion`, la metadata no se vuelve a leer. CU-04 y CU-02 tienen el suyo cada uno.
  `attempts` suma los tres.

### Cómo queda registrado cada flujo

| Flujo | Copia local | `processed_event` | `sync_status` | Log | Offset (si es Kafka) |
| --- | --- | --- | --- | --- | --- |
| Éxito por incremental | Las versiones aplicadas, una transacción cada una | Registrado, con la última versión | Versión, `INCREMENTAL`, disparador; `remoteVersion` | INFO | Confirmado |
| Éxito por snapshot | Reemplazada | Registrado, en la transacción del snapshot | Versión, `SNAPSHOT`, disparador; `remoteVersion` si se leyó | INFO | Confirmado |
| 2a. Inválido | Sin cambios | No | `INVALID_EVENT`, texto fijo | WARN con partición y offset, sin el contenido | Confirmado |
| 3a. Duplicado | Sin cambios | Ya estaba | Sin cambios | INFO | Confirmado |
| 4a. Espera vencida | Sin cambios | No | `LOCK_TIMEOUT` | WARN | Confirmado |
| 6a. Redis no disponible | Sin cambios | No | `REDIS_UNAVAILABLE`, sin `remoteVersion` | WARN por intento y ERROR | Confirmado |
| 6a. Redis rechaza la credencial | Sin cambios | No | `INVALID_CREDENTIALS`, la descripción dice "Redis" | ERROR | Confirmado |
| 7a. Al día por la segunda barrera (`newVersion` menor o igual a la local) | Sin cambios | Registrado, en una transacción corta | Sin cambios: no se lee Redis | INFO | Confirmado |
| 7a. Al día por la metadata | Sin cambios | Registrado, junto con `remoteVersion` | `remoteVersion` | INFO | Confirmado |
| 8a. CU-04 falla | Las versiones aplicadas antes se conservan | No | El error de CU-04 (`REDIS_UNAVAILABLE`, `DATABASE_UNAVAILABLE`, `INCREMENTAL_FAILED` o `INVALID_CREDENTIALS`) | WARN por intento y ERROR | Confirmado |
| 8a. CU-02 falla | Sin cambios (o las versiones de CU-04, si venía de 8b) | No | El error de CU-02 | ERROR y WARN | Confirmado |
| 8b. Discontinuidad a mitad de camino o plazo vencido | Las versiones aplicadas, y después reemplazada por el snapshot | Registrado, en la transacción del snapshot | `SNAPSHOT` | WARN con el motivo | Confirmado |
| Falla inesperada, se agotan los reintentos | Sin cambios | No | `EVENT_PROCESSING_FAILED`, texto fijo | WARN por intento y ERROR | Confirmado |

Las descripciones de `sync_status` se informan a los usuarios (CU-09): son textos fijos, sin nombres de
clases, hosts, usuarios, tokens ni el contenido del mensaje.

## Comprobación periódica (issue #35)

El disparador 3 está implementado desde el #35. Es CU-03 con `PERIODIC`, sin las barreras del aviso:
no trae `eventId`, así que su barrera de idempotencia es la comparación de versiones.

### Configuración

| Variable | Por defecto | Regla |
| --- | --- | --- |
| `SYNC_PERIODIC_ENABLED` | `true` | Con `false` la tarea ni se registra (así está en `src/test`). |
| `SYNC_PERIODIC_INTERVAL` | `5m` | Mínimo `1m`: con menos, la aplicación no arranca. `5` sin unidad son 5 ms. |

- **Demora fija (`fixedDelay`):** el intervalo se cuenta desde que termina una pasada, y la primera es
  un intervalo después de arrancar. Después de un snapshot largo no se encadenan pasadas.
- **No espera la exclusión.** Si hay otra sincronización en curso o esperando, esa ya compara
  versiones: la pasada se saltea sin registrar nada (ni `LOCK_TIMEOUT` ni "interrumpido"). Si esperara,
  en la cola justa sumaría su peor caso a la espera de un aviso que llegue detrás. Se toma con
  `tryLock(0, …)`, que respeta la cola, y no con `tryLock()`, que se mete delante.
- **Sin versión local hace el snapshot:** recupera un arranque que no pudo armar la primera copia
  (CU-01, flujo 2a). Por eso es el `Retry-After` del `503` con la base vacía (DP-03).
- **Log:** la pasada "al día" va en DEBUG; aplicar o fallar, en INFO, WARN o ERROR como con los demás
  disparadores.
- **Apagado:** Spring espera a la pasada en curso hasta `spring.lifecycle.timeout-per-shutdown-phase`
  (30 s) y después la interrumpe. Una pasada al día termina; una larga hace rollback de la versión en
  curso, porque cada versión es su propia transacción.

| Situación | Qué hace la pasada | `sync_status` |
| --- | --- | --- |
| Aviso perdido (Redis va adelante) | Aplica por incremental, o por snapshot si hay discontinuidad | Versión, `PERIODIC`; `remoteVersion` |
| Al día | Nada | `remoteVersion` y su fecha (CU-09 informa el error anterior como `resolved`) |
| Base vacía (el arranque falló) | Snapshot por REST, sin leer Redis | `SNAPSHOT`, `PERIODIC` |
| Redis sigue caído | Nada; reintenta en la próxima pasada | `REDIS_UNAVAILABLE` |
| Otra sincronización en curso o esperando | Se saltea | Sin cambios |

El peor caso con la exclusión tomada (618 s) no cambia: la periódica corre lo mismo que los demás
disparadores con versión local, y como no espera nunca entra en la cola.

## Respaldo en las consignas

- Enunciado §4.1: "ejecutar la sincronización completa e incremental" y "detectar
  discontinuidades".
- Enunciado §6.2: Kafka notifica, se consultan en Redis la metadata y los cambios; el
  procesamiento repetido no altera el resultado; ante discontinuidad, versión fuera de la ventana o
  estado no verificable, sincronización completa.
- Enunciado §8: mensajes Kafka duplicados, pérdida de notificaciones, interrupción de la conexión
  con la cátedra, sin ciclos de reintento ilimitados.
- Contrato §14.2 y §14.3: versiones y metadata; "si la versión local es menor que
  `oldest-available-version` [...] se debe obtener un snapshot REST".
- Contrato §15.3: `CatalogUpdated` (topic, `eventId`, `newVersion`); "el evento notifica; los
  datos se obtienen desde Redis o mediante snapshot".
- Contrato §16: deduplicar `eventId`, aplicar en orden, commit del offset después de persistir.
- Contrato §18.1 y §18.2: mensaje duplicado y versión incremental perdida.
- Contrato §18.4: reintentos limitados y observables.
