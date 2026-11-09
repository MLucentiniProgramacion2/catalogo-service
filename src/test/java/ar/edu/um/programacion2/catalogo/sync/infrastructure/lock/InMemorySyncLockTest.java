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
import java.util.concurrent.locks.ReentrantLock;
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

    @Test
    void sinEsperarConLaExclusionLibreCorre() throws Exception {
        InMemorySyncLock lock = new InMemorySyncLock(LARGA);

        assertThat(lock.tryRunExclusively(lock::isHeld)).contains(true);
        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    void sinEsperarConOtraEnCursoVuelveEnseguidaSinCorrer() throws Exception {
        // Con la espera larga: si tryRunExclusively esperara, el test quedaría parado 30 s y lo diría el tiempo.
        InMemorySyncLock lock = new InMemorySyncLock(LARGA);
        AtomicBoolean corrio = new AtomicBoolean();

        try (SyncLockHolder otra = SyncLockHolder.hold(lock)) {
            long antes = System.nanoTime();
            Optional<Boolean> resultado = lock.tryRunExclusively(() -> {
                corrio.set(true);
                return true;
            });

            assertThat(resultado).isEmpty();
            assertThat(Duration.ofNanos(System.nanoTime() - antes)).isLessThan(Duration.ofSeconds(5));
        }

        assertThat(corrio).isFalse();
    }

    @Test
    void sinEsperarRespetaElOrdenDeLosQueEsperan() throws Exception {
        // tryLock() se mete delante de la cola aunque el lock sea justo (Javadoc de ReentrantLock); tryLock(0, ...) no. La
        // diferencia solo se ve en el instante entre que uno suelta y el siguiente toma, así que se verifica qué se llama.
        RegistraComoSeToma registro = new RegistraComoSeToma();
        InMemorySyncLock lock = new InMemorySyncLock(LARGA, registro);

        lock.tryRunExclusively(() -> true);

        assertThat(registro.sinPlazo).isZero();
        assertThat(registro.conPlazoCero).isEqualTo(1);
    }

    @Test
    void elLockTieneQueSerJusto() {
        assertThatThrownBy(() -> new InMemorySyncLock(LARGA, new ReentrantLock(false))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sinEsperarElMismoHiloEntraDentroDeLaExterna() throws Exception {
        InMemorySyncLock lock = new InMemorySyncLock(CORTA);

        Optional<Optional<Boolean>> resultado = lock.runExclusively(() -> {
            try {
                return lock.tryRunExclusively(() -> true);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(resultado).contains(Optional.of(true));
    }

    @Test
    void sinEsperarConElHiloInterrumpidoNoCorre() {
        InMemorySyncLock lock = new InMemorySyncLock(LARGA);
        AtomicBoolean corrio = new AtomicBoolean();
        Thread.currentThread().interrupt();

        assertThatThrownBy(() ->
            lock.tryRunExclusively(() -> {
                corrio.set(true);
                return true;
            })
        ).isInstanceOf(InterruptedException.class);
        assertThat(corrio).isFalse();
    }

    @Test
    void sinEsperarSiLaAccionLanzaSeLiberaIgual() {
        InMemorySyncLock lock = new InMemorySyncLock(CORTA);

        assertThatThrownBy(() ->
            lock.tryRunExclusively(() -> {
                throw new IllegalStateException("falló");
            })
        ).isInstanceOf(IllegalStateException.class);
        assertThat(lock.isHeld()).isFalse();
    }

    /** Un lock justo que cuenta cómo lo intentan tomar. */
    private static final class RegistraComoSeToma extends ReentrantLock {

        private int sinPlazo;
        private int conPlazoCero;

        RegistraComoSeToma() {
            super(true);
        }

        @Override
        public boolean tryLock() {
            sinPlazo++;
            return super.tryLock();
        }

        @Override
        public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
            if (timeout == 0) {
                conPlazoCero++;
            }
            return super.tryLock(timeout, unit);
        }
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
