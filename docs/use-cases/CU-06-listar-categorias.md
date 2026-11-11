# CU-06: Listar categorías

## Objetivo

Que la app muestre las categorías profesionales para que el usuario elija el filtro de categoría
de la búsqueda (CU-05), usando la copia local.

## Actores

- **Principal:** Usuario final, desde la app KMP.

## Disparador

El usuario abre la pantalla de búsqueda o el selector de categoría.

## Precondiciones

- La app envía un JWT de usuario válido; el catálogo rechaza el token técnico de servicio (DP-06).

## Flujo principal

1. La app pide el listado de categorías, opcionalmente filtrando por estado habilitado.
2. El catálogo valida el token y el parámetro.
3. El catálogo lee las categorías de la base local.
4. El catálogo responde con las categorías (ID, nombre, descripción, estado) y la versión local del
   catálogo.

## Flujos alternativos

- **2a. Sin token, token inválido o vencido:** `401`.
- **2b. Token técnico de servicio:** `403` (DP-06).
- **2c. Parámetro inválido:** `400` en `application/problem+json`.
- **3a. Base local vacía:** `503` en `application/problem+json` (DP-03).
- **3b. Sincronización en curso:** se responde con la última copia confirmada (DP-03).

## Postcondiciones

- **De éxito:** la app recibe las categorías de la versión local informada, sin consultas a la
  cátedra.
- **De fallo:** la app recibe un error `problem+json`; no hay efectos sobre el estado del servicio.

## Decisiones del proyecto

- **DP-02:** la app consulta al catálogo directamente con el JWT de usuario. Ver CU-05.
- **DP-03:** durante una reconstrucción se sirve la última copia confirmada; con la base vacía,
  `503`. Ver CU-05.
- **DP-06:** los endpoints de usuario rechazan el token técnico de servicio.

## Contrato del endpoint (issue #25)

```http
GET /api/categories?enabled={true|false}
Authorization: Bearer <jwt-de-usuario>
```

- `enabled` es opcional: sin él vienen todas, incluidas las deshabilitadas. Se aceptan los valores
  que convierte Spring a `Boolean` (`true`, `false`, `on`, `off`, `yes`, `no`, `1`, `0`, sin
  distinguir mayúsculas); vacío cuenta como ausente.
- Orden por `id`: lo fija la cátedra y no depende de la collation de la base. Si hace falta, la app
  ordena por nombre para mostrar.
- Sin paginación: son pocas (8 en el catálogo de la cátedra) y el selector las necesita todas.

Respuesta `200 OK`:

```json
{
  "catalogVersion": 1,
  "categories": [
    { "id": 1, "name": "Clinica medica", "description": "Atencion clinica general", "enabled": true }
  ]
}
```

`description` puede ser `null`. `catalogVersion` es la versión de la que salen las categorías: las dos
se leen en la misma transacción (`REPEATABLE READ`), así que un snapshot confirmado en el medio no las
mezcla.

| Caso | Status | Cuerpo |
| --- | --- | --- |
| Sin token, firma inválida o vencido (2a) | `401` | vacío, con `WWW-Authenticate` (Spring Security) |
| Token de servicio o sin `ROLE_USER`/`ROLE_ADMIN` (2b) | `403` | vacío, con `WWW-Authenticate` (Spring Security) |
| `enabled` no convertible (2c) | `400` | `problem+json`, `message: error.http.400` |
| Sin versión aplicada (3a) | `503` | `problem+json`, `code: CATALOG_NOT_INITIALIZED` |

Como en el contrato de la cátedra (§13), la app decide por `status` y `code`, nunca por `detail`.
"Base vacía" es que nunca se confirmó un snapshot (`sync_status.local_version` nula), no que haya
cero categorías: una versión aplicada sin categorías responde `200` con la lista vacía. Desde el
issue #35, el `503` lleva `Retry-After` con el intervalo de la comprobación periódica en segundos
(DP-04), que es la que vuelve a intentar el snapshot; con la periódica apagada no va.

## Respaldo en las consignas

- Enunciado §4.1: filtro por categoría, resuelto con la copia local y usable desde KMP; el
  catálogo es "la única fuente local vigente".
- Enunciado §9: JWT exigido y validado en las APIs protegidas.
- Contrato §7 y §14.4: estructura de las categorías (`id`, `name`, `description`, `enabled`).
