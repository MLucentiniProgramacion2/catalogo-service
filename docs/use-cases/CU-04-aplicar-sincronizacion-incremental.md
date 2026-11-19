# CU-04: Aplicar la sincronización incremental

## Objetivo

Aplicar en orden cada versión pendiente publicada en Redis, con una transacción por versión, de
modo que la versión local solo avance cuando todos los cambios de esa versión quedaron
persistidos.

## Actores

- **Principal:** ninguno directo. Es un caso **incluido** por CU-03.
- **Secundario:** Redis de la cátedra.

## Disparador

CU-03 determina que el historial de cambios es continuo y pide aplicar las versiones desde la
versión local + 1 hasta la versión objetivo.

## Precondiciones

- Existe una versión local, que es mayor o igual a `oldestAvailableVersion`.
- El caso que lo invoca tiene tomada la exclusión mutua de sincronización (DP-09).

## Flujo principal

Para cada versión `v`, desde la versión local + 1 hasta la objetivo, en orden ascendente:

1. Se toma `catedra:sync:changes:{v}`, ya leído por CU-03 junto con los del resto del lote (un solo
   `MGET`), y se verifica que su campo `version` sea `v`.
2. Se obtienen las listas de IDs afectados: `professionalCategories`, `professionals` y
   `weeklySchedules`.
3. Para cada ID se lee su estado vigente en el Hash correspondiente
   (`catedra:sync:professional-categories`, `catedra:sync:professionals`,
   `catedra:sync:weekly-schedules`).
4. Se validan las entidades leídas: formatos, y que cada profesional apunte a una categoría y cada
   horario a un profesional que existan en la copia local o en esta misma versión. Se ignoran los
   campos desconocidos.
5. Se abre una transacción y se verifica que la versión local siga siendo `v - 1` (si otro proceso
   ya la avanzó, se hace rollback y se termina sin cambios).
6. Se guardan las entidades en orden (categorías, profesionales, horarios), creándolas o
   actualizándolas con su estado vigente. Las deshabilitadas se conservan con `enabled=false`
   (baja lógica).
7. Se guarda `v` como versión local. Si es la última versión del lote y el disparador fue Kafka, se
   registra el `eventId` en esta misma transacción (DP-07).
8. Se confirma la transacción y se pasa a la versión siguiente.

Las lecturas de Redis (pasos 1 a 4) se hacen antes de abrir la transacción, para que esta sea
corta.

Reaplicar una versión es idempotente: cada entidad se guarda con su estado vigente, así que
repetir el proceso deja el mismo resultado. Como el Hash tiene siempre el estado *actual* (no el
histórico), aplicar `v` puede traer datos que ya reflejan una versión posterior. Es correcto,
porque al terminar el lote la copia converge al estado de la versión objetivo.

## Flujos alternativos

