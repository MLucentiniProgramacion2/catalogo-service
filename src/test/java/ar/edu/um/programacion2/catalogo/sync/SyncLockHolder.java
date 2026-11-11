package ar.edu.um.programacion2.catalogo.sync;

import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncLock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Simula en los tests una sincronización en curso: un hilo <strong>propio</strong> toma la exclusión y la retiene hasta
 * {@link #close()}.
 * <p>
 * Tiene que ser otro hilo: la exclusión es reentrante, así que si la tomara el hilo del test, lo que se prueba pasaría
 * derecho y el test daría verde sin demostrar nada. En los tests de integración el arranque y MockMvc corren en el hilo
 * del test.
 */
public final class SyncLockHolder implements AutoCloseable {

    private static final long MAX_SECONDS = 30;

    private final CountDownLatch taken = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final Thread thread;

    private SyncLockHolder(SyncLock syncLock) {
        thread = new Thread(() -> holdUntilClosed(syncLock), "sincronizacion-simulada");
        thread.start();
    }

    /** Vuelve recién cuando la exclusión ya está tomada. */
    public static SyncLockHolder hold(SyncLock syncLock) {
        SyncLockHolder holder = new SyncLockHolder(syncLock);
        await(holder.taken);
        return holder;
    }

    private void holdUntilClosed(SyncLock syncLock) {
        try {
            syncLock.runExclusively(() -> {
                taken.countDown();
                await(release);
                return true;
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        release.countDown();
        try {
            thread.join(TimeUnit.SECONDS.toMillis(MAX_SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(MAX_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("La sincronización simulada no llegó a tiempo");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
