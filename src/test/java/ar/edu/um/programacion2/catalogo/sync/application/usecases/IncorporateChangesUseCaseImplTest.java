package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.catalogo.sync.InMemorySyncStatusRepository;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogUpdateNotice;
import ar.edu.um.programacion2.catalogo.sync.domain.model.FullSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.IncorporationResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.IncrementalSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.ProcessedEvent;
import ar.edu.um.programacion2.catalogo.sync.domain.model.PublishedVersions;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.model.VersionChanges;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.ApplyIncrementalSyncUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.ExecuteFullSyncUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.CatalogChangesProvider;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ChangesAccessDeniedException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ChangesSourceUnavailableException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.InvalidChangesException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ProcessedEventRepository;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CU-03 con disparador, con los puertos y los casos incluidos mockeados. {@code sync_status} está en memoria para ver la
 * versión remota que quedó escrita.
 */
@ExtendWith({ MockitoExtension.class, OutputCaptureExtension.class })
class IncorporateChangesUseCaseImplTest {

    private static final Instant AHORA = Instant.parse("2026-11-08T02:19:40Z");
    private static final Instant T = Instant.parse("2026-11-08T01:19:34Z");
    private static final CatalogUpdateNotice AVISO = new CatalogUpdateNotice("evento-89", 89);

    @Mock
    private CatalogChangesProvider redis;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ApplyIncrementalSyncUseCase cu04;

    @Mock
    private ExecuteFullSyncUseCase cu02;

    @Mock
    private SyncErrorRecorder syncErrorRecorder;

    @Mock
    private PlatformTransactionManager txManager;

    private final List<Duration> esperas = new ArrayList<>();
    private InMemorySyncStatusRepository syncStatus;
    private IncorporateChangesUseCaseImpl useCase;

