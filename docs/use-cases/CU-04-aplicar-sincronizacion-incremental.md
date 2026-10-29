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

1. Se lee `catedra:sync:changes:{v}` y se verifica que su campo `version` sea `v`.
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

- **1a. Falta `changes:{v}`** (salió de la ventana de retención mientras se aplicaba el lote):
  discontinuidad. Se conservan las versiones ya aplicadas y se vuelve a CU-03, que ejecuta CU-02.
- **3a. Un ID listado en `changes:{v}` no existe en su Hash:** el estado no se puede verificar. Se
  conservan las versiones ya aplicadas y se vuelve a CU-03, que ejecuta CU-02.
- **4a. Entidad inválida o con una referencia inexistente:** igual que 3a, el estado no se puede
  verificar y se pasa a CU-02.
- **1b / 3b. Redis no disponible o timeout:** reintentos acotados (DP-08). Si se agotan, se
  conservan las versiones ya aplicadas completas y el caso termina con fallo.
- **6a / 8a. Falla de la base:** rollback de la versión `v`, que no avanza. Reintentos acotados
  (DP-08). Si se agotan, se conservan las versiones anteriores y el caso termina con fallo; el
  `eventId` no queda registrado.

## Postcondiciones

- **De éxito:** la versión local es la versión objetivo y la copia local refleja el estado vigente
  de todas las entidades afectadas.
- **De fallo:** la versión local es la última versión aplicada completa, la copia corresponde a esa
  versión y nunca queda una versión aplicada a medias.

## Decisiones del proyecto

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
