# CU-08: Disparar la sincronización manualmente

## Objetivo

Que un administrador pueda forzar una sincronización (por ejemplo, durante la demo) sin esperar a
Kafka ni a la comprobación periódica, y sin reemplazar la sincronización automática.

## Actores

- **Principal:** Administrador (usuario con rol `ROLE_ADMIN`).
- **Secundarios:** Redis de la cátedra (modo normal, a través de CU-03) y API REST de la cátedra
  (modo snapshot, a través de CU-02).

## Disparador

El administrador pide una sincronización manual.

## Precondiciones

- El pedido trae un JWT de usuario válido con el rol `ROLE_ADMIN`. El rol lo emite el servicio de
  turnos en el token. No se acepta el token técnico de servicio (DP-06).

## Flujo principal

1. El administrador pide la sincronización indicando el modo:
   - **normal** (por defecto): incorporar los cambios pendientes;
   - **snapshot**: reconstruir la copia completa aunque la versión esté al día.
2. El catálogo valida el token y que el usuario tenga `ROLE_ADMIN`.
3. Según el modo:
   - **normal:** ejecuta **CU-03 Incorporar cambios del catálogo** con el disparador "manual"
     (compara versiones y aplica incremental o snapshot según corresponda);
   - **snapshot:** toma la exclusión mutua (DP-09) y ejecuta **CU-02 Ejecutar la sincronización
     completa**.
4. El catálogo responde con el resultado: versión local antes y después, camino aplicado
   (incremental, snapshot o sin cambios) y, si falló, el error (sin datos sensibles).

## Flujos alternativos

- **2a. Sin token, token inválido o vencido:** `401`.
- **2b. Usuario sin `ROLE_ADMIN`, o token técnico de servicio:** `403`.
- **2c. Modo desconocido:** `400` en `application/problem+json`.
- **3a. Se supera la espera máxima de la exclusión mutua:** no se aplica nada, queda registrado en
  el estado de sincronización y se informa al administrador que no pudo ejecutarse porque hay otra
  sincronización en curso.
- **3b. La sincronización falla** (Redis, cátedra o base): se informa el error; la copia queda en
  la última versión aplicada completa (DP-08).

## Postcondiciones

- **De éxito:** la copia local queda en la versión vigente de la cátedra (o reconstruida, en modo
  snapshot) y el resultado queda en el estado de sincronización.
- **De fallo:** la copia local queda consistente en la última versión aplicada completa y el error
  queda registrado.

## Decisiones del proyecto

- **DP-05 (permisos de operación):** la sincronización manual requiere `ROLE_ADMIN`. Depende de que
  turnos incluya el rol en el JWT que emite. El estado de sincronización (CU-09), en cambio, lo
  pueden consultar `ROLE_USER` y `ROLE_ADMIN`.
- **DP-10 (modos de la sincronización manual):** por defecto es un disparador más de CU-03 (misma
  lógica e idempotencia). Con un parámetro explícito fuerza un snapshot (CU-02), útil para mostrar
  la reconstrucción en la demo. Los dos modos respetan la exclusión mutua (DP-09).
