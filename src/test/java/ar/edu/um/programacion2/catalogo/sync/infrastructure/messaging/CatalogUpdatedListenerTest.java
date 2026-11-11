package ar.edu.um.programacion2.catalogo.sync.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.catalogo.sync.CatalogUpdatedJson;
import ar.edu.um.programacion2.catalogo.sync.application.service.SyncService;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogEventResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogUpdateNotice;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class CatalogUpdatedListenerTest {

    private static final String EVENTO = "8b097be5-7ba5-4618-97e8-c502d3f46723";
    private static final CatalogUpdateNotice AVISO = new CatalogUpdateNotice(EVENTO, 41);

    @Mock
    private SyncService syncService;

    @InjectMocks
    private CatalogUpdatedListener listener;

    @Test
    void unMensajeValidoVaAlCasoDeUso() {
        when(syncService.handleCatalogUpdated(AVISO)).thenReturn(new CatalogEventResult.UpToDate(41, 41));

        listener.onCatalogUpdated(mensaje(CatalogUpdatedJson.of(EVENTO, 41)));

        verify(syncService, never()).rejectInvalidCatalogEvent();
    }

    @Test
    void unMensajeRotoSeRegistraComoInvalidoSinLlegarAlCasoDeUso() {
        when(syncService.rejectInvalidCatalogEvent()).thenReturn(new CatalogEventResult.Invalid());

        listener.onCatalogUpdated(mensaje("esto no es json"));

        verify(syncService, never()).handleCatalogUpdated(any());
    }

    @Test
    void unTombstoneSeRegistraComoInvalido() {
        when(syncService.rejectInvalidCatalogEvent()).thenReturn(new CatalogEventResult.Invalid());

        listener.onCatalogUpdated(mensaje(null));

        verify(syncService, never()).handleCatalogUpdated(any());
    }

    @ParameterizedTest
    @MethodSource("resultados")
    void ningunResultadoEsperadoSaleComoExcepcion(CatalogEventResult resultado) {
        when(syncService.handleCatalogUpdated(AVISO)).thenReturn(resultado);

        assertThatCode(() -> listener.onCatalogUpdated(mensaje(CatalogUpdatedJson.of(EVENTO, 41)))).doesNotThrowAnyException();
    }

    static Stream<CatalogEventResult> resultados() {
        return Stream.of(
            new CatalogEventResult.Applied(12L, 42, SyncPath.SNAPSHOT, 1),
            new CatalogEventResult.Duplicate(EVENTO),
            new CatalogEventResult.UpToDate(42, 41),
            new CatalogEventResult.Failed(SyncErrorType.CATEDRA_UNAVAILABLE, "La cátedra no estuvo disponible", 4),
            new CatalogEventResult.NotExecuted(),
            new CatalogEventResult.Invalid()
        );
    }

    @Test
    void unaFallaInesperadaSaleParaQueLaTomeElManejadorDeErrores() {
        when(syncService.handleCatalogUpdated(AVISO)).thenThrow(new DataAccessResourceFailureException("base caída"));

        assertThatThrownBy(() -> listener.onCatalogUpdated(mensaje(CatalogUpdatedJson.of(EVENTO, 41)))).isInstanceOf(
            DataAccessResourceFailureException.class
        );
    }

    private static ConsumerRecord<String, String> mensaje(String valor) {
        return new ConsumerRecord<>("topic", 0, 40L, "41", valor);
    }
}
