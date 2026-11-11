package ar.edu.um.programacion2.catalogo.sync;

import ar.edu.um.programacion2.catalogo.sync.infrastructure.client.CatedraApiProperties;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.config.SyncProperties;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.redis.CatedraRedisProperties;
import com.zaxxer.hikari.HikariConfig;
import java.time.Duration;
import java.util.List;

/**
 * El peor caso de una sincronización con la exclusión tomada, armado con los valores por defecto reales de la
 * configuración, no con números escritos a mano: si cambia un timeout, cambia la cuenta y los tests que la usan lo
 * notan. Es la tabla de DP-09 y del ADR 0005.
 * <p>
 * Supone lo mismo que las cuentas anteriores: cada paso tarda su timeout entero sin llegar a fallar, o falla al
 * vencerlo; una sentencia colgada en la base no tiene tope (#29) y queda afuera.
 */
public final class SyncWorstCase {

    private final SyncProperties sync = new SyncProperties();
    private final CatedraRedisProperties redis = new CatedraRedisProperties();
    private final CatedraApiProperties api = new CatedraApiProperties();

    /** El {@code connection-timeout} de Hikari: el proyecto no lo cambia, así que es el valor por defecto. */
    public Duration hikari() {
        return Duration.ofMillis(new HikariConfig().getConnectionTimeout());
    }

    /** Un intento contra Redis: conexión, {@code HELLO}, {@code CLIENT SETINFO} y el comando (ADR 0006). */
    public Duration redisAttempt() {
        return redis.getConnectTimeout().plus(redis.getCommandTimeout().multipliedBy(3));
    }

    private List<Duration> delays() {
        return sync.getRetry().getDelays();
    }

    public Duration retryDelays() {
        return delays().stream().reduce(Duration.ZERO, Duration::plus);
    }

    private Duration longestDelay() {
        return delays().stream().max(Duration::compareTo).orElse(Duration.ZERO);
    }

    /** CU-02: 4 intentos de conexión + respuesta de la API + Hikari, las esperas y registrar el error (DP-09, #29). */
    public Duration fullSync() {
        int attempts = delays().size() + 1;
        Duration attempt = api.getConnectTimeout().plus(api.getReadTimeout()).plus(hikari());
        return attempt.multipliedBy(attempts).plus(retryDelays()).plus(hikari());
    }

    /**
     * CU-03 con la exclusión tomada, con versión local:
     * <ol>
     * <li>leer la versión local (Hikari);</li>
     * <li>la lectura previa: metadata, {@code remoteVersion} y cambios, y sus reintentos, cada uno lo más largo entre un
     * intento de Redis y Hikari, con sus esperas;</li>
     * <li>CU-04 hasta el plazo, y la versión en curso al vencer (la espera más larga, 3 {@code HMGET} y su
     * transacción);</li>
     * <li>CU-02 después de una discontinuidad o del plazo, que ya incluye registrar su error.</li>
     * </ol>
     */
    public Duration incorporateChanges() {
        Duration readLocal = hikari();
        Duration prePhase = redisAttempt().plus(hikari()).plus(redisAttempt());
        Duration slowestStep = redisAttempt().compareTo(hikari()) > 0 ? redisAttempt() : hikari();
        Duration prePhaseRetries = slowestStep.multipliedBy(delays().size()).plus(retryDelays());
        Duration batch = sync.getIncremental().getTimeBudget();
        Duration versionInFlight = longestDelay().plus(redisAttempt().multipliedBy(3)).plus(hikari());
        return readLocal.plus(prePhase).plus(prePhaseRetries).plus(batch).plus(versionInFlight).plus(fullSync());
    }

    /** Cuánto puede esperar un disparador la exclusión, por defecto. */
    public Duration lockWait() {
        return sync.getLock().getWaitTimeout();
    }

    /**
     * Una entrega de Kafka (ADR 0005): la primera barrera (Hikari), la espera de la exclusión, CU-03 entero y las esperas
     * del {@code DefaultErrorHandler}.
     */
    public Duration kafkaDelivery() {
        return hikari().plus(lockWait()).plus(incorporateChanges()).plus(retryDelays());
    }
}
