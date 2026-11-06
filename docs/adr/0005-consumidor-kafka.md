# ADR 0005: Consumidor de `CatalogUpdated` con `@KafkaListener`, en un hilo y con commit por mensaje

- **Estado:** aceptada
- **Fecha:** 2026-11-06
- **Relacionada con:** [CU-03](../use-cases/CU-03-incorporar-cambios-del-catalogo.md),
  [DP-07, DP-08 y DP-09](../use-cases/README.md#decisiones-del-proyecto),
  [ADR 0004](0004-cliente-http-catedra.md)

## Contexto

CU-03 recibe `CatalogUpdated` por Kafka. Hay que decidir cómo se deserializa, cuándo se confirma el
offset, qué pasa con lo inesperado y cuánto puede tardar el listener.

Lo que exigen las consignas:

- **Contrato §4, §15.1 y §16:** entrega al menos una vez, campos desconocidos ignorados, offset
  confirmado después de persistir.
- **Contrato §3 y §15.2:** bootstrap y topic externalizados; "consumer group sugerido" en
  `integration`.
- **Contrato §18.4 y enunciado §8:** reintentos limitados y observables.
- **DP-08:** una falla no traba la partición: se confirma el offset, porque el aviso no trae datos.

Lo que condiciona el diseño:

- El listener puede bloquearse minutos: espera la exclusión mutua (DP-09) y corre CU-02.
- El topic real tiene un broker, una partición y una réplica, va en PLAINTEXT, sin credenciales, y
  los mensajes no traen headers (visto con `kcat` el 6/11). El mensaje es de la cátedra: el catálogo
  no controla su formato.
- `src/test/resources/config/application.yml` **tapa** al `application.yml` principal.
- Spring Boot 4.1.1, spring-kafka 4.1.1, kafka-clients 4.2.1, Jackson 3.

Las pruebas se hicieron con un broker real (`apache/kafka-native:4.1.0`). Abajo se distingue qué se
midió de qué sale de leer el código.

## Decisión

### Configuración en Java, con `catedra.kafka.*`

| Propiedad | Variable | Por defecto |
| --- | --- | --- |
| `bootstrap-servers` | `CATEDRA_KAFKA_BOOTSTRAP_SERVERS` | ninguno: sin ella la app no arranca |
| `catalog-topic` | `CATEDRA_KAFKA_CATALOG_TOPIC` | ninguno: sin ella la app no arranca |
| `consumer-group` | `CATEDRA_KAFKA_CONSUMER_GROUP` | ninguno: sin ella la app no arranca |
| `max-poll-interval` | (no) | `15m` |
| `auth-retry-interval` | (no) | `1m` |
| `listener-enabled` | (no) | `true`; solo `src/test` lo apaga |

`CatedraKafkaConfiguration` arma el `ConsumerFactory`, el `DefaultErrorHandler` y el
`kafkaListenerContainerFactory`. Con esos beans, la autoconfiguración de Spring Boot se retira:
no queda un segundo factory, y `KafkaAdmin` no se conecta porque no hay ningún `NewTopic`. Ninguna
propiedad `spring.kafka.*` llega al consumidor. Lo verifica `KafkaCatalogUpdatedIT`, que las define
con valores trampa.

### Un consumer group propio por servicio

`integration` entrega un solo `kafkaConsumerGroupId`, que el contrato llama "sugerido". Se usa uno
por servicio: ese valor con el sufijo `-catalogo` acá y `-turnos` allá. Se probó con `kcat` que la
cátedra acepta otros nombres: un grupo con otro sufijo se unió y recibió la partición sin error de
autorización.

**Motivo, medido:** un rebalanceo espera a todos los miembros del grupo. Con un consumidor bloqueado
30 s en su listener y otro miembro entrando al grupo, el consumidor de **otro topic** del mismo grupo
estuvo **29 s sin recibir ningún mensaje**. No se perdió nada: llegaron todos juntos cuando el
bloqueado volvió. Con grupos separados no hubo ningún corte. Acá el bloqueo puede durar minutos, y
turnos no puede atrasarse tanto: sus holds tienen plazo.

### Mensajes como `String`, parseados en el adaptador

| Opción | Mensaje roto, medido |
| --- | --- |
| `String` + `ConsumerRecord` | **Elegida.** Roto, bytes inválidos, vacío y tombstone llegan al listener; offset confirmado |
| `JacksonJsonDeserializer` solo | **Partición trabada:** listener sin mensajes, offset sin moverse, 162.387 errores en 8 s (142 MB de log) y el contenedor "corriendo" |
| `ErrorHandlingDeserializer` + Jackson | El siguiente se procesa, pero el roto nunca llega al listener: va al recoverer, un segundo camino para el flujo 2a |

Además, esa última opción descartaba como ilegible un mensaje **válido** con un `__TypeId__` ajeno,
salvo con `use.type.headers=false`. El topic real no manda ese header, pero el consumidor no tiene
por qué depender de eso. Con `String`, `trusted.packages` no hace falta.

El parámetro es `ConsumerRecord`: un tombstone con `@Payload String` falla antes de llegar al método.
`ACCEPT_FLOAT_AS_INT` va apagado: Jackson trunca `7.5` a `7` en silencio.

### Commit por mensaje, sin `Acknowledgment`

`AckMode.RECORD`: el contenedor confirma cuando el listener vuelve bien, y todos los flujos esperados
de CU-03 vuelven bien, también los que fallaron. Con `MANUAL` habría un `acknowledge()` por rama:
olvidarse uno traba el offset sin ningún error.

### `DefaultErrorHandler` para lo inesperado

Las fallas esperadas no salen del listener. Lo inesperado (por ejemplo, la base caída en una barrera)
sale como excepción.

| Configuración | Medido |
| --- | --- |
| Por defecto | 10 entregas sin espera, después saltea y confirma |
| Con las esperas de DP-08 (elegida) | 4 entregas a los 0, 1, 3 y 7 s, recoverer y offset confirmado, aunque no haya un mensaje siguiente |

Cada intento queda en el log con un `RetryListener`, y al agotarse `CatalogEventRecoverer` registra
`EVENT_PROCESSING_FAILED` en CU-09.

**El recoverer nunca lanza.** Medido: si lanza, el aviso se vuelve a entregar sin fin (18 entregas en
9 s, offset sin moverse). Si no puede registrar el error, lo deja en el log y el offset se confirma
igual.

### `max.poll.interval.ms` = 15 min y `max.poll.records` = 1

| Tramo | Tiempo |
| --- | --- |
| Primera barrera (Hikari) | 30 s |
| Espera de la exclusión | 360 s |
| Leer la versión (Hikari) | 30 s |
| Peor caso de CU-02 | 297 s |
| Esperas del `DefaultErrorHandler` | 7 s |
| **Cota** | **724 s** |

**Cada reentrega arranca un plazo nuevo.** Medido con un plazo de 5 s: 4 entregas de 2 s con esperas
de 1, 2 y 2 s suman 13 s sin ningún rebalanceo, porque cada reentrega pasa por `poll`. Pero **la
espera cuenta dentro de la ventana** de esa entrega: con 2 s de trabajo y 4 s de espera (6 s) se
venció el plazo. Así que en una ventana cabe una sola espera, 4 s como mucho: el peor caso real son
721 s. Se suman los 7 s como cota, para no depender de ese detalle.

Con 900 s sobran 176 s. `CatedraKafkaPropertiesTest` deja la cuenta en un test. **CU-04 tiene que
recalcularla.**

`max.poll.records=1` hace falta porque el plazo se cuenta por `poll`, no por mensaje. Medido, con un
plazo de 5 s y 3 mensajes de 3 s: con lotes hubo 5 entregas (2 duplicados); de a uno, 3 entregas.

Superar el plazo no pierde nada, pero repite: el consumidor sale del grupo, el commit falla y el
mensaje se reentrega. El heartbeat corre en otro hilo: bloquearse más que `session.timeout` no afecta
(medido: 12 s con 6 s de `session.timeout`, una sola entrega).

### `auto-offset-reset` = `earliest`

Los avisos no traen datos, así que releer el historial cuesta poco: los avisos viejos van "al día", el
primero más nuevo que la copia pide **un** snapshot, y como trae la última versión, el resto también
va "al día". Con `latest`, la copia queda atrasada hasta el próximo aviso, y un offset vencido saltea
lo pendiente. Lo fija `KafkaFirstStartIT`.

### El topic no se declara, ni se crea

No hay `NewTopic`: el topic es de la cátedra. En el Tema 13 se declara porque es propio.

Además va `allow.auto.create.topics=false`. **Medido:** con el valor por defecto, el propio
consumidor crea el topic si no existe, y un nombre mal escrito terminaría creando uno.

### `authExceptionRetryInterval` = 1 min

Sin esto, una falla de autorización **detiene el contenedor para siempre** con la aplicación viva.
Comprobado en el bytecode de spring-kafka 4.1.1 (`ListenerConsumer` marca `fatalError` si el intervalo
es `null`), y medido simulando la excepción en `poll`: `running=false`, y el mensaje siguiente no se
procesó. Con el intervalo se reintenta y se recupera. Lo simulado es la excepción: no se probó contra
un broker con ACL.

### Un solo hilo

`concurrency=1`: DP-09 ya serializa las sincronizaciones y el topic tiene una partición. Tampoco se
saca el trabajo del hilo del listener: habría que confirmar desde otro hilo (`asyncAcks`) y pausar el
consumidor, para ganar poco.

## Consecuencias

- Sin las tres variables, la aplicación no arranca, como con `CATEDRA_JWT`. Un broker caído, en
  cambio, no frena el arranque: el servicio sirve la copia local (DP-03) y Kafka reintenta con WARN.
- Un listener bloqueado tarda más en notarse: hasta 15 min.
- **Apagado:** medido, el listener bloqueado no se interrumpe y `context.close()` espera 30 s (el
  plazo de apagado de Spring Boot). El offset no se confirma y el mensaje se reentrega al volver: las
  barreras cubren el duplicado.
- Los logs de Kafka pueden nombrar el broker (`Connection to node...`): el `logback-spring.xml` deja
  `org.apache` en WARN. Es un host, no una credencial, pero hay que revisarlo antes de publicar logs
  como evidencia.
- Un aviso que falla no se reintenta hasta el próximo: la red de seguridad es la comprobación
  periódica (DP-04), que todavía no existe.

## Alternativas descartadas

- **`spring.kafka.*` en el YAML, como en el Tema 13:** los tests no ejercitarían esos valores, porque
  el YAML de `src/test` tapa al principal. Habría que duplicarlos y mantenerlos iguales a mano.
- **Declarar el `NewTopic`:** el topic no es nuestro.
- **Un grupo compartido con turnos:** ver arriba.