- **Modo normal pendiente (issue #29):** CU-03 todavía no existe. Hasta que el issue de CU-03 lo
  conecte, el modo normal responde `501` y **un `POST` sin `mode` también**, porque es el modo por
  defecto. Hoy la sincronización manual se dispara con `mode=SNAPSHOT`.
- Ya estaba decidido que la sincronización es **automática** al recibir `CatalogUpdated`: la manual
  se suma, no la reemplaza.

## Contrato del endpoint (issue #29)

```http
POST /api/admin/sync?mode={NORMAL|SNAPSHOT}
Authorization: Bearer <jwt-de-usuario-con-ROLE_ADMIN>
```

| Parámetro | Tipo | Regla |
| --- | --- | --- |
| `mode` | `NORMAL` / `SNAPSHOT` | Opcional. Si se omite o va vacío, `NORMAL` (DP-10). Solo esos valores, en mayúsculas, como `day` en CU-05: `snapshot` da `400`. |

El pedido no tiene cuerpo y es **sincrónico**: vuelve cuando la sincronización terminó, como pide el
paso 4. Puede tardar minutos: en el peor caso, unos 11 (hasta 6 de espera y unos 5 de ejecución). Si
el cliente corta la conexión, la sincronización sigue y el resultado queda en CU-09.

Respuesta `200 OK`:

```json
{ "mode": "SNAPSHOT", "localVersionBefore": 3, "localVersionAfter": 5, "path": "SNAPSHOT", "attempts": 1 }
```

`localVersionBefore` es `null` si la base no tenía versión. `attempts` cuenta los intentos, con el
primero incluido (DP-08). Por ahora `path` es siempre `SNAPSHOT`: `INCREMENTAL` y "sin cambios"
llegan con CU-03.

| Caso | Status | Cuerpo |
| --- | --- | --- |
| Sin token, firma inválida o vencido (2a) | `401` | vacío (Spring Security) |
| Sin `ROLE_ADMIN`, o token técnico de servicio (2b) | `403` | vacío (Spring Security) |
| Modo desconocido o en minúsculas (2c) | `400` | `problem+json`, `message: error.http.400`, `detail: Failed to convert 'mode'`, sin `code`, como CU-06 |
| Se superó la espera de la exclusión mutua (3a) | `409` | `problem+json`, `code: SYNC_IN_PROGRESS` |
| `mode=NORMAL` o sin `mode`, hasta CU-03 | `501` | `problem+json`, `code: SYNC_MODE_NOT_AVAILABLE` |
| La sincronización falló (3b) | `502` | `problem+json`, `code: SYNC_FAILED`, con `errorType`, versiones e intentos |

Ejemplo de `502`:

```json
{
  "type": "https://www.jhipster.tech/problem/problem-with-message",
  "title": "Bad Gateway",
  "status": 502,
  "detail": "La cátedra no estuvo disponible después de 4 intentos: La cátedra no respondió al pedir el snapshot",
  "message": "error.http.502",
  "path": "/api/admin/sync",
  "code": "SYNC_FAILED",
  "errorType": "CATEDRA_UNAVAILABLE",
  "localVersionBefore": 3,
  "localVersionAfter": 3,
  "attempts": 4
}
```

- **`502` y no `200`:** el pedido era válido, pero lo pedido no ocurrió. Con un `200`, un cliente que
  mira solo el status vería éxito. Es un solo status por `code`: si la que falló es la base local,
  `errorType` es `DATABASE_UNAVAILABLE`, y si ni siquiera se pudo leer la versión, las dos versiones
  van en `null` y `attempts` en `0`.
- **`errorType`** toma los valores de `SyncErrorType`: `CATEDRA_UNAVAILABLE`, `INVALID_CREDENTIALS`,
  `SNAPSHOT_FAILED` o `DATABASE_UNAVAILABLE`.
- **`409`:** queda registrado como `LOCK_TIMEOUT` en CU-09. Se puede volver a pedir cuando termine la
  que está corriendo; va sin `Retry-After` porque su duración no se conoce.
- **`501`:** responde enseguida, sin tomar la exclusión y sin registrar un error, porque no falló
  ninguna sincronización.

Como en el contrato de la cátedra (§13), la app decide por `status` y `code`, nunca por `detail`. Las
respuestas no llevan hosts, URL de la cátedra, tokens, stack traces ni nombres de clases.

## Respaldo en las consignas

- Enunciado §6.1: snapshot "cuando necesite reconstruirla".
- Enunciado §9: rol administrador opcional con permisos documentados y autorización correcta.
- Enunciado §11: evidencias mínimas de "una inicialización desde snapshot" y "recuperación mediante
  snapshot luego de una discontinuidad".
- Contrato §16: el flujo automático por `CatalogUpdated` sigue siendo el principal.