    @BeforeEach
    void armarCasoDeUso() {
        lenient().when(txManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        syncStatus = new InMemorySyncStatusRepository(conVersion(88));
        useCase = casoDeUso(syncStatus);
    }

    private IncorporateChangesUseCaseImpl casoDeUso(SyncStatusRepository estado) {
        return new IncorporateChangesUseCaseImpl(
            redis,
            estado,
            processedEventRepository,
            cu04,
            cu02,
            new TransactionTemplate(txManager),
            Clock.fixed(AHORA, ZoneOffset.UTC),
            new RetryBackoff(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)), esperas::add),
            syncErrorRecorder
        );
    }

    @Test
    void sinVersionLocalVaASnapshotSinLeerRedis() {
        when(cu02.executeFullSync(SyncTrigger.KAFKA, AVISO)).thenReturn(new FullSyncResult.Applied(89, 1));

        assertThat(useCase.incorporate(SyncTrigger.KAFKA, null, AVISO)).isEqualTo(new IncorporationResult.Applied(null, 89, SyncPath.SNAPSHOT, 1));
        verifyNoInteractions(redis, cu04);
    }

    @Test
    void sinVersionLocalElSnapshotSeHaceAunqueRedisEsteCaido() {
        // La primera copia viene por REST: un Redis caído no la puede impedir. remoteVersion llega con el próximo aviso.
        lenient().when(redis.readPublishedVersions()).thenThrow(new ChangesSourceUnavailableException("Redis de la cátedra no respondió", null));
        when(cu02.executeFullSync(SyncTrigger.MANUAL)).thenReturn(new FullSyncResult.Applied(89, 1));

        assertThat(useCase.incorporate(SyncTrigger.MANUAL, null)).isEqualTo(new IncorporationResult.Applied(null, 89, SyncPath.SNAPSHOT, 1));
        verify(redis, never()).readPublishedVersions();
        verifyNoInteractions(syncErrorRecorder);
        assertThat(syncStatus.current().getRemoteVersion()).isNull();
    }

    @Test
    void conLaVentanaContinuaVaPorIncrementalHastaLaVersionDeRedisYEscribeLaRemota() {
        publica(89, 1);
        when(redis.readChanges(89, 89)).thenReturn(cambios(89));
        when(cu04.applyIncremental(SyncTrigger.KAFKA, 88, cambios(89), AVISO)).thenReturn(new IncrementalSyncResult.Completed(89, 1));

        IncorporationResult resultado = useCase.incorporate(SyncTrigger.KAFKA, 88L, AVISO);

        assertThat(resultado).isEqualTo(new IncorporationResult.Applied(88L, 89, SyncPath.INCREMENTAL, 1));
        assertThat(syncStatus.current().getRemoteVersion()).isEqualTo(89L);
        assertThat(syncStatus.current().getRemoteVersionCheckedAt()).isEqualTo(AHORA);
        // El eventId lo registra CU-04 con la última versión, no la transacción de la versión remota.
        verify(processedEventRepository, never()).registerIfAbsent(any());
        InOrder orden = inOrder(redis, txManager, cu04);
        orden.verify(redis).readPublishedVersions();
        orden.verify(txManager).commit(any());
        orden.verify(redis).readChanges(89, 89);
        orden.verify(cu04).applyIncremental(SyncTrigger.KAFKA, 88, cambios(89), AVISO);
    }

    @Test
    void conLaLocalUnaAntesDeLaMasViejaDisponibleVaSnapshotAunqueExistaElCambio() {
        // Contrato §18.2: local 3, Redis en 7 con la más vieja en 4. No se empieza en changes:4.
        publica(7, 4);
        when(cu02.executeFullSync(SyncTrigger.KAFKA, AVISO)).thenReturn(new FullSyncResult.Applied(7, 1));

        assertThat(useCase.incorporate(SyncTrigger.KAFKA, 3L, AVISO)).isEqualTo(new IncorporationResult.Applied(3L, 7, SyncPath.SNAPSHOT, 1));
        verify(redis, never()).readChanges(anyLong(), anyLong());
        verifyNoInteractions(cu04);
    }

    @Test
    void conLaLocalIgualALaMasViejaDisponibleVaPorIncremental() {
        publica(7, 4);
        when(redis.readChanges(5, 7)).thenReturn(cambios(5, 6, 7));
        when(cu04.applyIncremental(SyncTrigger.KAFKA, 4, cambios(5, 6, 7), AVISO)).thenReturn(new IncrementalSyncResult.Completed(7, 1));

        assertThat(useCase.incorporate(SyncTrigger.KAFKA, 4L, AVISO)).isEqualTo(new IncorporationResult.Applied(4L, 7, SyncPath.INCREMENTAL, 1));
        verifyNoInteractions(cu02);
    }

    @Test
    void siFaltaUnCambioEnElMedioVaSnapshotSinAplicarNada() {
        publica(91, 1);
        when(redis.readChanges(89, 91)).thenReturn(cambios(89, 91));
        when(cu02.executeFullSync(SyncTrigger.KAFKA, AVISO)).thenReturn(new FullSyncResult.Applied(91, 1));

        assertThat(useCase.incorporate(SyncTrigger.KAFKA, 88L, AVISO)).isEqualTo(new IncorporationResult.Applied(88L, 91, SyncPath.SNAPSHOT, 1));
        verifyNoInteractions(cu04);
    }

    @Test
    void conLaCopiaAlDiaRegistraElEventoEnLaMismaTransaccionQueLaVersionRemota() {
        publica(89, 1);

        IncorporationResult resultado = useCase.incorporate(SyncTrigger.KAFKA, 89L, AVISO);

        assertThat(resultado).isEqualTo(new IncorporationResult.UpToDate(89, 89, 1));
        assertThat(syncStatus.current().getRemoteVersion()).isEqualTo(89L);
        verify(processedEventRepository).registerIfAbsent(new ProcessedEvent("evento-89", 89, AHORA));
        verify(txManager, times(1)).commit(any());
        verifyNoInteractions(cu04, cu02);
    }

    @Test
    void conUnAvisoQueSeAdelantaARedisEstaAlDiaYRegistraElEvento() {
        // El aviso dice 90 y Redis todavía publica la 89: la objetivo es la de Redis.
        publica(89, 1);
        CatalogUpdateNotice adelantado = new CatalogUpdateNotice("evento-90", 90);

        assertThat(useCase.incorporate(SyncTrigger.KAFKA, 89L, adelantado)).isEqualTo(new IncorporationResult.UpToDate(89, 89, 1));
        verify(processedEventRepository).registerIfAbsent(new ProcessedEvent("evento-90", 90, AHORA));
    }

    @Test
    void conUnAvisoQueSeAdelantaARedisLlegaHastaLaPublicada() {
        publica(89, 1);
        CatalogUpdateNotice adelantado = new CatalogUpdateNotice("evento-90", 90);
        when(redis.readChanges(89, 89)).thenReturn(cambios(89));
        when(cu04.applyIncremental(SyncTrigger.KAFKA, 88, cambios(89), adelantado)).thenReturn(new IncrementalSyncResult.Completed(89, 1));

        assertThat(useCase.incorporate(SyncTrigger.KAFKA, 88L, adelantado)).isEqualTo(
            new IncorporationResult.Applied(88L, 89, SyncPath.INCREMENTAL, 1)
        );
    }

    @Test
    void sinAvisoNoRegistraEventosYUsaLasVariantesSinAviso() {
        publica(89, 1);
        when(redis.readChanges(89, 89)).thenReturn(cambios(89));
        when(cu04.applyIncremental(SyncTrigger.MANUAL, 88, cambios(89))).thenReturn(new IncrementalSyncResult.Completed(89, 1));

        assertThat(useCase.incorporate(SyncTrigger.MANUAL, 88L)).isEqualTo(new IncorporationResult.Applied(88L, 89, SyncPath.INCREMENTAL, 1));
        assertThat(useCase.incorporate(SyncTrigger.MANUAL, 89L)).isEqualTo(new IncorporationResult.UpToDate(89, 89, 1));
        verifyNoInteractions(processedEventRepository);
    }

    @Test
    void conRedisCaidoAlLeerLaMetadataNoAplicaNadaYRegistraElError() {
        when(redis.readPublishedVersions()).thenThrow(new ChangesSourceUnavailableException("Redis de la cátedra no respondió al leer la metadata", null));

        IncorporationResult resultado = useCase.incorporate(SyncTrigger.KAFKA, 88L, AVISO);

        String descripcion = "Redis de la cátedra no respondió al leer la metadata después de 4 intentos: no se aplicó ningún cambio";
        assertThat(resultado).isEqualTo(new IncorporationResult.Failed(88L, 88L, SyncErrorType.REDIS_UNAVAILABLE, descripcion, 4));
        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
        verify(syncErrorRecorder).record(SyncErrorType.REDIS_UNAVAILABLE, descripcion);
        assertThat(syncStatus.current().getRemoteVersion()).isNull();
        verifyNoInteractions(processedEventRepository, cu04, cu02);
    }

    @Test
    void conLaMetadataInvalidaElEstadoNoSePuedeVerificarYVaSnapshotSinEscribirLaRemota() {
        when(redis.readPublishedVersions()).thenThrow(new InvalidChangesException("Redis de la cátedra publicó la metadata sin respetar el contrato", null));
        when(cu02.executeFullSync(SyncTrigger.KAFKA, AVISO)).thenReturn(new FullSyncResult.Applied(89, 1));

        assertThat(useCase.incorporate(SyncTrigger.KAFKA, 88L, AVISO)).isEqualTo(new IncorporationResult.Applied(88L, 89, SyncPath.SNAPSHOT, 1));
        assertThat(syncStatus.current().getRemoteVersion()).isNull();
        verifyNoInteractions(syncErrorRecorder);
    }

    @Test
    void conLaCredencialDeRedisRechazadaNoReintentaNiCaeEnUnSnapshot() {
        when(redis.readPublishedVersions()).thenThrow(
            new ChangesAccessDeniedException("Redis de la cátedra rechazó la credencial o el permiso al leer la metadata", null)
        );

        IncorporationResult resultado = useCase.incorporate(SyncTrigger.KAFKA, 88L, AVISO);

        assertThat(resultado).isInstanceOf(IncorporationResult.Failed.class);
        assertThat(((IncorporationResult.Failed) resultado).errorType()).isEqualTo(SyncErrorType.INVALID_CREDENTIALS);
        verify(syncErrorRecorder).record(
            SyncErrorType.INVALID_CREDENTIALS,
            "Redis de la cátedra rechazó la credencial o el permiso al leer la metadata: no se aplicó ningún cambio"
        );
        assertThat(esperas).isEmpty();
        verifyNoInteractions(cu02, cu04);
    }

    @Test
    void siFallaEscribirLaVersionRemotaNoSeVuelveALeerLaMetadata() {
        publica(89, 1);
        AtomicInteger escrituras = new AtomicInteger();
        SyncStatusRepository falla1Vez = new SyncStatusRepository() {
            @Override
            public SyncStatus load() {
                return syncStatus.load();
            }

            @Override
            public void save(SyncStatus status) {
                if (escrituras.getAndIncrement() == 0) {
                    throw new DataAccessResourceFailureException("sin conexión");
                }
                syncStatus.save(status);
            }
        };

        IncorporationResult resultado = casoDeUso(falla1Vez).incorporate(SyncTrigger.KAFKA, 89L, AVISO);

        assertThat(resultado).isEqualTo(new IncorporationResult.UpToDate(89, 89, 2));
        verify(redis, times(1)).readPublishedVersions();
        assertThat(esperas).containsExactly(Duration.ofSeconds(1));
        assertThat(syncStatus.current().getRemoteVersion()).isEqualTo(89L);
    }

    @Test
    void unaDiscontinuidadAMitadDeCaminoConservaLasAplicadasYSigueConElSnapshotYElMismoAviso() {
        publica(91, 1);
        when(redis.readChanges(89, 91)).thenReturn(cambios(89, 90, 91));
        when(cu04.applyIncremental(SyncTrigger.KAFKA, 88, cambios(89, 90, 91), AVISO)).thenReturn(
            new IncrementalSyncResult.NeedsSnapshot(89, "en la versión 90 no están en Redis el profesional 201", 1)
        );
        when(cu02.executeFullSync(SyncTrigger.KAFKA, AVISO)).thenReturn(new FullSyncResult.Applied(91, 1));

        assertThat(useCase.incorporate(SyncTrigger.KAFKA, 88L, AVISO)).isEqualTo(new IncorporationResult.Applied(88L, 91, SyncPath.SNAPSHOT, 1));
    }

    @Test
    void siFallaElSnapshotDespuesDeLaDiscontinuidadLaLocalQuedaEnLaUltimaAplicada() {
        publica(91, 1);
        when(redis.readChanges(89, 91)).thenReturn(cambios(89, 90, 91));
        when(cu04.applyIncremental(SyncTrigger.KAFKA, 88, cambios(89, 90, 91), AVISO)).thenReturn(
            new IncrementalSyncResult.NeedsSnapshot(89, "el plazo", 1)
        );
        when(cu02.executeFullSync(SyncTrigger.KAFKA, AVISO)).thenReturn(
            new FullSyncResult.Failed(SyncErrorType.CATEDRA_UNAVAILABLE, "La cátedra no estuvo disponible", 4)
        );

        assertThat(useCase.incorporate(SyncTrigger.KAFKA, 88L, AVISO)).isEqualTo(
            new IncorporationResult.Failed(88L, 89L, SyncErrorType.CATEDRA_UNAVAILABLE, "La cátedra no estuvo disponible", 4)
        );
    }

    @Test
    void siCu04FallaDevuelveLaUltimaVersionAplicada() {
        publica(91, 1);
        when(redis.readChanges(89, 91)).thenReturn(cambios(89, 90, 91));
        when(cu04.applyIncremental(SyncTrigger.KAFKA, 88, cambios(89, 90, 91), AVISO)).thenReturn(
            new IncrementalSyncResult.Failed(89, SyncErrorType.DATABASE_UNAVAILABLE, "La base no respondió", 4)
        );

        assertThat(useCase.incorporate(SyncTrigger.KAFKA, 88L, AVISO)).isEqualTo(
            new IncorporationResult.Failed(88L, 89L, SyncErrorType.DATABASE_UNAVAILABLE, "La base no respondió", 4)
        );
        verifyNoInteractions(cu02);
    }

    @Test
    void siOtroProcesoAvanzoLaVersionTerminaSinCambios() {
        publica(89, 1);
        when(redis.readChanges(89, 89)).thenReturn(cambios(89));
        when(cu04.applyIncremental(SyncTrigger.KAFKA, 88, cambios(89), AVISO)).thenReturn(new IncrementalSyncResult.Preempted(88, 89L, 1));

        assertThat(useCase.incorporate(SyncTrigger.KAFKA, 88L, AVISO)).isEqualTo(new IncorporationResult.UpToDate(89, 89, 1));
        verifyNoInteractions(cu02);
    }

    @Test
    void losIntentosSumanLaLecturaDeRedisCu04YCu02() {
        // 1 reintento leyendo la metadata, 1 en CU-04 (2 intentos) y 2 en CU-02 (3 intentos): 1 + 1 + 1 + 2 = 5.
        when(redis.readPublishedVersions())
            .thenThrow(new ChangesSourceUnavailableException("Redis de la cátedra no respondió al leer la metadata", null))
            .thenReturn(new PublishedVersions(91, 1));
        when(redis.readChanges(89, 91)).thenReturn(cambios(89, 90, 91));
        when(cu04.applyIncremental(eq(SyncTrigger.KAFKA), eq(88L), any(), eq(AVISO))).thenReturn(
            new IncrementalSyncResult.NeedsSnapshot(89, "en la versión 90 no están en Redis el profesional 201", 2)
        );
        when(cu02.executeFullSync(SyncTrigger.KAFKA, AVISO)).thenReturn(new FullSyncResult.Applied(91, 3));

        IncorporationResult resultado = useCase.incorporate(SyncTrigger.KAFKA, 88L, AVISO);

        assertThat(resultado).isEqualTo(new IncorporationResult.Applied(88L, 91, SyncPath.SNAPSHOT, 5));
    }

    @Test
    void laPeriodicaAlDiaNoEscribeEnInfo(CapturedOutput salida) {
        // Pasa cada pocos minutos: en INFO llenaría el log. Los tests corren con la aplicación en INFO.
        publica(88, 1);

        assertThat(useCase.incorporate(SyncTrigger.PERIODIC, 88L)).isEqualTo(new IncorporationResult.UpToDate(88, 88, 1));
        assertThat(salida.getAll()).doesNotContain("ya está al día");
    }

    @Test
    void losDemasDisparadoresAlDiaSiguenEnInfo(CapturedOutput salida) {
        publica(88, 1);

        useCase.incorporate(SyncTrigger.STARTUP, 88L);

        assertThat(salida.getAll()).contains("Sincronización (STARTUP): la versión local 88 ya está al día con la publicada 88");
    }

    private void publica(long current, long oldest) {
        when(redis.readPublishedVersions()).thenReturn(new PublishedVersions(current, oldest));
    }

    private static SortedMap<Long, VersionChanges> cambios(long... versiones) {
        SortedMap<Long, VersionChanges> cambios = new TreeMap<>();
        for (long v : versiones) {
            cambios.put(v, new VersionChanges(v, Set.of(), Set.of(201L), Set.of()));
        }
        return cambios;
    }

    private static SyncStatus conVersion(long version) {
        return new SyncStatus(version, T, SyncPath.SNAPSHOT, SyncTrigger.STARTUP, null, null, null, null, null);
    }
}
