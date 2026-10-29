# ADR 0004: RestClient sobre el cliente HTTP del JDK para la API de la cátedra

- **Estado:** aceptada
- **Fecha:** 2026-10-27
- **Relacionada con:** [CU-02](../use-cases/CU-02-ejecutar-sincronizacion-completa.md),
  [DP-08](../use-cases/README.md#decisiones-del-proyecto), [ADR 0003](0003-validacion-jwt.md)

## Contexto

CU-02 empieza pidiendo el snapshot completo a la cátedra: `GET /api/synchronization/snapshot` con
`Authorization: Bearer <jwt-tecnico>` (contrato §7). Hay que elegir con qué se hace ese pedido.

Lo que exigen las consignas:

- **Contrato §3 y §4:** la URL y el JWT técnico se externalizan; el JWT vale un año y no puede
  aparecer en el repositorio, los logs ni los errores.
- **Contrato §13:** los errores se deciden por `status` y `code`, nunca por el texto de `detail`.
  Los `401` y `403` de Spring Security pueden venir sin `code`.
- **Contrato §18.4 y enunciado §8:** timeouts definidos, reintentos limitados y observables. Un
  timeout no prueba que la operación haya fallado.
- **CU-02 (flujos 1a, 2a y 3a):** distinguir la cátedra caída o lenta (se reintenta, DP-08), el
  acceso denegado (no se reintenta) y la respuesta inválida (no se aplica nada).

Lo que hay en el proyecto (Spring Boot 4.1, Spring Framework 7):

- `spring-web` trae `RestClient`, `RestTemplate` y las fábricas de requests. **No** está el módulo
  `spring-boot-restclient`, que en Boot 4 es aparte: no hay un `RestClient.Builder`
  autoconfigurado.
- En el classpath no hay Apache HttpClient 5, Jetty ni Reactor Netty. Si alguno aparece (por
  ejemplo, como dependencia transitiva), `RestClient.builder()` lo elige solo.
- El perfil `dev` corre con `ROOT: DEBUG`.

## Decisión

- **`RestClient` de Spring** con la fábrica **`JdkClientHttpRequestFactory`** (el
  `java.net.http.HttpClient` del JDK), fijada de forma explícita en `CatedraClientConfiguration`.
- Configuración en `catedra.api.*` (`CatedraApiProperties`):

  | Propiedad | Variable | Por defecto |
  | --- | --- | --- |
  | `url` | `CATEDRA_API_URL` | ninguno: sin ella la app no arranca |
  | `token` | `CATEDRA_JWT` | ninguno: sin él la app no arranca |
  | `connect-timeout` | `CATEDRA_CONNECT_TIMEOUT` | `5s` |
  | `read-timeout` | `CATEDRA_READ_TIMEOUT` | `30s` |

- **Un solo intento** por llamada. Los reintentos (DP-08) son del caso de uso, porque también
  cubren las fallas de la base.
- **Clasificación de la falla por status**, en tres excepciones del puerto
  (`sync.domain.ports.out`):

  | Situación | Excepción | ¿Transitoria? |
  | --- | --- | --- |
  | timeout, conexión rechazada, corte a mitad del cuerpo, `408`, `429`, `5xx` | `SnapshotSourceUnavailableException` | sí |
  | `401`, `403` | `SnapshotAccessDeniedException` | no |
  | JSON malformado, campo obligatorio ausente, formato inválido, otro status (incluidos `3xx`) | `InvalidSnapshotException` | no |

  `408` y `429` no figuran en la matriz del contrato (§13.1), pero son transitorios por
  definición (el servidor no esperó o pidió bajar el ritmo) y repetir un `GET` sin efectos es
  seguro. El `code` del problem+json va en el mensaje solo para diagnóstico; `detail` nunca se
  usa.

### Detalles que forman parte de la decisión

- **El read timeout es un plazo total.** En Spring 7.0, `JdkClientHttpRequestFactory` aplica el
  read timeout a la respuesta completa, cuerpo incluido (cierra el cuerpo cuando vence), no a la
  inactividad del socket. Por eso el valor por defecto es holgado (30 s): el snapshot puede ser
  grande y la red pasa por ZeroTier.
  - Peor caso con DP-08: 4 intentos × (5 s + 30 s) + 7 s de espera ≈ 2,5 min. Hay que tenerlo en
    cuenta al fijar la espera máxima del lock de DP-09.
- **El cuerpo se lee completo dentro de `exchange` y se parsea aparte**, con un `JsonMapper`
  propio. Así toda falla de I/O, incluso a mitad del cuerpo, llega como `ResourceAccessException`
  (no disponible), y toda falla de formato llega como `JacksonException` (inválida). Leído con
  `.body(...)`, un corte de red y un JSON roto terminan en la misma `RestClientException`.
- **Sin redirecciones** (`Redirect.NEVER`): el token no puede viajar a otro host. Un `3xx` es una
  respuesta inesperada.
- **HTTP/1.1 explícito**: evita que el JDK intente el upgrade a HTTP/2 sin TLS (`h2c`).
- **El token se agrega como cabecera por defecto del `RestClient`**: el adaptador no lo ve.
  `CatedraApiProperties` es una clase (no un record) con `toString()` que lo oculta.

## Alternativas consideradas

| Alternativa | Motivo para descartarla |
| --- | --- |
| **`RestTemplate`** (el que se nombra en el Tema 9) | Funciona, pero Spring lo mantiene sin evolucionar y recomienda `RestClient` para código nuevo. `RestClient` usa las mismas fábricas y conversores con una API fluida, y `exchange` da control sobre status y cuerpo sin configurar un `ResponseErrorHandler`. |
| **`WebClient`** | Es reactivo: necesita `spring-webflux` y Reactor Netty. Este servicio es Spring MVC bloqueante, así que habría que llamar a `.block()` y se sumarían dependencias sin ninguna ventaja. |
| **OpenFeign** (Spring Cloud) | Cliente declarativo cómodo. Spring Cloud ya está en el pom (lo trajo JHipster), pero haría falta sumar `spring-cloud-starter-openfeign` por un solo endpoint. Su logger en nivel `FULL` imprime las cabeceras, `Authorization` incluida. El bloque `feign:` del `application.yml` es un resto de JHipster y no se usa. |
| **Fábrica por defecto de `RestClient.builder()`** | Hoy elige la del JDK, pero cambia sola si aparece Apache HttpClient 5 en el classpath, y ese cliente loguea las cabeceras en DEBUG. |
| **`SimpleClientHttpRequestFactory`** (`HttpURLConnection`) | Su read timeout es de inactividad, que serviría, pero `HttpURLConnection` loguea las cabeceras de la request en nivel `FINE` de `java.util.logging`, que Spring Boot redirige al log de la aplicación. |
| **Apache HttpClient 5, Jetty o Reactor Netty** | Dependencias nuevas que no aportan nada para un `GET` por sincronización. HC5, además, loguea las cabeceras en DEBUG. |
| **Agregar `spring-boot-starter-restclient`** | Daría el `RestClient.Builder` autoconfigurado y métricas de cliente, a cambio de un módulo más. Para un único cliente alcanza con `spring-web`. Se puede sumar si aparecen más clientes. |

## Consecuencias

- **Positivas:**
  - Sin dependencias nuevas.
  - Las tres fallas son tipos distintos del puerto: CU-02 decide reintentar o no con un `switch`
    que el compilador obliga a completar (la jerarquía es `sealed`).
  - Los tests prueban timeouts reales y el servidor apagado con el `HttpServer` del JDK, sin red
    externa.
- **Negativas:**
  - El cuerpo completo queda en memoria dos veces por un momento (bytes y DTO). Es aceptable para
    el tamaño de un catálogo.
  - No hay métricas automáticas de las llamadas salientes (no está `spring-boot-restclient`).
  - El connect timeout no tiene un test automático: necesitaría una IP que no responda, y eso
    depende de la red de quien corre los tests.
- **Seguridad:**
  - El token no aparece en `toString()`, en los mensajes de excepción ni en
    `/management/env` y `/management/configprops` (Boot enmascara los valores). Hay tests para
    los tres casos y se verificó a mano en el log de `dev`, también con los loggers HTTP en TRACE.
  - **Nunca activar `-Djdk.httpclient.HttpClient.log=headers`** (ni `all`) con el token real: es el
    log de diagnóstico del cliente del JDK y **sí** imprime las cabeceras. No depende de los niveles
    de Logback, solo de esa propiedad del sistema.
  - Si el token se filtra, hay que rotarlo según la sección 5.4 del contrato y reemplazarlo en los
    dos servicios.
