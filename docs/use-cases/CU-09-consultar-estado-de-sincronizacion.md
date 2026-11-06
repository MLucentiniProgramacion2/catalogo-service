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
  "lastError": { "at": "2026-11-04T13:00:00Z", "type": "LOCK_TIMEOUT", "description": "La sincronización (MANUAL) no se ejecutó: otra seguía en curso al vencer la espera máxima" }
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
| `remote` | Última versión publicada conocida y cuándo se leyó. No se consulta Redis en cada pedido; queda en `null` hasta CU-04. |
| `lastError` | Último error, aunque después haya habido un éxito: `lastSuccess` y `lastError` traen su fecha para compararlas. `type` es un valor de `SyncErrorType`; entre ellos, `INVALID_EVENT` (se descartó un aviso de Kafka ilegible o incompleto) y `EVENT_PROCESSING_FAILED` (se descartó un aviso después de agotar los reintentos). |

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
