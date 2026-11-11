package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.catalogo.sync.SyncLockHolder;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogEventResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogUpdateNotice;
import ar.edu.um.programacion2.catalogo.sync.domain.model.IncorporationResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.ProcessedEvent;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.IncorporateChangesUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.GetSyncStatusUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ProcessedEventRepository;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.lock.InMemorySyncLock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** CU-03 con los puertos mockeados. La exclusión es la real, con una espera corta: así se ve qué corre con ella tomada. */
@ExtendWith(MockitoExtension.class)
class HandleCatalogUpdatedUseCaseImplTest {

    private static final Instant AHORA = Instant.parse("2026-11-06T01:19:28Z");
    private static final Instant T1 = Instant.parse("2026-11-04T12:00:00Z");
    private static final String EVENTO = "8b097be5-7ba5-4618-97e8-c502d3f46723";
    private static final CatalogUpdateNotice AVISO = new CatalogUpdateNotice(EVENTO, 41);

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private GetSyncStatusUseCase getSyncStatusUseCase;

    @Mock
    private IncorporateChangesUseCase incorporateChangesUseCase;

    @Mock
    private SyncErrorRecorder syncErrorRecorder;

    @Mock
    private PlatformTransactionManager txManager;

    private final InMemorySyncLock lock = new InMemorySyncLock(Duration.ofMillis(20));

    private HandleCatalogUpdatedUseCaseImpl useCase;

    @BeforeEach
    void armarCasoDeUso() {
        lenient().when(txManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        useCase = new HandleCatalogUpdatedUseCaseImpl(
            processedEventRepository,
            getSyncStatusUseCase,
            incorporateChangesUseCase,
            new ExclusiveSync(lock, syncErrorRecorder),
            syncErrorRecorder,
            new TransactionTemplate(txManager),
            Clock.fixed(AHORA, ZoneOffset.UTC)
        );
    }

    @Test
    void conUnaVersionNuevaIncorporaLosCambiosConElDisparadorKafkaYElAviso() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(estadoConVersion(12));
        when(incorporateChangesUseCase.incorporate(SyncTrigger.KAFKA, 12L, AVISO)).thenReturn(
            new IncorporationResult.Applied(12L, 42, SyncPath.INCREMENTAL, 2)
        );

        CatalogEventResult resultado = useCase.handle(AVISO);

        assertThat(resultado).isEqualTo(new CatalogEventResult.Applied(12L, 42, SyncPath.INCREMENTAL, 2));
        verify(processedEventRepository, never()).registerIfAbsent(any());
        verifyNoInteractions(syncErrorRecorder);
    }

    @Test
    void sinVersionLocalTambienIncorporaLosCambios() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(SyncStatus.initial());
        when(incorporateChangesUseCase.incorporate(SyncTrigger.KAFKA, null, AVISO)).thenReturn(
            new IncorporationResult.Applied(null, 42, SyncPath.SNAPSHOT, 1)
        );

