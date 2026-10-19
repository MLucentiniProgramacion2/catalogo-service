# CU-01: Inicializar el catálogo al arrancar

## Objetivo

Que el servicio, al iniciar, quede con una copia local utilizable y al día sin esperar a que llegue
una notificación de Kafka: si la base está vacía la construye con un snapshot; si ya tiene datos,
verifica que su versión no haya quedado atrás de la publicada por la cátedra.

## Actores

- **Principal:** Arranque del servicio.
- **Secundarios:** API REST de la cátedra (a través de CU-02), Redis de la cátedra (a través de
  CU-03).

## Disparador

El servicio de catálogo termina de iniciar (primer arranque o reinicio).

## Precondiciones

- La base PostgreSQL propia está disponible y las migraciones están aplicadas.
- La configuración de integración (URL de la API, JWT técnico, credenciales de Redis, topic de
  Kafka) está cargada desde variables de entorno.

## Flujo principal

1. El servicio arranca y lee la versión de catálogo guardada localmente.
2. Si **no hay versión local** (base vacía), ejecuta **CU-02 Ejecutar la sincronización
   completa** y continúa en el paso 4.
3. Si **hay versión local**, ejecuta **CU-03 Incorporar cambios del catálogo** con el disparador
   "arranque", que compara la versión local con la de Redis y aplica lo que falte.
4. Registra el resultado en el estado de sincronización (consultable por CU-09).
5. El servicio queda atendiendo búsquedas, consultas de agenda y notificaciones de Kafka.

## Flujos alternativos

- **2a. El snapshot falla** (cátedra no disponible, timeout, credencial inválida): CU-02 termina
  sin cambios y deja el error registrado. El servicio **igual queda levantado**: mientras la base
  esté vacía, las consultas de CU-05, CU-06 y CU-07 responden `503` (DP-03). La comprobación
  periódica de versión (disparador de CU-03, DP-04) vuelve a intentarlo, porque "sin versión
  local" siempre deriva en snapshot.
- **3a. Redis no disponible:** CU-03 agota sus reintentos (DP-08), registra el error y el servicio
  sigue atendiendo con la copia local existente. La comprobación periódica reintentará más tarde.
- **3b. Llega un `CatalogUpdated` o vence la comprobación periódica mientras corre el arranque:**
  la exclusión mutua (DP-09) hace que ese disparador espere; al entrar, vuelve a comparar versiones
  y normalmente ya no tiene nada que aplicar.

## Postcondiciones

- **De éxito:** la base local contiene la copia del catálogo y la versión local coincide con la
  última versión que se pudo verificar contra la cátedra. El estado de sincronización refleja el
  resultado.
- **De fallo:** la copia local queda exactamente como estaba antes del arranque (o vacía si nunca
  hubo snapshot), la versión local no avanzó y el error queda registrado en el estado de
  sincronización. El servicio sigue levantado.

## Decisiones del proyecto

- **DP-03:** con la base vacía, las consultas responden `503`; con datos, se sirve la última
  copia confirmada. Ver CU-05.
- **DP-04:** el arranque con versión local existente no tiene un flujo propio: es uno de los
  disparadores de CU-03, que reutiliza la misma lógica e idempotencia.
- **DP-09:** una sola incorporación de cambios a la vez. Ver CU-03.

## Respaldo en las consignas

- Enunciado §6.1: snapshot "cuando inicialice una base local vacía".
- Enunciado §8: comportamiento correcto ante "reinicio de los servicios con operaciones
  pendientes" e "interrupción temporal de la conexión con el servicio central".
- Contrato §14.2 y §16: la comparación de versiones permite detectar y recuperar el atraso.
