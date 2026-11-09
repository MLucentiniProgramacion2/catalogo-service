# CU-09: Consultar el estado de sincronización

## Objetivo

Informar en qué versión está la copia local, cuándo y cómo se sincronizó por última vez, si hay
una sincronización en curso y cuál fue el último error, para la app y para el diagnóstico.

## Actores

- **Principal:** Usuario final (desde la app KMP) o Administrador.

## Disparador

La app o el administrador consultan el estado de sincronización.

## Precondiciones

- El pedido trae un JWT de usuario válido; no se acepta el token técnico de servicio (DP-06).
- Los casos CU-01 a CU-04 y CU-08 registran su resultado en el estado de sincronización, que se
  persiste en la base local.

## Flujo principal

1. La app o el administrador piden el estado de sincronización.
2. El catálogo valida el token.
3. El catálogo lee el estado persistido y responde con:
   - versión local aplicada (o "sin datos" si la base está vacía);
   - fecha y camino de la última sincronización exitosa (snapshot o incremental) y su disparador
     (Kafka, arranque, periódico o manual);
   - última versión remota conocida (la de la última lectura de metadata, sin consultar a Redis en
     cada pedido);
   - si hay una sincronización en curso;
   - último error: fecha, tipo (por ejemplo, Redis no disponible, snapshot fallido, credencial
     inválida, espera de exclusión mutua superada) y una descripción sin datos sensibles.

## Flujos alternativos

- **2a. Sin token, token inválido o vencido:** `401`.
- **2b. Token técnico de servicio:** `403` (DP-06).
- **3a. Nunca hubo sincronización:** se responde con versión "sin datos" y, si existe, el error del
  intento inicial (CU-01).

## Postcondiciones

- **De éxito:** el solicitante conoce la versión aplicada y la salud de la sincronización. No hay
  efectos sobre el servicio.
- **De fallo:** se devuelve un error `problem+json`; no hay efectos.

## Decisiones del proyecto

- **DP-05 (permisos de operación):** `ROLE_USER` y `ROLE_ADMIN` pueden consultar el estado (la app
  puede mostrar, por ejemplo, "catálogo actualizado a la versión X" o un aviso de error). La
  respuesta nunca incluye hosts, credenciales, el JWT técnico ni stack traces.
