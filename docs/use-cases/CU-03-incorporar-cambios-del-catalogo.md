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
   (DP-09).
5. Se lee la versión local.
6. Se lee `catedra:sync:metadata` (`currentVersion`, `oldestAvailableVersion`). La versión
   objetivo es `currentVersion` de Redis: Kafka solo notifica, los datos y la versión salen de
   Redis.
7. **Segunda barrera de idempotencia:** se compara la versión local con la objetivo (y, si el
   disparador es Kafka, con `newVersion`).
8. Se decide el camino:
   - si **no hay versión local**, o la versión local es **menor que `oldestAvailableVersion`**, o
     falta alguna `catedra:sync:changes:{v}` entre la versión local + 1 y la objetivo, se ejecuta
     **CU-02 Ejecutar la sincronización completa**;
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
  cambios** y lo deja registrado en el estado de sincronización. Si es Kafka, el `eventId`
  **no** se registra y se commitea el offset. La comprobación periódica lo cubre después.
- **6a. Redis no disponible o timeout:** reintentos acotados (DP-08). Si se agotan, no se aplica
  nada, se registra el error y, si es Kafka, el `eventId` **no** se registra y se commitea el
  offset. La comprobación periódica recupera el atraso cuando Redis vuelva.
- **7a. La copia ya está al día** (versión local mayor o igual a la objetivo, o `newVersion` menor
  o igual a la versión local, por ejemplo porque la comprobación periódica ya la aplicó): no se
  aplican cambios. Si es Kafka, se registra el `eventId` si faltaba (en una transacción corta
  propia) y se commitea el offset.
- **8a. CU-04 o CU-02 terminan con fallo:** se conservan las versiones que ya se habían aplicado
  completas, porque cada versión es su propia transacción. La versión en curso hace rollback. El
  `eventId` **no** se registra (cae con el rollback), el error queda en el estado de sincronización
  y, si es Kafka, se commitea el offset.
- **8b. CU-04 detecta una discontinuidad a mitad de camino** (desaparece un `changes:{v}` o una
  entidad no se puede verificar): se conservan las versiones ya aplicadas y se continúa con CU-02.

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
  no se pierde información y la partición de Kafka no queda trabada reintentando sin límite.
- **DP-09 (exclusión mutua):** una sola incorporación de cambios a la vez. Un segundo disparador
  espera; al entrar, vuelve a comparar versiones y normalmente ya no tiene nada que hacer. La
  espera tiene un tiempo máximo configurable: si se supera, termina sin aplicar cambios, lo
  registra en el estado de sincronización y la comprobación periódica lo cubre después.

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
