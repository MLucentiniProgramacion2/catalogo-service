# CU-02: Ejecutar la sincronización completa (snapshot)

## Objetivo

Reemplazar la copia local de categorías, profesionales y horarios semanales por el snapshot que
entrega la cátedra, y guardar como versión local exactamente la `snapshotVersion` de esos datos,
todo en una única transacción.

## Actores

- **Principal:** ninguno directo. Es un caso **incluido** por CU-01 (base vacía), CU-03
  (discontinuidad o versión fuera de la ventana) y CU-08 (snapshot forzado).
- **Secundario:** API REST de la cátedra.

## Disparador

Otro caso de uso determina que hace falta reconstruir la copia local.

## Precondiciones

- El JWT técnico de la cuenta de integración está configurado por variable de entorno.
- El caso que lo invoca tiene tomada la exclusión mutua de sincronización (DP-09).

## Flujo principal

1. El servicio pide `GET /api/synchronization/snapshot` con `Authorization: Bearer <jwt-tecnico>`,
   con un timeout configurable.
2. La cátedra responde `200 OK` con `snapshotVersion`, `generatedAt`, `professionalCategories`,
   `professionals` y `weeklySchedules`, incluidas las entidades deshabilitadas.
3. El servicio valida la respuesta antes de tocar la base: campos obligatorios, formatos (instantes
   ISO-8601 UTC, horas `HH:mm` o `HH:mm:ss`, `dayOfWeek` válido), y que cada profesional apunte a
   una categoría del snapshot y cada horario a un profesional del snapshot. Ignora los campos
   desconocidos.
4. Abre una transacción.
5. Reemplaza la copia local con las tres colecciones: las entidades del snapshot quedan con su
   estado (incluido `enabled=false`, que representa bajas lógicas) y las que no figuran en el
   snapshot dejan de existir localmente.
6. Guarda como versión local la `snapshotVersion`, junto con la fecha de aplicación y el origen
   "snapshot".
7. Confirma la transacción.
8. Registra el resultado en el estado de sincronización (CU-09).

## Flujos alternativos

- **1a. Timeout, error de red, `5xx` o `503`:** se reintenta de forma acotada (DP-08). Al ser un
  `GET` sin efectos, repetirlo es seguro. Si se agotan los reintentos, no se modifica nada, se
  registra el error y el caso termina con fallo.
- **2a. `401` o `403`** (JWT técnico vencido, inválido o sin integración asociada): no se
  reintenta, porque no es una falla transitoria. Se registra un error de configuración, **sin
  incluir el token** en el estado ni en los logs, y el caso termina con fallo.
- **3a. Respuesta inválida o con referencias inexistentes:** no se aplica nada, se registra el
  error y el caso termina con fallo. No se aplica un snapshot parcial.
- **5a. Falla de la base durante la transacción:** se hace rollback; la copia anterior y su
  versión quedan intactas. Si la falla es transitoria (la base no respondió, se cortó la conexión,
  timeout o bloqueo), se reintenta de forma acotada (DP-08); si se agotan los reintentos, se
  registra el error y el caso termina con fallo. Si la base rechazó los datos (integridad, uso
  inválido), no se reintenta. Reintentar después de un commit dudoso es seguro: aplicar el mismo
  snapshot dos veces deja el mismo resultado.

## Postcondiciones

- **De éxito:** la copia local coincide con el snapshot y la versión local es exactamente su
  `snapshotVersion`.
- **De fallo:** la copia local y la versión local quedan como estaban antes de empezar; el error
  queda registrado en el estado de sincronización.

## Decisiones del proyecto

- **DP-03:** mientras corre el snapshot, las búsquedas y la consulta de agenda siguen respondiendo
  con la última copia confirmada. La transacción aísla los cambios: nadie ve la copia a medio
  reemplazar.
- **DP-08:** timeouts configurables, hasta 3 reintentos con backoff exponencial (1 s, 2 s, 4 s) y,
  si se agotan, rollback (la versión no avanza) y error registrado en el estado de sincronización.

## Respaldo en las consignas

- Enunciado §4.1: "detectar discontinuidades y reconstruir la copia local".
- Enunciado §6.1: aplicar el snapshot "de forma consistente" y registrar "exactamente la versión
  que corresponde a los datos almacenados".
- Enunciado §8: "datos de entrada inválidos o referencias inexistentes" y reintentos no
  ilimitados.
- Contrato §7: endpoint, formato y regla "`snapshotVersion` solo se guarda como versión local
  cuando las tres colecciones quedaron aplicadas correctamente".
- Contrato §16 y §18.2: "se reemplaza la copia de forma consistente y se guarda su
  `snapshotVersion`".
- Contrato §18.4: reintentos limitados y observables.
