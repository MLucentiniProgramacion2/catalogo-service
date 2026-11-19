# ADR 0007: Autorización del token técnico de servicio y vigencia obligatoria

- **Estado:** aceptada
- **Fecha:** 2026-11-09
- **Relacionada con:** [DP-06](../use-cases/README.md#decisiones-del-proyecto),
  [CU-07](../use-cases/CU-07-consultar-agenda-vigente.md), [ADR 0003](0003-validacion-jwt.md) y DPT-16
  del servicio de turnos

## Contexto

Turnos llama a la agenda vigente del catálogo (CU-07) con un **JWT técnico de servicio** propio (DP-06 y
DPT-16): `sub` e `iss` `turnos-service`, `aud` `catalogo-service`, `auth` `ROLE_SERVICE` y 5 minutos de
vida. Se firma con la **misma clave HS512** que los tokens de usuario (ADR 0003), así que la firma solo
prueba que lo emitió alguien que tiene la clave, no qué clase de token es.

DP-06 fija tres reglas:

1. El endpoint de agenda exige firma, vigencia, `ROLE_SERVICE`, un `aud` que contenga `catalogo-service`
   e `iss = turnos-service`. Si falta cualquiera, `403`, no `401`.
2. Es una regla de **autorización del endpoint**, no de la validación general del token: los tokens de
   usuario no traen `aud` y no se tocan.
3. Los endpoints de usuario (CU-05, CU-06, CU-08 y CU-09) rechazan el token técnico.

Mediciones del #35 (Spring Security 7.1.1):

- `Jwt.getIssuer()` devuelve una `URL` y **falla** con `iss=turnos-service`: hay que leerlo con
  `getClaimAsString("iss")`.
- Un `aud` de un solo valor llega como lista, así que "contiene" sirve igual.
- **El validador por defecto (`JwtTimestampValidator`) deja pasar un token sin `exp`**, que no vencería
  nunca. Vale para los tokens de usuario y para el de servicio. El enunciado (§9) pide validar la
  vigencia de todo token recibido.

## Decisión

### La regla de los endpoints de servicio

- Prefijo propio **`/api/service/**`** para los endpoints de servicio, con su regla **antes** de
  `/api/admin/**` y `/api/**` en `SecurityConfiguration`: Spring usa la primera que coincide. La agenda es
  `GET /api/service/professionals/{id}/agenda`.
- La regla es un **`AuthorizationManager`** propio en `.access(...)`
  (`security/ServiceTokenAuthorizationManager`). Pasa solo si el token, ya validado por el decoder:
  - trae `ROLE_SERVICE` y **ningún rol de usuario** (`ROLE_USER`, `ROLE_ADMIN`);
  - trae un `aud` que contiene `catalogo-service`;
  - trae `iss` igual a `turnos-service`;
  - es **de vida corta**: trae `iat` y `exp`, `exp - iat` es de 5 minutos como máximo, e `iat` está a lo
    sumo 60 s en el futuro (la misma tolerancia que usa el decoder para `exp`). Sin esta última condición,
    un token con `iat` dentro de un año y `exp` 5 minutos después cumpliría la diferencia y serviría todo
    ese tiempo. Para `exp - iat` no hace falta margen: los dos los pone el mismo reloj, el de turnos.
- Si falta cualquiera, el pedido está autenticado pero no autorizado: **`403`**. Sin token, con la firma
  inválida o vencido, el decoder responde **`401`** antes de llegar a la regla.
- Los valores (`catalogo-service`, `turnos-service` y los 5 minutos) son **constantes**, no propiedades:
  son las identidades del contrato entre los dos servicios propios, no datos del despliegue. Cambiarlos
  exige cambiar los dos servicios y este ADR; como propiedad, solo sumarían una forma de configurarlo mal.
- **Turnos:** DPT-16 hace configurable la vida del token, pero el valor configurado **no puede superar los
  5 minutos**. Si lo supera, el catálogo responde `403` a cada pedido de agenda. Es a propósito: falla
  cerrado y se ve enseguida.
- El reloj de la regla es propio (`Clock.systemUTC()`), no el de la sincronización: algunos tests lo
  reemplazan por uno fijo, y la vigencia se compara con la hora real.

### Las reglas de usuario rechazan cualquier token de servicio

Lo que marca un token como de servicio es **`ROLE_SERVICE`**, no el `aud`: un token de servicio mal
armado puede no traer `aud`. Las reglas de usuario (`/api/admin/**`, `/api/**`, `/v3/api-docs/**` y
`/management/**`) piden el rol que corresponde **y que no esté `ROLE_SERVICE`**
(`AuthorizationManagers.allOf(hasAnyAuthority(...), not(hasAuthority(ROLE_SERVICE)))`, en
`security/TokenAuthorizations`). Un token mixto (`ROLE_SERVICE` y `ROLE_USER`) da `403` en todos lados.

### `exp` obligatorio en todos los tokens

`SecurityJwtConfiguration` suma al decoder, además de los validadores por defecto, un `JwtClaimValidator`
que exige `exp`. Un token sin `exp` responde **`401`**, igual que uno vencido, sea de usuario o de
servicio. Lo cuenta el mismo contador de tokens vencidos. Los tokens que emite JHipster (y turnos)
siempre traen `exp`, así que no cambia nada para ellos.

## Alternativas descartadas

| Alternativa | Motivo |
| --- | --- |
| Validadores de `aud` e `iss` en el `JwtDecoder` | Dan `401`, no `403`, y rechazarían los tokens de usuario, que no traen `aud`: DP-06 dice que no es validación general. |
| Una segunda `SecurityFilterChain` con `securityMatcher("/api/service/**")` y su propio decoder | Duplica csrf, sesión, manejo de errores y `oauth2ResourceServer`. Su atractivo es un decoder con validadores propios, que es justamente lo que da `401`. |
| `@PreAuthorize` en el controller | La ruta igual necesita una regla antes de `/api/**`, así que la regla queda partida en dos lugares. Un endpoint de servicio nuevo sin la anotación quedaría abierto a cualquier token. |
| `aud`, `iss` y la vida máxima como propiedades | Ver arriba: no son configuración de despliegue. |
| No exigir la vida corta | Con HS512, cualquiera que tenga la clave puede firmar un token de servicio de un año; y un token de servicio filtrado (por ejemplo en un log de turnos) serviría todo ese tiempo. |
| Identificar el token de servicio por `aud` | Un token de servicio sin `aud` pasaría por los endpoints de usuario si además trae `ROLE_USER`. |
| `JwtTimestampValidator.setAllowEmptyExpiryClaim(false)` en lugar de un `JwtClaimValidator` | Habría que reemplazar el validador por defecto de `JwtValidators.createDefault()` en vez de sumarle uno, y perder lo que traiga en futuras versiones. |

## Consecuencias

- **Positivas:**
  - La regla de servicio está en una sola clase con un unitario por condición, y en una sola línea de
    `SecurityConfiguration`.
  - Un token de usuario nunca sirve para la agenda, y uno de servicio nunca sirve para los endpoints de
    usuario, aunque compartan la clave.
  - Ningún token sin `exp` pasa.
- **Negativas:**
  - Con HS512 la vida corta acota un **token** filtrado, no una **clave** filtrada: con la clave se puede
    firmar un token de 5 minutos nuevo en cada pedido. Sigue siendo la limitación documentada del
    ADR 0003; la salida es RS256, descartada por costo.
  - Si turnos configura una vida mayor a 5 minutos, la agenda falla con `403` hasta que se corrija.
  - `/api/service/**` es un prefijo reservado: un endpoint de usuario no puede ir ahí.