        assertThat(useCase.handle(AVISO)).isEqualTo(new CatalogEventResult.Applied(null, 42, SyncPath.SNAPSHOT, 1));
    }

    @Test
    void unDuplicadoNoTomaLaExclusionNiLlamaACu02() {
        when(processedEventRepository.exists(EVENTO)).thenReturn(true);

        try (SyncLockHolder otra = SyncLockHolder.hold(lock)) {
            // Con la exclusión tomada: si el duplicado la pidiera, vencería la espera y el resultado sería NotExecuted.
            assertThat(useCase.handle(AVISO)).isEqualTo(new CatalogEventResult.Duplicate(EVENTO));
        }

        verifyNoInteractions(getSyncStatusUseCase, incorporateChangesUseCase, syncErrorRecorder);
        verify(processedEventRepository, never()).registerIfAbsent(any());
    }

    @Test
    void laPrimeraBarreraCorreEnUnaTransaccionYAntesDeLaExclusion() {
        when(processedEventRepository.exists(EVENTO)).thenAnswer(invocation -> {
            assertThat(lock.isHeld()).isFalse();
            return true;
        });

        useCase.handle(AVISO);

        InOrder orden = inOrder(txManager, processedEventRepository);
        orden.verify(txManager).getTransaction(any());
        orden.verify(processedEventRepository).exists(EVENTO);
        orden.verify(txManager).commit(any());
    }

    @Test
    void conLaCopiaAlDiaRegistraElEventoEnUnaTransaccionPropiaSinCu02() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(estadoConVersion(41));

        CatalogEventResult resultado = useCase.handle(AVISO);

        assertThat(resultado).isEqualTo(new CatalogEventResult.UpToDate(41, 41));
        verifyNoInteractions(incorporateChangesUseCase);
        InOrder orden = inOrder(txManager, processedEventRepository);
        orden.verify(txManager).getTransaction(any());
        orden.verify(processedEventRepository).registerIfAbsent(new ProcessedEvent(EVENTO, 41, AHORA));
        orden.verify(txManager).commit(any());
    }

    @Test
    void conLaCopiaAdelantadaTambienEstaAlDia() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(estadoConVersion(50));

        assertThat(useCase.handle(AVISO)).isEqualTo(new CatalogEventResult.UpToDate(50, 41));
        verifyNoInteractions(incorporateChangesUseCase);
    }

    @Test
    void leeLaVersionRegistraYSincronizaConLaExclusionTomada() {
        when(getSyncStatusUseCase.getSyncStatus()).thenAnswer(invocation -> {
            assertThat(lock.isHeld()).isTrue();
            return estadoConVersion(12);
        });
        when(incorporateChangesUseCase.incorporate(SyncTrigger.KAFKA, 12L, AVISO)).thenAnswer(invocation -> {
            assertThat(lock.isHeld()).isTrue();
            return new IncorporationResult.Applied(12L, 42, SyncPath.INCREMENTAL, 1);
        });

        useCase.handle(AVISO);

        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    void siVenceLaEsperaNoLeeNiRegistraNada() {
        try (SyncLockHolder otra = SyncLockHolder.hold(lock)) {
            assertThat(useCase.handle(AVISO)).isEqualTo(new CatalogEventResult.NotExecuted());
        }

        verify(syncErrorRecorder).record(eq(SyncErrorType.LOCK_TIMEOUT), contains("KAFKA"));
        verifyNoInteractions(getSyncStatusUseCase, incorporateChangesUseCase);
        verify(processedEventRepository, never()).registerIfAbsent(any());
    }

    @Test
    void siFallaLaIncorporacionDevuelveElFalloYNoRegistraElEvento() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(estadoConVersion(12));
        when(incorporateChangesUseCase.incorporate(SyncTrigger.KAFKA, 12L, AVISO)).thenReturn(
            new IncorporationResult.Failed(12L, 12L, SyncErrorType.CATEDRA_UNAVAILABLE, "La cátedra no estuvo disponible", 4)
        );

        CatalogEventResult resultado = useCase.handle(AVISO);

        assertThat(resultado).isEqualTo(new CatalogEventResult.Failed(SyncErrorType.CATEDRA_UNAVAILABLE, "La cátedra no estuvo disponible", 4));
        verify(processedEventRepository, never()).registerIfAbsent(any());
        // Ya lo registró quien falló: acá no se registra dos veces.
        verifyNoInteractions(syncErrorRecorder);
    }

    @Test
    void unaFallaDeLaBaseEnLaPrimeraBarreraSaleComoExcepcionParaQueSeReintente() {
        when(processedEventRepository.exists(EVENTO)).thenThrow(new DataAccessResourceFailureException("sin conexión"));

        assertThatThrownBy(() -> useCase.handle(AVISO)).isInstanceOf(DataAccessResourceFailureException.class);

        verifyNoInteractions(getSyncStatusUseCase, incorporateChangesUseCase);
        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    void unaFallaDeLaBaseAlLeerLaVersionLiberaLaExclusionYSaleComoExcepcion() {
        when(getSyncStatusUseCase.getSyncStatus()).thenThrow(new DataAccessResourceFailureException("sin conexión"));

        assertThatThrownBy(() -> useCase.handle(AVISO)).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(lock.isHeld()).isFalse();
        verifyNoInteractions(incorporateChangesUseCase);
    }

    @Test
    void unEventoInvalidoQuedaRegistradoConUnTextoFijo() {
        assertThat(useCase.rejectInvalid()).isEqualTo(new CatalogEventResult.Invalid());

        verify(syncErrorRecorder, times(1)).record(SyncErrorType.INVALID_EVENT, HandleCatalogUpdatedUseCaseImpl.INVALID_EVENT_DESCRIPTION);
        verifyNoInteractions(processedEventRepository, getSyncStatusUseCase, incorporateChangesUseCase);
    }

    @Test
    void unaFallaInesperadaQuedaRegistradaConUnTextoFijo() {
        useCase.reportUnexpectedFailure();

        verify(syncErrorRecorder, times(1)).record(
            SyncErrorType.EVENT_PROCESSING_FAILED,
            HandleCatalogUpdatedUseCaseImpl.UNEXPECTED_FAILURE_DESCRIPTION
        );
    }

    @Test
    void siLaMetadataDiceQueEstaAlDiaDevuelveUpToDateConLaVersionDelAviso() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(estadoConVersion(12));
        when(incorporateChangesUseCase.incorporate(SyncTrigger.KAFKA, 12L, AVISO)).thenReturn(new IncorporationResult.UpToDate(12, 12, 1));

        assertThat(useCase.handle(AVISO)).isEqualTo(new CatalogEventResult.UpToDate(12, 41));
    }

    private static SyncStatus estadoConVersion(long version) {
        SyncStatus estado = SyncStatus.initial();
        estado.recordSnapshotApplied(version, SyncTrigger.STARTUP, T1);
        return estado;
    }
}
