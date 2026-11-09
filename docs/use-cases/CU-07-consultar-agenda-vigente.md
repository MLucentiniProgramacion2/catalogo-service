# CU-07: Consultar la agenda vigente de un profesional

## Objetivo

Que el servicio de turnos obtenga del catálogo, como única fuente local vigente, los datos de un
profesional y sus horarios semanales habilitados, para construir los turnos disponibles y validar
un pedido de reserva antes de solicitar el hold a la cátedra.

## Actores

- **Principal:** Servicio de turnos.

## Disparador

El servicio de turnos necesita las reglas de agenda de un profesional: por ejemplo, para mostrar
la disponibilidad de una fecha o antes de pedir un hold.

## Precondiciones

- Turnos envía un **JWT técnico de servicio** firmado con la clave compartida, con
  `aud=catalogo-service`, `iss=turnos-service` y el rol `ROLE_SERVICE` (DP-06).

## Flujo principal

1. Turnos pide la agenda vigente de un profesional por su ID. Si la llamada nace de un pedido de
   usuario, envía además el login del usuario en el header `X-User-Login`.
2. El catálogo valida firma y vigencia del token y, como regla de autorización de este endpoint,
   exige `aud=catalogo-service`, `iss=turnos-service` y el rol `ROLE_SERVICE` (DP-06).
3. El catálogo registra en los logs el login recibido en `X-User-Login` (si vino) para
   trazabilidad. No lo usa para autorizar ni para filtrar datos.
4. El catálogo lee de la base local el profesional, su categoría y sus horarios semanales
   **habilitados**.
5. El catálogo responde con el profesional (incluido su estado habilitado), su categoría, los
   horarios habilitados (`dayOfWeek`, `startTime`, `endTime`, `slotDurationMinutes`) y la versión
   local del catálogo.

## Flujos alternativos

- **2a. Token ausente, firma inválida o vencido:** `401`.
- **2b. Token con firma válida sin `ROLE_SERVICE`, sin `aud=catalogo-service` o sin
  `iss=turnos-service`:** `403`, sea un token de usuario o uno de servicio mal armado. Un token de
  usuario nunca sirve para este caso.
- **4a. El profesional no existe en la copia local:** respuesta de "no encontrado" en
  `problem+json`.
- **4b. El profesional está deshabilitado** (baja lógica): se informa que no está habilitado
  (`enabled: false`) y sin horarios, para que turnos no ofrezca ni valide turnos con él.
- **4e. La categoría está deshabilitada y el profesional no:** se responde la agenda igual, con
  `category.enabled: false`. El contrato no lo prohíbe y la cátedra solo rechaza un hold por
  `PROFESSIONAL_DISABLED`; turnos decide qué hacer.
- **4c. Base local vacía:** `503` (DP-03). Turnos no puede iniciar operaciones que requieran validar
  información vigente.
- **4d. Sincronización en curso:** se responde con la última copia confirmada (DP-03).

La forma concreta de la respuesta y de estos errores está en "Contrato del endpoint (issue #35)".

## Postcondiciones

- **De éxito:** turnos recibe las reglas de agenda vigentes según la versión local informada y
  puede combinarlas con las ocupaciones que obtiene de la cátedra.
- **De fallo:** turnos recibe un error y no inicia la operación que dependía de esa información
  (enunciado §8). El catálogo no cambia de estado.

## Decisiones del proyecto

- **DP-06 (autenticación entre servicios):** turnos se autentica con un **JWT técnico de servicio**
  propio, de vida corta, firmado con la clave compartida. El token lleva `aud=catalogo-service`,
  `iss=turnos-service` y el rol `ROLE_SERVICE`, y el endpoint de agenda exige **los tres** además
  de firma y vigencia: si falta cualquiera, responde `403`. Exigir los dos claims a la vez viene de
  DPT-16 del servicio de turnos. Así un token de usuario nunca sirve para este caso aunque comparta
  la clave de firma. La verificación de `aud`, `iss` y `ROLE_SERVICE` es una regla de
  **autorización del endpoint de agenda**, no de la validación general del token: los endpoints de
  usuario siguen aceptando tokens de usuario sin `aud`. A la inversa, los endpoints de usuario (CU-05, CU-06, CU-08,
  CU-09) rechazan el token técnico. Cuando la llamada nace de un pedido de usuario, turnos manda
  además el login en `X-User-Login`, **solo para logs y trazabilidad, no para autorizar**. La
  agenda no es un dato propio de ningún usuario, así que no se pierde autorización al no propagar
  el JWT del usuario, y el token técnico también sirve en procesos de turnos que no tienen un
  usuario detrás.
- **DP-03:** durante una reconstrucción se sirve la última copia confirmada. Si la agenda
  informada quedó desactualizada, la cátedra rechaza el hold (`PROFESSIONAL_DISABLED`,
  `INVALID_SLOT`, `SLOT_ALREADY_RESERVED`, `SLOT_ALREADY_HELD`) y turnos lo traduce para el
  usuario: la copia local nunca autoriza una reserva por sí sola.

