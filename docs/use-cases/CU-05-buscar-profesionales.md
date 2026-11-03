# CU-05: Buscar profesionales con filtros

## Objetivo

Que el usuario encuentre profesionales filtrando por categoría, nombre, estado habilitado y
disponibilidad, resolviendo la búsqueda **solo con la copia local** del catálogo.

## Actores

- **Principal:** Usuario final, desde la app KMP.

## Disparador

El usuario ejecuta una búsqueda desde la app.

## Precondiciones

- La app envía un JWT de usuario emitido por el servicio de turnos. El catálogo valida firma,
  vigencia y que **no** sea un token técnico de servicio (DP-06).

## Flujo principal

1. La app envía la búsqueda con cualquier combinación de estos filtros opcionales:
   - **categoría:** ID de categoría;
   - **nombre:** texto parcial, sin distinguir mayúsculas ni minúsculas, sobre nombre y apellido;
   - **habilitado:** `true` o `false`; si se omite, se toma `true` (DP-01);
   - **disponibilidad:** día de la semana (`MONDAY` a `SUNDAY`) y, opcionalmente, una franja
     horaria desde/hasta.
2. El catálogo valida el token y los parámetros.
3. El catálogo consulta la base local aplicando los filtros indicados. Un filtro omitido no
   restringe el resultado, salvo "habilitado": si se omite, solo se devuelven los profesionales
   habilitados; con `habilitado=false` se devuelven los deshabilitados (bajas lógicas).
4. Para el filtro de disponibilidad, se incluyen los profesionales que tienen al menos un horario
   semanal **habilitado** de ese día cuya franja se superpone con la franja pedida (o cualquier
   horario habilitado de ese día, si no se indicó franja).
5. El catálogo responde con la lista de profesionales (con su categoría y estado) y la versión
   local del catálogo con la que se resolvió la búsqueda.

## Flujos alternativos

- **2a. Sin token, token inválido o vencido:** `401`.
- **2b. Token técnico de servicio:** `403`; los endpoints de usuario no lo aceptan (DP-06).
- **2c. Parámetros inválidos** (día inexistente, hora mal formada, "desde" posterior a "hasta",
  franja sin día): `400` en `application/problem+json`, con el detalle de los campos.
- **3a. La base local está vacía** (nunca se aplicó un snapshot): `503` en
  `application/problem+json`, indicando que el catálogo todavía no está disponible.
- **3b. Hay una sincronización en curso:** la búsqueda se resuelve con la última copia confirmada
  y no se bloquea (DP-03).
- **5a. Ningún profesional cumple los filtros:** lista vacía; es una respuesta válida.

## Postcondiciones

- **De éxito:** el usuario recibe los profesionales que cumplen los filtros según la versión local
  informada. No se hizo ninguna consulta a la cátedra.
- **De fallo:** el usuario recibe un error `problem+json` que la app puede mostrar; no hay efectos
  sobre el estado del servicio.

## Decisiones del proyecto

- **DP-01 (disponibilidad):** "disponibilidad" significa que el profesional **atiende** en ese día
  o franja según sus horarios semanales habilitados. **No** considera ocupaciones: los turnos
  libres reales los construye el servicio de turnos combinando horarios y ocupaciones (enunciado
  §7). Consultar ocupaciones en cada búsqueda obligaría a llamar a la cátedra por búsqueda, algo
  que prohíbe el enunciado §6.2. **Valor por defecto del filtro "habilitado":** si no se indica, se
  devuelven solo los profesionales habilitados, que son los que el usuario puede reservar; con
  `habilitado=false` se devuelven los deshabilitados.
- **DP-02 (acceso desde KMP):** la app consulta al catálogo **directamente**, con el JWT de usuario
  emitido por turnos; turnos no hace de intermediario. Así la búsqueda sigue funcionando aunque
  turnos no esté disponible (enunciado §8). CORS no afecta a la app Android nativa (lo aplica solo
  el navegador), pero se configura restrictivo según los clientes permitidos (enunciado §9).
- **DP-03 (búsquedas durante la reconstrucción):** mientras se aplica un snapshot o un incremento,
  se sirve la última copia confirmada y la respuesta informa la versión local; con la base vacía,
  `503`. La copia local sirve para mostrar y filtrar, y la cátedra tiene la última palabra al
  reservar: si un dato quedó desactualizado, el hold falla con `PROFESSIONAL_DISABLED`,
  `INVALID_SLOT`, `SLOT_ALREADY_RESERVED` o `SLOT_ALREADY_HELD` (contrato §9 y §13.1).
- **DP-06:** los endpoints de usuario rechazan el token técnico de servicio.

## Contrato del endpoint (issue #27)

```http
GET /api/professionals?categoryId=&name=&enabled=&day=&from=&to=&page=&size=
Authorization: Bearer <jwt-de-usuario>
```

| Parámetro | Tipo | Regla |
| --- | --- | --- |
| `categoryId` | número | Opcional. Una categoría que no existe da lista vacía, no `404`. |
| `name` | texto | Opcional. Hasta 100 caracteres, contados sobre el texto **recibido** (antes de recortar espacios). Ver abajo. |
| `enabled` | `true` / `false` | Opcional. Si se omite, se toma `true` (DP-01). Distinto de CU-06, donde omitirlo significa "todas". |
| `day` | `MONDAY` a `SUNDAY` | Opcional. Solo esos valores, en mayúsculas, como el contrato §7. |
| `from`, `to` | hora | Opcionales y solo con `day`. `HH:mm` o `HH:mm:ss` (contrato §4). Se puede mandar uno solo. |
| `page` | entero ≥ 0 | Por defecto `0`. |
| `size` | entero de 1 a 100 | Por defecto `20`. |

