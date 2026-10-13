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

- Turnos envía un **JWT técnico de servicio** firmado con la clave compartida, con un claim que lo
  distingue de los tokens de usuario (`aud=catalogo-service` o un `iss` propio) y el rol
  `ROLE_SERVICE` (DP-06).

## Flujo principal

1. Turnos pide la agenda vigente de un profesional por su ID. Si la llamada nace de un pedido de
   usuario, envía además el login del usuario en el header `X-User-Login`.
2. El catálogo valida firma, vigencia, el claim de servicio (`aud` o `iss`) y el rol
   `ROLE_SERVICE`.
3. El catálogo registra en los logs el login recibido en `X-User-Login` (si vino) para
   trazabilidad. No lo usa para autorizar ni para filtrar datos.
4. El catálogo lee de la base local el profesional, su categoría y sus horarios semanales
   **habilitados**.
5. El catálogo responde con el profesional (incluido su estado habilitado), su categoría, los
   horarios habilitados (`dayOfWeek`, `startTime`, `endTime`, `slotDurationMinutes`) y la versión
   local del catálogo.

## Flujos alternativos

- **2a. Sin token, token inválido o vencido:** `401`.
- **2b. Token de usuario** (sin el claim de servicio o sin `ROLE_SERVICE`), aunque esté firmado con
  la misma clave: `403`. Un token de usuario nunca sirve para este caso.
- **4a. El profesional no existe en la copia local:** respuesta de "no encontrado" en
  `problem+json`.
- **4b. El profesional está deshabilitado** (baja lógica): se informa que no está habilitado, para
  que turnos no ofrezca ni valide turnos con él.
- **4c. Base local vacía:** `503` (DP-03). Turnos no puede iniciar operaciones que requieran validar
  información vigente.
- **4d. Sincronización en curso:** se responde con la última copia confirmada (DP-03).

La forma concreta de la respuesta y de estos errores (rutas, DTOs y `code`) se define junto con el
contrato con turnos, que todavía es una decisión pendiente.

## Postcondiciones

- **De éxito:** turnos recibe las reglas de agenda vigentes según la versión local informada y
  puede combinarlas con las ocupaciones que obtiene de la cátedra.
- **De fallo:** turnos recibe un error y no inicia la operación que dependía de esa información
  (enunciado §8). El catálogo no cambia de estado.

## Decisiones del proyecto

- **DP-06 (autenticación entre servicios):** turnos se autentica con un **JWT técnico de servicio**
  propio, de vida corta, firmado con la clave compartida. El token lleva un claim que lo distingue
  de los de usuario (`aud=catalogo-service` o un `iss` propio) y el rol `ROLE_SERVICE`; el catálogo
  valida ambos además de firma y vigencia, así que un token de usuario nunca sirve para este caso
  aunque comparta la clave de firma. A la inversa, los endpoints de usuario (CU-05, CU-06, CU-08,
  CU-09) rechazan el token técnico. Cuando la llamada nace de un pedido de usuario, turnos manda
  además el login en `X-User-Login`, **solo para logs y trazabilidad, no para autorizar**. La
  agenda no es un dato propio de ningún usuario, así que no se pierde autorización al no propagar
  el JWT del usuario, y el token técnico también sirve en procesos de turnos que no tienen un
  usuario detrás.
- **DP-03:** durante una reconstrucción se sirve la última copia confirmada. Si la agenda
  informada quedó desactualizada, la cátedra rechaza el hold (`PROFESSIONAL_DISABLED`,
  `INVALID_SLOT`, `SLOT_ALREADY_RESERVED`, `SLOT_ALREADY_HELD`) y turnos lo traduce para el
  usuario: la copia local nunca autoriza una reserva por sí sola.

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
