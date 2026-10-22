# ADR 0003: El catálogo solo valida el JWT, firmado con HS512 y una clave compartida con turnos

- **Estado:** aceptada
- **Fecha:** 2026-10-21
- **Relacionada con:** [DP-02, DP-05 y DP-06 de los casos de uso](../use-cases/README.md#decisiones-del-proyecto)

## Contexto

Requisitos del enunciado (`PROJECT_STATEMENT-v1.md`) que condicionan la decisión:

- **§3:** los dos servicios se comunican mediante contratos explícitos **protegidos con JWT**, y
  cada uno es propietario exclusivo de sus datos.
- **§3.2:** el usuario final se registra e inicia sesión desde KMP contra el backend del alumno,
  que **emite un JWT** para consumir las APIs protegidas.
- **Seguridad:** hay que validar **firma, vigencia y permisos** de todo token recibido, incluido
  el del otro servicio, externalizar los secretos y documentar las limitaciones de seguridad.

Decisiones ya tomadas que se apoyan en esta:

- El registro y el login viven en el **servicio de turnos**, que emite el JWT del usuario. El
  catálogo no guarda usuarios ni contraseñas.
- **DP-02:** la app KMP consulta al catálogo directamente con el JWT de usuario emitido por turnos.
- **DP-05:** los permisos sobre la sincronización se deciden por rol (`ROLE_ADMIN`).
- **DP-06:** turnos llama al catálogo con un JWT técnico de servicio (`aud=catalogo-service`,
  `iss=turnos-service`, `ROLE_SERVICE`).

El profesor aprobó usar JHipster solo para la base (seguridad, JWT y configuración). Hay que
elegir cómo genera JHipster este servicio y cómo valida los tokens que emite turnos.

## Decisión

- El catálogo **no emite tokens: solo los valida.** Es un *resource server* de Spring Security
  que verifica la **firma** (HS512) y la **vigencia** (`exp`) de cada token y toma la identidad y
  los roles del propio token (claims `sub` y `auth`), nunca de datos enviados por el cliente.
- La clave de firma es un **secreto simétrico compartido** con turnos. Se lee de la variable de
  entorno `JWT_BASE64_SECRET` (en `.env`, que no se versiona), y ambos servicios usan
  **exactamente el mismo valor**. Se genera con `openssl rand -base64 64` (512 bits, lo que pide
  HS512).
- En dev y prod la propiedad `jhipster.security.authentication.jwt.base64-secret` no tiene valor
  por defecto: si la variable falta o está vacía, **la aplicación no arranca**. Solo el perfil de
  test tiene una clave fija, falsa y pública a propósito.
- La base se genera con JHipster como **microservicio** sin gestión de usuarios ni *service
  discovery*.

### Por qué microservicio y no monolito

- Un **monolito** JHipster con JWT genera la gestión de usuarios completa: tablas `jhi_user` y
  `jhi_authority`, registro, login, cuentas y el endpoint que **emite** tokens. Todo eso es
  responsabilidad de turnos: acá habría que borrarlo o desactivarlo, y quedarían tablas de
  usuarios en una base que no es su dueña (§3).
- Un **microservicio** JHipster está pensado justamente para validar tokens que emite otro
  componente. Trae la configuración de Spring Security como *resource server*, sin usuarios ni
  login, que es lo que necesita el catálogo.
- No se usa *gateway* ni *service discovery* (Consul/Eureka): la app KMP llama directo al
  catálogo (DP-02), y para dos servicios agregaría infraestructura sin aportar a los requisitos.

## Alternativas consideradas

| Alternativa | Motivo para descartarla |
| --- | --- |
| **Firma asimétrica (RS256):** turnos firma con la clave privada y el catálogo valida con la pública. | Es más segura: el catálogo no tendría ningún secreto con el que **fabricar** tokens, y filtrar la clave pública no compromete nada. Pero JHipster no la trae lista: habría que reemplazar `SecurityJwtConfiguration` en los dos servicios, generar y distribuir el par de claves y, para rotarlas bien, publicar un JWKS. Se descarta por costo y queda como **limitación documentada** (ver Consecuencias). |
| **Monolito JHipster** | Genera registro, login y tablas de usuarios que pertenecen a turnos (ver arriba). |
| **El catálogo consulta a turnos para validar cada token** (*introspection*) | Agrega una llamada de red por request y acopla la disponibilidad del catálogo a la de turnos. El JWT existe justamente para validarse de forma local. |

## Consecuencias

- **Positivas:**
  - Validación local y sin estado: el catálogo no consulta a turnos para autenticar.
  - Es la configuración que JHipster genera y prueba (`SecurityJwtConfiguration`,
    `TokenAuthenticationIT`), así que se escribe poco código propio de seguridad.
  - Si falta la clave o está vacía, la app no arranca, en vez de levantar con una clave de
    ejemplo.
- **Negativas:**
  - Con HS512 la misma clave **firma y valida**. Cualquiera que tenga la clave, incluido el
    propio catálogo, puede fabricar tokens válidos de **cualquier usuario y con cualquier rol**
    (`ROLE_ADMIN`, `ROLE_SERVICE`). JHipster incluso deja definido un `JwtEncoder` en
    `SecurityJwtConfiguration`, aunque el catálogo no lo usa.
  - Rotar la clave exige cambiarla a la vez en los dos servicios, y los tokens emitidos con la
    clave anterior dejan de validar.
- **Seguridad:**
  - Si la clave se filtra, se pueden fabricar tokens de cualquier usuario. Por eso vive solo en
    el `.env` de cada servicio: nunca en el código, en los `application-*.yml` versionados, en
    los tests, en los logs ni en issues o PRs. Si se filtra, hay que generar una nueva y
    reemplazarla en los dos servicios.
  - **Relación con DP-06:** el token técnico de servicio que manda turnos **se firma con la
    misma clave** que los tokens de usuario. La firma solo prueba que lo emitió alguien que
    tiene la clave, no qué tipo de token es. Por eso el endpoint de agenda (CU-07) exige además
    `aud=catalogo-service`, `iss=turnos-service` y `ROLE_SERVICE`, y los endpoints de usuario
    rechazan el token técnico. Esas reglas de autorización se implementan con CU-07, no en esta
    base.
