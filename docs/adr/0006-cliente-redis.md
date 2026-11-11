# ADR 0006: Spring Data Redis sobre Lettuce, sin la autoconfiguración de Spring Boot, para leer el Redis de la cátedra

- **Estado:** aceptada
- **Fecha:** 2026-11-07
- **Relacionada con:** [CU-03](../use-cases/CU-03-incorporar-cambios-del-catalogo.md),
  [CU-04](../use-cases/CU-04-aplicar-sincronizacion-incremental.md),
  [DP-08 y DP-09](../use-cases/README.md#decisiones-del-proyecto), [ADR 0004](0004-cliente-http-catedra.md),
  [ADR 0005](0005-consumidor-kafka.md)

## Contexto

CU-03 y CU-04 leen de Redis la metadata, los `changes:{v}` y las entidades vigentes (contrato §14). Hay que elegir
el cliente y cómo se configura.

Lo que exigen las consignas:

- **Contrato §3 y §14.1:** host, puerto, usuario y contraseña externalizados. La ACL de la cuenta técnica permite leer
  `catedra:sync:*` y leer y escribir solo `alumnos:{groupId}:*`.
- **Contrato §18.4 y enunciado §8:** timeouts definidos y reintentos limitados y observables.
- **Enunciado §9:** la contraseña no puede aparecer en logs, errores ni respuestas.

Lo que se vio en el Redis real (7/11, con `redis-cli` y el usuario del proyecto):

- Redis **7.4.11**, standalone. `HELLO 3` funciona.
- Funcionan `GET`, `MGET`, `HGET`, `HMGET`, `HGETALL`, `HKEYS`, `HLEN`, `EXISTS`, `TYPE` y `TTL`.
- **`NOPERM`:** `PING`, `ECHO`, `INFO`, `CLIENT SETNAME` y `CLIENT SETINFO`.
- La latencia por ZeroTier es de **200 a 290 ms por viaje**, y hay microcortes (`No route to host` durante un minuto).

Lo que hay en el proyecto: Spring Boot 4.1.1, que trae Spring Data Redis 4.1.1 y Lettuce 7.5.2.
`src/test/resources/config/application.yml` tapa al principal.

Las pruebas se hicieron con Redis 7.4 en Docker y un usuario con la misma ACL que la cuenta técnica (`RedisTestServer`
la reproduce en los tests). Abajo se distingue qué se midió de qué sale de leer el código.

## Decisión

### `spring-data-redis` + `lettuce-core`, sin `spring-boot-starter-data-redis`

Las dos dependencias van sueltas, con las versiones del BOM de Spring Boot. El adaptador usa `StringRedisTemplate` (el
`RedisTemplate` nombrado en el Tema 9) sobre un `LettuceConnectionFactory` armado en Java por
`CatedraRedisConfiguration`. El JSON lo parsea el adaptador, como el mensaje de Kafka en el ADR 0005.

**Por qué sin el starter, medido:** toda la autoconfiguración de Redis de Spring Boot 4 vive en el módulo
`spring-boot-data-redis`, que solo trae el starter. Con ese módulo:

| Situación | Resultado |
| --- | --- |
| Sin un `RedisConnectionFactory` propio | Arma `redisConnectionFactory` a **`localhost:6379`** |
| Con uno propio | Igual crea `redisTemplate` y `stringRedisTemplate`, y activa los **repositorios de Redis** (`redisKeyValueAdapter` y `redisKeyValueTemplate`) |
| Health indicator | `redisHealthContributor` manda **`INFO`**: la ACL lo niega (`NOPERM`) y queda en **DOWN**, así que `/management/health` también. Además, cada consulta de salud le pegaría a la cátedra |

Sin el módulo no existe ninguna de las tres cosas, y `spring.data.redis.*` no se lee. `CatedraRedisExposureIT` lo fija
con propiedades `spring.data.redis.*` trampa, y se pone en rojo si se agrega el starter (comprobado).

### Configuración en Java, con `catedra.redis.*`

| Propiedad | Variable | Por defecto |
| --- | --- | --- |
| `host` | `CATEDRA_REDIS_HOST` | ninguno: sin ella la app no arranca |
| `port` | `CATEDRA_REDIS_PORT` | ninguno |
| `username` | `CATEDRA_REDIS_USERNAME` | ninguno |
| `password` | `CATEDRA_REDIS_PASSWORD` | ninguno |
| `connect-timeout` | `CATEDRA_REDIS_CONNECT_TIMEOUT` | `5s` |
| `command-timeout` | `CATEDRA_REDIS_COMMAND_TIMEOUT` | `3s` |

- **Conexión, 5 s:** es el mismo camino por ZeroTier que la API REST, así que va el mismo valor que
  `catedra.api.connect-timeout` (unas 17 veces los 290 ms del peor viaje).
- **Comando, 3 s:** las respuestas pesan unos pocos KB, así que cada comando es un viaje. 3 s son unas 10 veces el peor
  viaje medido. Rige también para los comandos del saludo (`HELLO` y `CLIENT SETINFO`).
- **Peor caso de un intento, 14 s:** conexión (5) + `HELLO` (3) + `CLIENT SETINFO` (3) + el comando (3). Es lo que
  usan la espera de la exclusión (DP-09) y `max.poll.interval.ms`.

### Lo que hace Lettuce, medido

| Prueba | Resultado |
| --- | --- |
| Qué manda al conectar | `HELLO 3 AUTH <usuario> <contraseña>` (permitido) y después dos `CLIENT SETINFO` (`lib-name` y `lib-ver`), que la ACL niega. **No manda `PING`** ni `CLIENT SETNAME` |
| El `NOPERM` del `SETINFO` | **No corta la conexión:** Lettuce lo ignora y lo deja en DEBUG ("Error applying connection metadata"). Visto también en el bytecode (`RedisHandshake.applyConnectionMetadataSafely`) |
| Cuántas conexiones | **Una sola**, compartida por todas las lecturas (`shareNativeConnection`, el valor por defecto). 20 lecturas seguidas: una conexión y un solo saludo |
| Corte de la conexión | La vuelve a abrir sola y repite el saludo, una vez |
| Puerto cerrado | Falla enseguida: 484 ms el primer intento y unos 3 ms los siguientes |
| IP que no responde | Falla al vencer el connect timeout: 2,0 s con 2 s configurados |
| Redis que deja de responder con la conexión abierta (`docker pause`) | Falla al vencer el command timeout (2,0 s con 2 s configurados) y después se recupera |
| Conexión cerrada (`docker stop`), comportamiento por defecto | Cada comando **espera en cola hasta su timeout** (2,0 s) mientras reconecta en segundo plano |
| Lo mismo con `REJECT_COMMANDS` | Cada comando falla en 0 ms mientras reconecta. Reconectó a menos de 1 s de que volviera Redis |
| Lectura local | 1 a 5 ms. La primera tarda unos 500 ms: abre la conexión y arranca Netty |

De ahí salen tres decisiones:

- **La conexión se abre con la primera lectura**, no al arrancar: un Redis caído no frena el arranque y el servicio
  sirve la copia local (DP-03).
- **Se deja el comportamiento por defecto con la conexión cortada** (encolar hasta el timeout). El peor caso cuenta el
  timeout en cada intento con las dos opciones, pero encolando, 4 intentos de 3 s más las esperas de 1, 2 y 4 s aguantan
  un corte de unos 19 s. Rechazando, unos 7 s.
- **El `CLIENT SETINFO` no se puede apagar con Spring Data Redis:** `LettuceConnectionFactory` arma el `RedisURI` con
  `DriverInfo` y el nombre `spring-data-redis`, sin forma de configurarlo (visto en el bytecode de
  `createRedisURIAndApplySettings`).

### Una entrada en el ACL LOG de la cátedra por conexión

Cada conexión deja en el `ACL LOG` del Redis de la cátedra una negación de `client|setinfo`. Redis agrupa las negaciones
iguales en una entrada y suma `count`: **2 por conexión** (`lib-name` y `lib-ver`), nunca una por comando. Como la
conexión es una sola, solo crece al arrancar y en cada reconexión. Lo fija
`CatedraRedisClientIT.hayUnaSolaConexionYElSetinfoQuedaUnaVezPorConexionNoPorComando`: 20 lecturas dan `count` 2, y
después de cortar la conexión y 20 lecturas más, 4.

Es inofensivo (la ACL de la cátedra lo niega a propósito y cualquier cliente moderno lo manda), pero conviene saberlo
si el profesor mira ese log.

### Solo lectura

El adaptador solo usa `HMGET` y `MGET`. Los tests usan un usuario con la ACL de la cátedra: un `SET` en
`catedra:sync:*` falla con `NOPERM` (`escribirEnElNamespaceDeLaCatedraFallaPorLaAcl`).

## Alternativas consideradas

| Alternativa | Motivo para descartarla |
| --- | --- |
| **`spring-boot-starter-data-redis`** | Las tres trampas medidas arriba. Se podrían apagar una por una (`management.health.redis.enabled`, `spring.data.redis.repositories.enabled`, un bean propio), pero en el YAML de `src/test` y no en el principal, que es el que corre en dev |
| **Lettuce directo** (`RedisClient`) | Funciona igual, pero se pierde `StringRedisTemplate` y la traducción a `DataAccessException`. No se midió si `RedisURI` permite apagar el `CLIENT SETINFO` |
| **Jedis** | Jedis tiene una opción para no mandar `CLIENT SETINFO`, pero **no se midió** ni se verificó si `JedisConnectionFactory` la expone. Necesita un pool para no abrir una conexión por comando |
| **`REJECT_COMMANDS`** | Falla enseguida mientras reconecta: con el mismo peor caso, aguanta cortes más cortos |
| **`spring.data.redis.*` en el YAML** | Los tests no lo ejercitarían, por el YAML de `src/test`. Además, sin el starter no hay nadie que lo lea |

## Consecuencias

- Sin las cuatro variables, la aplicación no arranca, como con `CATEDRA_JWT` y Kafka. Un Redis caído, en cambio, no frena
  el arranque.
- No hay health de Redis ni métricas automáticas del cliente: el estado de la sincronización se ve en CU-09.
- **Seguridad:**
  - La contraseña no aparece en `toString()`, en `/management/env` ni en `/management/configprops` (Boot enmascara los
    valores), ni en los mensajes de error con una contraseña equivocada. Hay tests para los cuatro casos.
  - **Nunca subir `io.lettuce.core.protocol` a TRACE** con la contraseña real. Medido: en TRACE, `CommandEncoder` imprime
    los bytes del `HELLO 3 AUTH <usuario> <contraseña>`. En DEBUG (el `ROOT` del perfil `dev`) no aparece.
    `logback-spring.xml` deja `io.lettuce` en WARN. Una propiedad `logging.level.io.lettuce=TRACE` lo pisaría.
  - Los logs de Lettuce pueden nombrar el host (`Cannot reconnect to [host:puerto]`). Es un host, no una credencial, pero
    hay que revisarlo antes de publicar logs como evidencia, como con Kafka.
- La entrada en el ACL LOG de la cátedra por cada conexión (arriba).
