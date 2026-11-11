package ar.edu.um.programacion2.catalogo.sync.infrastructure.periodic;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import ar.edu.um.programacion2.catalogo.sync.application.service.SyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PeriodicSyncTriggerTest {

    @Mock
    private SyncService syncService;

    @Test
    void cadaPasadaLlamaALaComprobacionDelService() {
        new PeriodicSyncTrigger(syncService).checkCatalogVersion();

        verify(syncService).checkCatalogVersion();
    }

    @Test
    void unaFallaInesperadaNoSaleDelDisparador() {
        doThrow(new IllegalStateException("un error de programación")).when(syncService).checkCatalogVersion();

        assertThatCode(() -> new PeriodicSyncTrigger(syncService).checkCatalogVersion()).doesNotThrowAnyException();
    }
}
