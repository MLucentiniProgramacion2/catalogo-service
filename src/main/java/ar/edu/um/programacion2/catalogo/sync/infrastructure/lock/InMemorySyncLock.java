package ar.edu.um.programacion2.catalogo.sync.infrastructure.lock;

import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncLock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Adaptador de salida de {@link SyncLock} en la memoria del proceso, con un {@link ReentrantLock}. Supone una sola
 * instancia del servicio (CU-09): al reiniciar, la exclusión desaparece junto con la sincronización que la tenía.
 * <p>
 * {@code tryLock} con plazo y no {@code lock}: una espera sin límite dejaría a los disparadores colgados (DP-09).
 * {@code ReentrantLock} y no {@code Semaphore}: un semáforo no sabe de quién es el permiso, así que una sincronización
 * que incluye a otra se esperaría a sí misma hasta dar un {@code LOCK_TIMEOUT} falso. {@code synchronized} no tiene plazo.
 * <p>
 * Justo ({@code true}): los que esperan entran por orden de llegada. {@link ReentrantLock#isLocked()} está pensado para
 * esto, para observar el estado sin tomar el lock; no sirve para decidir si después se va a poder tomar.
 */
public class InMemorySyncLock implements SyncLock {

    private final ReentrantLock lock = new ReentrantLock(true);
    private final Duration waitTimeout;

    public InMemorySyncLock(Duration waitTimeout) {
        this.waitTimeout = Objects.requireNonNull(waitTimeout, "Falta la espera máxima");
    }

    @Override
    public <T> Optional<T> runExclusively(Supplier<T> action) throws InterruptedException {
        if (!lock.tryLock(waitTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
            return Optional.empty();
        }
        try {
            return Optional.of(action.get());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isHeld() {
        return lock.isLocked();
    }
}