- **Disponibilidad (paso 4, DP-01):** aparece el profesional que tiene al menos un horario semanal **habilitado** de
  ese día que se superpone con la franja. Sin franja, alcanza con cualquier horario habilitado de ese día. No mira
  ocupaciones ni turnos: los turnos libres los arma el servicio de turnos.
  - Los horarios y la franja son intervalos **semiabiertos** `[inicio, fin)`: se superponen si `start < to` y
    `end > from`. Un horario que termina a las 12:00 no se superpone con una franja que empieza a las 12:00.
  - Con solo `from` o solo `to`, la franja queda abierta del otro lado.
  - `from` tiene que ser anterior a `to`: con `from == to`, `[from, to)` está vacía y la búsqueda da `400`.
  - Un horario con `startTime >= endTime` (el contrato no lo impide) es el intervalo vacío `[start, end)`: no se
    superpone con ninguna franja y, sin franja, no cubre ninguna hora del día. No cuenta nunca, con franja o sin
    ella. La regla no depende de los turnos: un horario con `slotDurationMinutes <= 0` sí cuenta.
  - Un profesional con varios horarios que cumplen aparece una sola vez y cuenta una vez en `totalElements`.

- **Nombre:** se busca el texto, sin distinguir mayúsculas, dentro de "nombre apellido" (los dos campos unidos por un
  espacio): `ana pe` encuentra a "Ana Perez", pero `perez ana` no. Se recortan los espacios de los extremos; vacío o
  de solo espacios cuenta como filtro ausente. `%` y `_` se buscan como letras, no como comodines. Las tildes cuentan:
  `ÁNGEL` encuentra a "Ángel", pero `Perez` no encuentra a "Pérez" (ignorar tildes necesitaría una extensión de
  PostgreSQL).
- **Mayúsculas con tilde:** las pasa a minúsculas PostgreSQL, según el locale de la base. Con el de la imagen oficial
  (`en_US.utf8`) funciona; con una base creada con locale `C`, solo cambiarían las letras ASCII.
- **Orden por `id`:** es único, así que el orden es total y estable entre páginas (un profesional no aparece en dos ni
  en ninguna), y no depende de la collation. Si hace falta, la app reordena la página.
- **Categoría deshabilitada:** un profesional habilitado de una categoría deshabilitada aparece con `enabled` omitido,
  porque DP-01 mira el estado del profesional. La respuesta informa `category.enabled` y la app decide qué mostrar.
  Respaldo: la matriz §13.1 del contrato no tiene un error de hold por categoría deshabilitada, solo
  `PROFESSIONAL_DISABLED`.

Respuesta `200 OK`:

```json
{
  "catalogVersion": 1,
  "page": 0,
  "size": 20,
  "totalElements": 24,
  "totalPages": 2,
  "professionals": [
    {
      "id": 1,
      "firstName": "Ana",
      "lastName": "Perez",
      "enabled": true,
      "category": { "id": 101, "name": "Clinica medica", "enabled": true }
    }
  ]
}
```

`catalogVersion`, la página y `totalElements` salen de la misma transacción (`REPEATABLE READ`): un snapshot confirmado
en el medio no los mezcla. Una página fuera de rango (por ejemplo `page=50`) responde `200` con `professionals: []` y
los totales correctos; si ningún profesional cumple los filtros, también `200` con la lista vacía (5a).

| Caso | Status | Cuerpo |
| --- | --- | --- |
| Sin token, firma inválida o vencido (2a) | `401` | vacío (Spring Security) |
| Token de servicio o sin `ROLE_USER`/`ROLE_ADMIN` (2b) | `403` | vacío (Spring Security) |
| Parámetro inválido (2c): fuera de rango, no convertible, `name` de más de 100 caracteres, día inexistente o en minúsculas, hora mal formada, `from` no anterior a `to`, franja sin día | `400` | `problem+json`, `code: VALIDATION_ERROR` y `fieldErrors` |
| Sin versión aplicada (3a) | `503` | `problem+json`, `code: CATALOG_NOT_INITIALIZED`, sin `Retry-After` |

El `400` tiene la forma del contrato §13, con un `fieldError` por parámetro inválido. Ni el `detail` ni los mensajes
repiten el valor recibido:

```json
{
  "type": "https://www.jhipster.tech/problem/constraint-violation",
  "title": "Method argument not valid",
  "status": 400,
  "detail": "Invalid request parameters",
  "message": "error.validation",
  "path": "/api/professionals",
  "code": "VALIDATION_ERROR",
  "fieldErrors": [
    { "objectName": "professionalSearchParams", "field": "page", "message": "must be greater than or equal to 0" },
    { "objectName": "professionalSearchParams", "field": "size", "message": "Failed to convert value" }
  ]
}
```

Las reglas que combinan parámetros tienen su propio `field`:

| Regla | `field` | `message` |
| --- | --- | --- |
| `from` no anterior a `to` | `timeRangeOrdered` | `from must be before to` |
| `from` o `to` sin `day` | `timeRangeHasDay` | `from and to require day` |

Como en el contrato de la cátedra, la app decide por `status` y `code`, nunca por `detail` ni por el texto de
`fieldErrors`.

## Respaldo en las consignas

- Enunciado §4.1: filtros mínimos por categoría, nombre, estado habilitado y disponibilidad,
  resueltos con la copia local y usables desde KMP.
- Enunciado §5 punto 7: "buscar y filtrar profesionales usando la copia local".
- Enunciado §6.2: "no se aceptará consultar al servicio central por cada búsqueda".
- Enunciado §7: la disponibilidad real combina reglas semanales y ocupaciones en turnos.
- Enunciado §9: JWT exigido y validado, validación de datos en cada límite, CORS.
- Contrato §4 y §13: formatos y errores `problem+json`.
