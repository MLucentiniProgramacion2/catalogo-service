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
   - **habilitado:** `true` o `false`;
   - **disponibilidad:** día de la semana (`MONDAY` a `SUNDAY`) y, opcionalmente, una franja
     horaria desde/hasta.
2. El catálogo valida el token y los parámetros.
3. El catálogo consulta la base local aplicando los filtros indicados. Un filtro omitido no
   restringe el resultado.
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
  que prohíbe el enunciado §6.2.
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

## Respaldo en las consignas

- Enunciado §4.1: filtros mínimos por categoría, nombre, estado habilitado y disponibilidad,
  resueltos con la copia local y usables desde KMP.
- Enunciado §5 punto 7: "buscar y filtrar profesionales usando la copia local".
- Enunciado §6.2: "no se aceptará consultar al servicio central por cada búsqueda".
- Enunciado §7: la disponibilidad real combina reglas semanales y ocupaciones en turnos.
- Enunciado §9: JWT exigido y validado, validación de datos en cada límite, CORS.
- Contrato §4 y §13: formatos y errores `problem+json`.