- **DP-02:** la app consulta este estado directamente al catálogo, con el JWT de usuario.
- **Persistencia del estado (issue #13):** se guarda en una sola fila de `sync_status`, creada por
  Liquibase y protegida con `CHECK (id = 1)`. Si hay una sincronización **en curso** no se persiste:
  sale de la exclusión mutua en memoria (DP-09). Si se guardara y el servicio se cayera a mitad de un
  snapshot, quedaría marcada "en curso" para siempre; además, escrita dentro de la transacción del
  snapshot, nadie la vería hasta el commit. Supone una sola instancia del servicio.

## Contrato del endpoint (issue #29)

```http
GET /api/sync/status
Authorization: Bearer <jwt-de-usuario>
```

No tiene parámetros. Respuesta `200 OK`:

```json
{
  "localVersion": 12,
  "inProgress": false,
  "lastSuccess": { "at": "2026-11-04T12:00:00Z", "path": "SNAPSHOT", "trigger": "MANUAL" },
  "remote": { "version": 14, "checkedAt": "2026-11-04T12:30:00Z" },
  "lastError": { "at": "2026-11-04T13:00:00Z", "type": "LOCK_TIMEOUT", "description": "La sincronización (MANUAL) no se ejecutó: otra seguía en curso al vencer la espera máxima", "resolved": false }
}
```

Con la base sin datos y sin ningún intento (3a):

```json
{ "localVersion": null, "inProgress": false, "lastSuccess": null, "remote": null, "lastError": null }
```

| Campo | Regla |
| --- | --- |
| `localVersion` | Versión aplicada. `null` significa "sin datos": nunca se confirmó un snapshot. |
| `inProgress` | Si hay una sincronización corriendo ahora. Sale de la exclusión mutua en memoria; no mira si hay alguna esperando. |
| `lastSuccess` | Última sincronización exitosa: `path` es `SNAPSHOT` o `INCREMENTAL`, y `trigger` es `KAFKA`, `STARTUP`, `PERIODIC` o `MANUAL`. |
| `remote` | Última versión publicada conocida y cuándo se leyó: la escribe CU-03 cada vez que lee la metadata de Redis (con cualquier disparador: aviso de Kafka, arranque, comprobación periódica o CU-08 en modo normal), también cuando la copia está al día. No se consulta Redis en cada pedido. Queda en `null` hasta la primera lectura: sin versión local, CU-03 va directo al snapshot sin leer Redis. |
| `lastError` | Último error, aunque después haya habido un éxito: `lastSuccess` y `lastError` traen su fecha para compararlas. `type` es un valor de `SyncErrorType`; entre ellos, `INVALID_EVENT` (se descartó un aviso de Kafka ilegible o incompleto), `EVENT_PROCESSING_FAILED` (se descartó un aviso después de agotar los reintentos), `REDIS_UNAVAILABLE` (Redis no respondió) e `INCREMENTAL_FAILED` (la base rechazó una versión incremental; las anteriores se conservan). Con `INVALID_CREDENTIALS`, la descripción dice si fue la API REST o Redis. Que el estado publicado no se pueda verificar no es un error: termina en un snapshot, que queda como `lastSuccess` con `path: SNAPSHOT`, y el motivo va al log. |

### `lastError.resolved` (issue #35)

Un éxito no borra el último error, y una pasada que encuentra la copia al día no aplica nada, así que
no cambia `lastSuccess`. Sin otro dato, después de que Redis vuelve la app vería el error como vigente
hasta la próxima versión publicada. `resolved` lo resuelve sin tocar la base:

> `resolved` es `true` si y solo si `lastSuccess.at` **o** `remote.checkedAt` es **estrictamente
> posterior** a `lastError.at`.

- Con el mismo instante no cuenta como resuelto.
- Los tres instantes salen del mismo `Clock` del servicio (UTC), así que se pueden comparar entre sí.
- Funciona por el orden de CU-03: la versión remota se registra **antes** de aplicar, así que si en la
  misma pasada falla CU-04 o CU-02, el error queda con fecha posterior y sigue sin resolver.

| Flujo | Qué se escribe | `resolved` |
| --- | --- | --- |
| Se aplicó una versión o un snapshot después del error | `lastSuccess` (ahora) | `true` |
| Una pasada encontró la copia al día después del error | `remote.checkedAt` (ahora) | `true` |
| Redis no respondió al leer la metadata | `lastError`, sin `remote` | `false` |
| Se leyó la metadata y después falló CU-04 o CU-02 | `remote` (t1) y después `lastError` (t2 > t1) | `false` |
| `LOCK_TIMEOUT` y la que tenía la exclusión terminó bien | `lastSuccess` o `remote`, después del error | `true` |
| Sin copia y el snapshot falló | `lastError` | `false` |

Cada grupo es `null` entero si no hay datos, nunca a medias. La lectura es una sola fila en una
transacción de solo lectura; "en curso" se mira antes, así que el peor desfase es informar `inProgress`
con la versión ya nueva.

| Caso | Status | Cuerpo |
| --- | --- | --- |
| Sin token, firma inválida o vencido (2a) | `401` | vacío (Spring Security) |
| Token de servicio o sin `ROLE_USER`/`ROLE_ADMIN` (2b) | `403` | vacío (Spring Security) |
| Sin datos (3a) | `200` | `localVersion: null` y, si existe, el error del intento inicial |

**Nunca responde `503`:** a diferencia de CU-05 y CU-06, con la base vacía responde `200`, porque es
la consulta que explica por qué las demás dan `503`. La respuesta no lleva hosts, URL de la cátedra,
tokens, stack traces ni nombres de clases.

## Respaldo en las consignas

- Enunciado §4.1: "informar el estado y los errores de sincronización".
- Enunciado §5 punto 15: "informar errores de integración".
- Enunciado §8: persistencia de progreso y errores observables.
- Enunciado §9: no exponer secretos en respuestas innecesarias ni en logs.
- Contrato §18.4: reintentos "observables".