- **1a. Falta `changes:{v}`:** ya no ocurre acá. CU-03 lee los `changes:{v}` de todo el lote de una
  vez en el paso 8, y si falta alguno decide el snapshot **antes de aplicar nada** (issue #33). Como el
  contenido de una versión no cambia, no hace falta volver a leerlo. Si el `version` de un
  `changes:{v}` no es `v`, el estado no se puede verificar: igual que 3a.
- **3a. Un ID listado en `changes:{v}` no existe en su Hash:** el estado no se puede verificar. Se
  conservan las versiones ya aplicadas y se vuelve a CU-03, que ejecuta CU-02.
- **4a. Entidad inválida o con una referencia inexistente:** igual que 3a, el estado no se puede
  verificar y se pasa a CU-02. Un profesional cuya categoría no está ni en la copia local ni en esta
  versión, o un horario cuyo profesional no está, terminan en snapshot: la base nunca es la que corta
  por la FK. Se descartó traer la entidad referenciada del Hash: arreglaría en silencio una copia que
  ya está inconsistente, con más código y más viajes, para un caso que hoy no pasa (la cátedra solo
  cambia `enabled` del profesional 201). La verificación va adentro de la transacción de la versión,
  después del paso 5: un `select id ... in (...)` por tipo, solo si hay referencias afuera de la
  versión.
- **Vence el plazo del lote** (`sync.incremental.time-budget`, 60 s, medido con el reloj de la
  aplicación): no empieza otra versión ni otro reintento, se conservan las aplicadas, queda un WARN con
  cuántas se aplicaron y desde cuál sigue el snapshot, y se vuelve a CU-03, que ejecuta CU-02.
- **1b / 3b. Redis no disponible o timeout:** reintentos acotados (DP-08). Si se agotan, se
  conservan las versiones ya aplicadas completas y el caso termina con fallo.
- **6a / 8a. Falla de la base:** rollback de la versión `v`, que no avanza. Si la falla es
  transitoria (está en `DatabaseFailures`), reintentos acotados (DP-08); si se agotan, se conservan las
  versiones anteriores, el caso termina con `DATABASE_UNAVAILABLE` y el `eventId` no queda registrado.
  Si no es transitoria (la base rechazó los datos o el uso), no se reintenta: `INCREMENTAL_FAILED`.
- **8a. Commit dudoso:** si falla el commit de `v` pero la base sí lo confirmó, el reintento encuentra
  la versión local en `v` y no en `v - 1`. Solo en un reintento después de una falla, eso cuenta como
  aplicada y se sigue con la siguiente: reaplicar es idempotente. `TransactionSystemException` está en
  `DatabaseFailures`, así que ese reintento ocurre.

## Postcondiciones

- **De éxito:** la versión local es la versión objetivo y la copia local refleja el estado vigente
  de todas las entidades afectadas.
- **De fallo:** la versión local es la última versión aplicada completa, la copia corresponde a esa
  versión y nunca queda una versión aplicada a medias.

## Decisiones del proyecto

- **Reintentos (issue #33):** un solo presupuesto de 3 reintentos (1, 2 y 4 s) para todo el lote, así
  no se multiplican por versión. Un reintento repite solo el paso que falló: si falla la transacción,
  no se vuelve a leer Redis.
- **Viajes a Redis:** uno por cada Hash con IDs en cada versión (`HMGET`). Con el caso real (33
  versiones, cada una con un solo profesional), 33 `HMGET`, más el `MGET` de los cambios y la metadata
  que lee CU-03. Medido en el servidor con `INFO commandstats`.
- **Sentencias de una versión real:** 5 (`SELECT` de `sync_status`, de la categoría y del profesional,
  `UPDATE` del profesional y de `sync_status`), más el `INSERT` del `eventId` en la última. Reaplicada,
  4: sin el `UPDATE` del profesional. Medido con las estadísticas de Hibernate. Las entidades se
  guardan con `merge`: su `SELECT` es el que dice si hay que crearla o actualizarla.
- **El peor caso** de CU-03 con este caso adentro está en DP-09 ([README](README.md)): el plazo del lote
  es lo que le da una cota. Contando solo reintentos, cada versión puede tardar sus timeouts enteros
  sin llegar a fallar, y el total crece con la cantidad de versiones.

- **DP-07:** el `eventId` se registra en la transacción de la última versión del lote. Si falla
  algo antes, el rollback también lo deshace y el evento no queda marcado como procesado.
- **DP-08:** si se agotan los reintentos, las versiones que ya se habían aplicado completas antes
  de la falla **se conservan**, porque cada versión es su propia transacción. Solo hace rollback la
  versión en curso.
- **DP-09:** la verificación del paso 5 protege la versión local aunque la exclusión mutua fallara.

## Respaldo en las consignas

- Enunciado §6.2: "los cambios deberán aplicarse en orden. La versión local solo podrá avanzar
  cuando la unidad de trabajo correspondiente haya terminado correctamente"; ante discontinuidad o
  estado no verificable, sincronización completa.
- Enunciado §8: datos de entrada inválidos o referencias inexistentes.
- Contrato §14.4: entidades vigentes en Hashes; las deshabilitadas son bajas lógicas.
- Contrato §14.5: los arrays de `changes:{version}` contienen IDs y hay que leer el estado vigente
  de cada uno; "una versión local solo avanza después de aplicar correctamente todos sus cambios".
- Contrato §16: "aplicar cambios y avanzar versión atómica" por cada versión, en orden.
