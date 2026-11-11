package ar.edu.um.programacion2.catalogo.sync.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import ar.edu.um.programacion2.catalogo.sync.application.service.SyncService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class CatalogEventRecovererTest {

    private static final ConsumerRecord<String, String> AVISO = new ConsumerRecord<>("topic", 0, 40L, "41", "{}");

    @Mock
    private SyncService syncService;

    @Test
    void registraElAvisoDescartado() {
        new CatalogEventRecoverer(syncService).accept(AVISO, new IllegalStateException("inesperado"));

        verify(syncService).reportCatalogEventFailure();
    }

    @Test
    void nuncaLanzaAunqueNoSePuedaRegistrar() {
        doThrow(new DataAccessResourceFailureException("base caída")).when(syncService).reportCatalogEventFailure();

        assertThatCode(() -> new CatalogEventRecoverer(syncService).accept(AVISO, new IllegalStateException("inesperado"))).doesNotThrowAnyException();
    }
}
