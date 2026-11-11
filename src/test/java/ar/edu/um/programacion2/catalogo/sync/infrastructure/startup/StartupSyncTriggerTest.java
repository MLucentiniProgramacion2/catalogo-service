package ar.edu.um.programacion2.catalogo.sync.infrastructure.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ar.edu.um.programacion2.catalogo.sync.application.service.SyncService;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.config.SyncProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class StartupSyncTriggerTest {

    @Mock
    private SyncService syncService;

    private final SyncProperties properties = new SyncProperties();

    /** Executor que no ejecuta nada: guarda las tareas para correrlas cuando el test quiera. */
    private final List<Runnable> tareas = new ArrayList<>();

    private StartupSyncTrigger trigger;

    @BeforeEach
    void armarDisparador() {
        trigger = new StartupSyncTrigger(syncService, properties, tareas::add);
    }

    @Test
    void entregaLaInicializacionAlExecutorSinBloquearElArranque() {
        trigger.onApplicationReady();

        // El listener vuelve sin haber inicializado: la tarea quedó en el executor.
        verifyNoInteractions(syncService);
        assertThat(tareas).hasSize(1);

        tareas.get(0).run();

        verify(syncService).initializeCatalog();
    }

    @Test
    void desactivadoNoHaceNada() {
        properties.getStartup().setEnabled(false);

        trigger.onApplicationReady();

        assertThat(tareas).isEmpty();
        verifyNoInteractions(syncService);
    }

    @Test
    void siLaInicializacionFallaNoPropagaLaExcepcion() {
        doThrow(new DataAccessResourceFailureException("Unable to acquire JDBC Connection")).when(syncService).initializeCatalog();
        trigger.onApplicationReady();

        assertThatCode(() -> tareas.get(0).run()).doesNotThrowAnyException();
    }

    @Test
    void conUnExecutorSincronicoTampocoPropaga() {
        // Como en los tests de integración: si la tarea corre en el mismo hilo, una excepción saldría del listener de
        // ApplicationReadyEvent y haría fallar el arranque.
        doThrow(new IllegalStateException("un error de programación")).when(syncService).initializeCatalog();
        StartupSyncTrigger sincronico = new StartupSyncTrigger(syncService, properties, Runnable::run);

        assertThatCode(sincronico::onApplicationReady).doesNotThrowAnyException();
        verify(syncService).initializeCatalog();
    }
}
