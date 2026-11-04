package ar.edu.um.programacion2.catalogo.sync.infrastructure.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.catalogo.sync.SyncLockHolder;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * La exclusión mutua con hilos de verdad. Las esperas se coordinan con {@link CountDownLatch} y mirando el estado del
 * hilo, nunca con un {@code sleep}: un test que duerme "lo suficiente" falla o pasa según la carga de la máquina.
 */
class InMemorySyncLockTest {

    private static final Duration LARGA = Duration.ofSeconds(30);
    private static final Duration CORTA = Duration.ofMillis(20);

    @AfterEach
    void limpiarInterrupcion() {
        Thread.interrupted();
    }

    @Test
    void elSegundoHiloEsperaAlPrimeroYDespuesCorre() throws Exception {
        InMemorySyncLock lock = new InMemorySyncLock(LARGA);
        CountDownLatch elSegundoCorrio = new CountDownLatch(1);
        AtomicReference<Optional<String>> resultado = new AtomicReference<>();

        Thread segundo;
        try (SyncLockHolder primero = SyncLockHolder.hold(lock)) {
            segundo = new Thread(() -> {
                try {
                    resultado.set(
                        lock.runExclusively(() -> {
                            elSegundoCorrio.countDown();
                            return "corrió";
                        })
                    );
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            segundo.start();
            esperarEstado(segundo, Thread.State.TIMED_WAITING);

            // Está parado en tryLock con el primero adentro: todavía no corrió.
            assertThat(elSegundoCorrio.getCount()).isEqualTo(1);
            assertThat(lock.isHeld()).isTrue();
        }

        assertThat(elSegundoCorrio.await(10, TimeUnit.SECONDS)).isTrue();
        segundo.join(10_000);
        assertThat(resultado.get()).contains("corrió");
    }

    @Test
    void siVenceLaEsperaNoCorreLaAccionYDevuelveVacio() throws Exception {
        InMemorySyncLock lock = new InMemorySyncLock(CORTA);
        AtomicBoolean corrio = new AtomicBoolean();

        try (SyncLockHolder otra = SyncLockHolder.hold(lock)) {
            Optional<Boolean> resultado = lock.runExclusively(() -> {
                corrio.set(true);
                return true;
            });

            assertThat(resultado).isEmpty();
        }

        assertThat(corrio).isFalse();
    }

    @Test
    void enCursoEsVerdaderoMientrasCorreYFalsoAlTerminar() throws Exception {
        InMemorySyncLock lock = new InMemorySyncLock(LARGA);
        assertThat(lock.isHeld()).isFalse();

        Optional<Boolean> enCurso = lock.runExclusively(lock::isHeld);

        assertThat(enCurso).contains(true);
        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    void siLaAccionLanzaUnaExcepcionSeLiberaIgual() throws Exception {
        InMemorySyncLock lock = new InMemorySyncLock(CORTA);

        assertThatThrownBy(() ->
            lock.runExclusively(() -> {
                throw new IllegalStateException("falló la sincronización");
            })
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("falló la sincronización");

        assertThat(lock.isHeld()).isFalse();
        // Otro hilo la puede tomar: si no se hubiera liberado, a este hilo no le alcanzaría de prueba, porque es reentrante.
        assertThat(CompletableFuture.supplyAsync(() -> tomarDesdeOtroHilo(lock)).get(10, TimeUnit.SECONDS)).contains(true);
    }

    @Test
    void elMismoHiloPuedeEntrarDosVecesYSeLiberaAlSalirDeLaExterna() throws Exception {
        InMemorySyncLock lock = new InMemorySyncLock(CORTA);

        Optional<Optional<Boolean>> resultado = lock.runExclusively(() -> {
            try {
                Optional<Boolean> interna = lock.runExclusively(() -> true);
                // Al salir de la interna sigue tomada: la sincronización de afuera no terminó.
                assertThat(lock.isHeld()).isTrue();
                return interna;
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(resultado).contains(Optional.of(true));
        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    void siInterrumpenLaEsperaNoCorreLaAccion() throws Exception {
        InMemorySyncLock lock = new InMemorySyncLock(LARGA);
        AtomicBoolean corrio = new AtomicBoolean();

        try (SyncLockHolder otra = SyncLockHolder.hold(lock)) {
            Thread.currentThread().interrupt();

            assertThatThrownBy(() ->
                lock.runExclusively(() -> {
                    corrio.set(true);
                    return true;
                })
            ).isInstanceOf(InterruptedException.class);
        }

        assertThat(corrio).isFalse();
    }

    private static Optional<Boolean> tomarDesdeOtroHilo(InMemorySyncLock lock) {
        try {
            return lock.runExclusively(() -> true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static void esperarEstado(Thread hilo, Thread.State estado) throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (hilo.getState() != estado) {
            assertThat(System.nanoTime()).as("el hilo no llegó a %s", estado).isLessThan(limite);
            Thread.onSpinWait();
        }
    }
}
