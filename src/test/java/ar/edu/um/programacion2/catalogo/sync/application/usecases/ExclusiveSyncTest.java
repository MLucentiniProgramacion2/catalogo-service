package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.catalogo.sync.SyncLockHolder;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncLock;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.lock.InMemorySyncLock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExclusiveSyncTest {

    @Mock
    private SyncLock syncLock;

    @Mock
    private SyncErrorRecorder syncErrorRecorder;

    @AfterEach
    void limpiarInterrupcion() {
        Thread.interrupted();
    }

    @Test
    void conLaExclusionLibreCorreYDevuelveElResultadoSinRegistrarNada() {
        ExclusiveSync exclusiveSync = new ExclusiveSync(new InMemorySyncLock(Duration.ofMillis(20)), syncErrorRecorder);

        assertThat(exclusiveSync.run(SyncTrigger.MANUAL, () -> 7L)).contains(7L);

        verifyNoInteractions(syncErrorRecorder);
    }

    @Test
    void siVenceLaEsperaNoCorreYRegistraLockTimeout() {
        InMemorySyncLock lock = new InMemorySyncLock(Duration.ofMillis(20));
        ExclusiveSync exclusiveSync = new ExclusiveSync(lock, syncErrorRecorder);
        AtomicBoolean corrio = new AtomicBoolean();

        Optional<Boolean> resultado;
        try (SyncLockHolder otra = SyncLockHolder.hold(lock)) {
            resultado = exclusiveSync.run(SyncTrigger.PERIODIC, () -> {
                corrio.set(true);
                return true;
            });
        }

        assertThat(resultado).isEmpty();
        assertThat(corrio).isFalse();
        ArgumentCaptor<String> descripcion = ArgumentCaptor.forClass(String.class);
        verify(syncErrorRecorder).record(eq(SyncErrorType.LOCK_TIMEOUT), descripcion.capture());
        assertThat(descripcion.getValue()).contains("PERIODIC").contains("espera máxima").doesNotContain("Exception");
    }

    @Test
    void siInterrumpenLaEsperaConservaLaInterrupcionYLoRegistra() throws Exception {
        when(syncLock.runExclusively(any())).thenThrow(new InterruptedException());
        ExclusiveSync exclusiveSync = new ExclusiveSync(syncLock, syncErrorRecorder);

        assertThat(exclusiveSync.run(SyncTrigger.MANUAL, () -> true)).isEmpty();

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        ArgumentCaptor<String> descripcion = ArgumentCaptor.forClass(String.class);
        verify(syncErrorRecorder).record(eq(SyncErrorType.LOCK_TIMEOUT), descripcion.capture());
        assertThat(descripcion.getValue()).contains("interrumpió");
    }

    @Test
    void unaExcepcionDeLaAccionSaleTalCualSinRegistrarNadaYLiberaLaExclusion() {
        InMemorySyncLock lock = new InMemorySyncLock(Duration.ofMillis(20));
        ExclusiveSync exclusiveSync = new ExclusiveSync(lock, syncErrorRecorder);

        assertThatThrownBy(() ->
            exclusiveSync.run(SyncTrigger.MANUAL, () -> {
                throw new IllegalStateException("un error de programación");
            })
        ).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(syncErrorRecorder);
        assertThat(exclusiveSync.isRunning()).isFalse();
    }

    @Test
    void sinEsperarConLaExclusionLibreCorre() {
        ExclusiveSync exclusiveSync = new ExclusiveSync(new InMemorySyncLock(Duration.ofSeconds(30)), syncErrorRecorder);

        assertThat(exclusiveSync.runIfFree(SyncTrigger.PERIODIC, () -> 7L)).contains(7L);

        verifyNoInteractions(syncErrorRecorder);
    }

    @Test
    void sinEsperarConOtraEnCursoSeSalteaSinRegistrarNada() {
        // Con la espera larga: runIfFree no la usa. Saltear no es un error: no hay LOCK_TIMEOUT.
        InMemorySyncLock lock = new InMemorySyncLock(Duration.ofSeconds(30));
        ExclusiveSync exclusiveSync = new ExclusiveSync(lock, syncErrorRecorder);
        AtomicBoolean corrio = new AtomicBoolean();

        Optional<Boolean> resultado;
        try (SyncLockHolder otra = SyncLockHolder.hold(lock)) {
            resultado = exclusiveSync.runIfFree(SyncTrigger.PERIODIC, () -> {
                corrio.set(true);
                return true;
            });
        }

        assertThat(resultado).isEmpty();
        assertThat(corrio).isFalse();
        verifyNoInteractions(syncErrorRecorder);
    }

    @Test
    void sinEsperarConElHiloInterrumpidoConservaLaInterrupcionSinRegistrarNada() throws Exception {
        // En el apagado: la pasada no empieza y no deja un "interrumpido" en el estado.
        when(syncLock.tryRunExclusively(any())).thenThrow(new InterruptedException());
        ExclusiveSync exclusiveSync = new ExclusiveSync(syncLock, syncErrorRecorder);

        assertThat(exclusiveSync.runIfFree(SyncTrigger.PERIODIC, () -> true)).isEmpty();

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verifyNoInteractions(syncErrorRecorder);
    }

    @Test
    void informaSiHayUnaSincronizacionCorriendoSinTomarLaExclusion() {
        when(syncLock.isHeld()).thenReturn(true);

        assertThat(new ExclusiveSync(syncLock, syncErrorRecorder).isRunning()).isTrue();
    }
}