## Contrato del endpoint (issue #35)

```http
GET /api/service/professionals/{id}/agenda
Authorization: Bearer <jwt-tecnico-de-servicio>
X-User-Login: ana.perez
```

`X-User-Login` es opcional. Respuesta `200 OK`:

```json
{
  "catalogVersion": 57,
  "professional": {
    "id": 15,
    "firstName": "Ana",
    "lastName": "Gomez",
    "enabled": true,
    "category": { "id": 1, "name": "Clínica", "enabled": true }
  },
  "weeklySchedules": [
    { "id": 23, "dayOfWeek": "MONDAY", "startTime": "08:00:00", "endTime": "09:00:00", "slotDurationMinutes": 15 },
    { "id": 21, "dayOfWeek": "MONDAY", "startTime": "09:00:00", "endTime": "12:00:00", "slotDurationMinutes": 30 },
    { "id": 24, "dayOfWeek": "FRIDAY", "startTime": "09:00:00", "endTime": "13:00:00", "slotDurationMinutes": 30 }
  ]
}
```

Con el profesional deshabilitado (4b):

```json
{ "catalogVersion": 57, "professional": { "id": 16, "firstName": "Luis", "lastName": "Perez", "enabled": false, "category": { "id": 1, "name": "Clínica", "enabled": true } }, "weeklySchedules": [] }
```

| Campo | Regla |
| --- | --- |
| `catalogVersion` | Versión local de la que salen el profesional y los horarios: los tres salen de la misma lectura (`REPEATABLE_READ`), así que nunca se mezclan dos versiones. |
| `professional` | Habilitado o no, con su categoría. Nombre, apellido y categoría son los que turnos guarda solo para mostrar (DPT-12). |
| `professional.category.enabled` | Se informa; no cambia la agenda (4e). |
| `weeklySchedules` | Solo los habilitados, en orden: día de lunes a domingo, hora de inicio e ID. Vacío si el profesional está deshabilitado. |
| `startTime`, `endTime` | Horas locales de atención, siempre `HH:mm:ss`, como las publica la cátedra (contrato §4, §7 y §14.4). |
| `slotDurationMinutes` | Duración de cada turno, tal como la publica la cátedra. |

Los DTO (`AgendaResponse`, `AgendaProfessionalResponse`, `AgendaCategoryResponse` y
`AgendaScheduleResponse`) son propios de este contrato y no se comparten con los de la app (CU-05): un
cambio para la app no toca a turnos. Un test fija el JSON exacto.

| Caso | Status | Cuerpo |
| --- | --- | --- |
| Sin token, firma inválida, vencido o sin `exp` (2a) | `401` | vacío (Spring Security) |
| Token de usuario, o de servicio sin `ROLE_SERVICE`, `aud`, `iss`, `iat`, con más de 5 min de vida o con un rol de usuario (2b) | `403` | vacío (Spring Security) |
| El `id` no es un número | `400` | `problem+json` de JHipster; el `detail` no repite el valor |
| El profesional no está en la copia (4a) | `404` | `problem+json`, `code: PROFESSIONAL_NOT_FOUND` |
| Base local vacía (4c) | `503` | `problem+json`, `code: CATALOG_NOT_INITIALIZED`, y `Retry-After` con el intervalo de la comprobación periódica (sin el header si está apagada); el mismo `503` que CU-05 y CU-06 |

Con una sincronización en curso (4d) se responde la última copia confirmada: la lectura no espera ni
bloquea al snapshot. La regla de seguridad está en el [ADR 0007](../adr/0007-autorizacion-token-de-servicio.md).

**Sentencias por pedido:** 3 (la versión, el profesional con su categoría y los horarios
habilitados); 2 si el profesional no existe o está deshabilitado.

**`X-User-Login`:** solo para el log, en INFO
(`Agenda vigente del profesional 15 pedida por turnos (X-User-Login=ana.perez)`). Nunca autoriza,
filtra ni vuelve en la respuesta. Se escribe solo si tiene de 3 a 50 caracteres (DPT-20) de los que admite
el login de turnos; si no, `X-User-Login inválido`, sin repetirlo. Un valor con caracteres de control
(`\r\n`) ni llega: el firewall de Spring Security rechaza el pedido con `400`.

## Respaldo en las consignas

- Enunciado §4.1: el catálogo es "la única fuente local vigente para consultar profesionales y sus
  reglas de agenda".
- Enunciado §7: turnos combina "las reglas semanales vigentes obtenidas del servicio de catálogo"
  con las ocupaciones.
- Enunciado §8: si el catálogo no está disponible, no pueden iniciarse operaciones que requieran
  validar información vigente.
- Enunciado §9: JWT en la comunicación entre servicios, elección entre propagar el del usuario o
  usar uno técnico, validación de firma, vigencia y permisos, trazabilidad.
- Contrato §9 y §13.1: la cátedra valida el slot al crear el hold.
