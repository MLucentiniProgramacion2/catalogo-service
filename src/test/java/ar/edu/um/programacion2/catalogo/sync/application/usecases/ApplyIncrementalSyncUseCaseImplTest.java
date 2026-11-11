package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.sync.InMemorySyncStatusRepository;
import ar.edu.um.programacion2.catalogo.sync.ManualClock;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogUpdateNotice;
import ar.edu.um.programacion2.catalogo.sync.domain.model.ChangedEntities;
import ar.edu.um.programacion2.catalogo.sync.domain.model.IncrementalSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.ProcessedEvent;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.model.VersionChanges;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.CatalogChangesProvider;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ChangesAccessDeniedException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ChangesSourceUnavailableException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.InvalidChangesException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ProcessedEventRepository;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CU-04 con los puertos mockeados. {@code sync_status} está en memoria ({@link InMemorySyncStatusRepository}) para ver la
 * versión entre una transacción y la siguiente, y el reloj avanza a mano ({@link ManualClock}): el plazo se vence sin
 * dormir. Las esperas se registran en vez de dormirse.
 */
@ExtendWith(MockitoExtension.class)
class ApplyIncrementalSyncUseCaseImplTest {

    private static final Instant AHORA = Instant.parse("2026-11-08T02:00:00Z");
    private static final Instant T = Instant.parse("2026-09-01T03:00:00Z");
    private static final CatalogUpdateNotice AVISO = new CatalogUpdateNotice("evento-89", 89);

    @Mock
    private CatalogChangesProvider changesProvider;

    @Mock
    private CatalogRepository catalogRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private SyncErrorRecorder syncErrorRecorder;

    @Mock
    private PlatformTransactionManager txManager;

    private final ManualClock clock = new ManualClock(AHORA);
    private final List<Duration> esperas = new ArrayList<>();
    private InMemorySyncStatusRepository syncStatus;
    private ApplyIncrementalSyncUseCaseImpl useCase;

    @BeforeEach
    void armarCasoDeUso() {
        lenient().when(txManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        lenient().when(catalogRepository.findExistingCategoryIds(anyCollection())).thenAnswer(invocation -> Set.copyOf(invocation.getArgument(0)));
        lenient()
            .when(catalogRepository.findExistingProfessionalIds(anyCollection()))
            .thenAnswer(invocation -> Set.copyOf(invocation.getArgument(0)));
        syncStatus = spy(new InMemorySyncStatusRepository(conVersion(88)));
        useCase = casoDeUso(Duration.ofSeconds(60));
    }

    private ApplyIncrementalSyncUseCaseImpl casoDeUso(Duration plazo) {
        return new ApplyIncrementalSyncUseCaseImpl(
            changesProvider,
            catalogRepository,
            syncStatus,
            processedEventRepository,
            new TransactionTemplate(txManager),
            clock,
            new RetryBackoff(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)), esperas::add),
            syncErrorRecorder,
            new IncrementalTimeBudget(plazo)
        );
    }

    @Test
    void unaVersionGuardaLasEntidadesYDespuesAvanzaLaVersionEnLaMismaTransaccion() {
        cuandoSeLeaElProfesional201(true);

        IncrementalSyncResult resultado = useCase.applyIncremental(SyncTrigger.KAFKA, 88, pendientes(89));

        assertThat(resultado).isEqualTo(new IncrementalSyncResult.Completed(89, 1));
        SyncStatus estado = syncStatus.current();
        assertThat(estado.getLocalVersion()).isEqualTo(89L);
        assertThat(estado.getLastSuccessPath()).isEqualTo(SyncPath.INCREMENTAL);
        assertThat(estado.getLastSuccessTrigger()).isEqualTo(SyncTrigger.KAFKA);
        // Primero se lee Redis, afuera; después, en una transacción: versión, referencias, entidades y versión nueva.
        InOrder orden = inOrder(changesProvider, txManager, syncStatus, catalogRepository);
        orden.verify(changesProvider).readEntities(any());
        orden.verify(txManager).getTransaction(any());
        orden.verify(syncStatus).load();
        orden.verify(catalogRepository).findExistingCategoryIds(Set.of(101L));
        orden.verify(catalogRepository).saveAll(List.of(), List.of(profesional(201, true)), List.of());
        orden.verify(syncStatus).save(any());
        orden.verify(txManager).commit(any());
        verifyNoInteractions(syncErrorRecorder, processedEventRepository);
    }

    @Test
    void variasVersionesSeAplicanEnOrdenUnaTransaccionPorVersion() {
        cuandoSeLeaElProfesional201(true);
        List<Long> versionesAlGuardar = new ArrayList<>();
        doAnswer(invocation -> versionesAlGuardar.add(syncStatus.current().getLocalVersion()))
            .when(catalogRepository)
            .saveAll(anyList(), anyList(), anyList());

        IncrementalSyncResult resultado = useCase.applyIncremental(SyncTrigger.MANUAL, 88, pendientes(89, 90, 91));

        assertThat(resultado).isEqualTo(new IncrementalSyncResult.Completed(91, 1));
        // Cada versión ve la anterior ya confirmada.
        assertThat(versionesAlGuardar).containsExactly(88L, 89L, 90L);
        verify(txManager, times(3)).commit(any());
    }

    @Test
    void elEventIdSeRegistraSoloEnLaTransaccionDeLaUltimaVersion() {
        cuandoSeLeaElProfesional201(true);
        List<Long> versionAlRegistrar = new ArrayList<>();
        doAnswer(invocation -> versionAlRegistrar.add(syncStatus.current().getLocalVersion()))
            .when(processedEventRepository)
            .registerIfAbsent(any());
        syncStatus.save(conVersion(86));

        useCase.applyIncremental(SyncTrigger.KAFKA, 86, pendientes(87, 88, 89), AVISO);

        verify(processedEventRepository, times(1)).registerIfAbsent(new ProcessedEvent("evento-89", 89, AHORA));
        assertThat(versionAlRegistrar).containsExactly(89L);
    }

    @Test
    void unLoteVacioNoHaceNada() {
        assertThat(useCase.applyIncremental(SyncTrigger.MANUAL, 88, new TreeMap<>())).isEqualTo(new IncrementalSyncResult.Completed(88, 1));
        verifyNoInteractions(changesProvider, txManager);
    }

    @Test
    void unCambioQueDiceOtraVersionNoSePuedeVerificar() {
        SortedMap<Long, VersionChanges> pendientes = new TreeMap<>();
        pendientes.put(89L, new VersionChanges(88, Set.of(), Set.of(201L), Set.of()));

        IncrementalSyncResult resultado = useCase.applyIncremental(SyncTrigger.KAFKA, 88, pendientes);

        assertThat(resultado).isInstanceOf(IncrementalSyncResult.NeedsSnapshot.class);
        assertThat(resultado.localVersion()).isEqualTo(88);
        verifyNoInteractions(changesProvider, txManager, syncErrorRecorder);
    }

    @Test
    void unIdQueNoEstaEnElHashNoSePuedeVerificarYSeConservanLasAnteriores() {
        cuandoSeLeaElProfesional201(true);
        when(changesProvider.readEntities(cambio(90))).thenReturn(new ChangedEntities(List.of(), List.of(), List.of()));

        IncrementalSyncResult resultado = useCase.applyIncremental(SyncTrigger.KAFKA, 88, pendientes(89, 90, 91), AVISO);

        assertThat(resultado).isEqualTo(
            new IncrementalSyncResult.NeedsSnapshot(88 + 1, "en la versión 90 no están en Redis el profesional 201", 1)
        );
        assertThat(syncStatus.current().getLocalVersion()).isEqualTo(89L);
        verify(processedEventRepository, never()).registerIfAbsent(any());
        verifyNoInteractions(syncErrorRecorder);
    }

    @Test
    void unaEntidadInvalidaNoSePuedeVerificar() {
        when(changesProvider.readEntities(any())).thenThrow(new InvalidChangesException("Redis de la cátedra publicó algo raro", null));

        IncrementalSyncResult resultado = useCase.applyIncremental(SyncTrigger.KAFKA, 88, pendientes(89));

        assertThat(resultado).isInstanceOf(IncrementalSyncResult.NeedsSnapshot.class);
        assertThat(esperas).isEmpty();
    }

    @Test
    void unaReferenciaRotaVaASnapshotSinGuardarNiAvanzar() {
        cuandoSeLeaElProfesional201(true);
        when(catalogRepository.findExistingCategoryIds(Set.of(101L))).thenReturn(Set.of());

        IncrementalSyncResult resultado = useCase.applyIncremental(SyncTrigger.KAFKA, 88, pendientes(89));

        assertThat(resultado).isEqualTo(
            new IncrementalSyncResult.NeedsSnapshot(
                88,
                "la versión 89 tiene referencias inexistentes: el profesional 201 apunta a la categoría 101, que no existe",
                1
            )
        );
        verify(catalogRepository, never()).saveAll(anyList(), anyList(), anyList());
        assertThat(syncStatus.current().getLocalVersion()).isEqualTo(88L);
        // TransactionTemplate confirma con el estado marcado: el manager real hace el rollback.
        verify(txManager).commit(argThat(TransactionStatus::isRollbackOnly));
    }

    @Test
    void unaReferenciaQueTraeLaMismaVersionNoSeBuscaEnLaCopia() {
        when(changesProvider.readEntities(any())).thenReturn(
            new ChangedEntities(List.of(new ProfessionalCategory(105, "Nueva", null, true, T, T)), List.of(profesional(203, 105)), List.of())
        );

        SortedMap<Long, VersionChanges> pendientes = new TreeMap<>();
        pendientes.put(89L, new VersionChanges(89, Set.of(105L), Set.of(203L), Set.of()));

        assertThat(useCase.applyIncremental(SyncTrigger.KAFKA, 88, pendientes)).isEqualTo(new IncrementalSyncResult.Completed(89, 1));
        verify(catalogRepository).findExistingCategoryIds(Set.of());
    }

    @Test
    void siOtroProcesoYaAvanzoLaVersionTerminaSinCambiosNiError() {
        cuandoSeLeaElProfesional201(true);
        syncStatus.save(conVersion(89));

        IncrementalSyncResult resultado = useCase.applyIncremental(SyncTrigger.KAFKA, 88, pendientes(89, 90), AVISO);

        assertThat(resultado).isEqualTo(new IncrementalSyncResult.Preempted(88, 89L, 1));
        verify(catalogRepository, never()).saveAll(anyList(), anyList(), anyList());
        verify(processedEventRepository, never()).registerIfAbsent(any());
        verifyNoInteractions(syncErrorRecorder);
        // TransactionTemplate confirma con el estado marcado: el manager real hace el rollback.
        verify(txManager).commit(argThat(TransactionStatus::isRollbackOnly));
    }

    @Test
    void redisCaidoAMitadDelLoteConservaLasAnterioresYRegistraElError() {
        cuandoSeLeaElProfesional201(true);
        when(changesProvider.readEntities(cambio(90))).thenThrow(
            new ChangesSourceUnavailableException("Redis de la cátedra no respondió al leer los profesionales", null)
        );

        IncrementalSyncResult resultado = useCase.applyIncremental(SyncTrigger.KAFKA, 88, pendientes(89, 90, 91), AVISO);

        String descripcion =
            "Redis de la cátedra no respondió al leer los profesionales (versión 90) después de 4 intentos: se conservan las versiones hasta la 89";
        assertThat(resultado).isEqualTo(new IncrementalSyncResult.Failed(89, SyncErrorType.REDIS_UNAVAILABLE, descripcion, 4));
        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
        assertThat(syncStatus.current().getLocalVersion()).isEqualTo(89L);
        verify(syncErrorRecorder).record(SyncErrorType.REDIS_UNAVAILABLE, descripcion);
        verify(processedEventRepository, never()).registerIfAbsent(any());
    }

    @Test
    void siLaBaseFallaEnLaSegundaDeTresSeReintentaSoloLaTransaccionYSeConservaLaPrimera() {
        cuandoSeLeaElProfesional201(true);
        AtomicInteger versionEnCurso = new AtomicInteger();
        doAnswer(invocation -> {
            if (syncStatus.current().getLocalVersion() == 89L) {
                versionEnCurso.incrementAndGet();
                throw new DataAccessResourceFailureException("sin conexión");
            }
            return null;
        })
            .when(catalogRepository)
            .saveAll(anyList(), anyList(), anyList());

        IncrementalSyncResult resultado = useCase.applyIncremental(SyncTrigger.KAFKA, 88, pendientes(89, 90, 91), AVISO);

        assertThat(resultado).isEqualTo(
            new IncrementalSyncResult.Failed(
                89,
                SyncErrorType.DATABASE_UNAVAILABLE,
                "La base de datos local no respondió al aplicar la versión 90 después de 4 intentos: se conservan las versiones hasta la 89",
                4
            )
        );
        assertThat(versionEnCurso).hasValue(4);
        // Redis se leyó una sola vez para la 90: el reintento repite el paso que falló, no la versión entera.
        verify(changesProvider, times(1)).readEntities(cambio(90));
        verify(changesProvider, never()).readEntities(cambio(91));
        verify(processedEventRepository, never()).registerIfAbsent(any());
    }

    @Test
    void siLaBaseRechazaLaVersionNoSeReintentaYEsIncrementalFailed() {
        cuandoSeLeaElProfesional201(true);
        doThrow(new DataIntegrityViolationException("viola un CHECK")).when(catalogRepository).saveAll(anyList(), anyList(), anyList());

        IncrementalSyncResult resultado = useCase.applyIncremental(SyncTrigger.KAFKA, 88, pendientes(89));

        assertThat(resultado).isEqualTo(
            new IncrementalSyncResult.Failed(
                88,
                SyncErrorType.INCREMENTAL_FAILED,
                "La base de datos local rechazó la versión 89: no se aplicó ninguna versión",
                1
            )
        );
        assertThat(esperas).isEmpty();
    }

    @Test
    void conLaCredencialDeRedisRechazadaNoSeReintentaYLaDescripcionNombraARedis() {
        when(changesProvider.readEntities(any())).thenThrow(
            new ChangesAccessDeniedException("Redis de la cátedra rechazó la credencial o el permiso al leer los profesionales", null)
        );

        IncrementalSyncResult resultado = useCase.applyIncremental(SyncTrigger.KAFKA, 88, pendientes(89));

        assertThat(resultado).isInstanceOf(IncrementalSyncResult.Failed.class);
        assertThat(((IncrementalSyncResult.Failed) resultado).errorType()).isEqualTo(SyncErrorType.INVALID_CREDENTIALS);
        verify(syncErrorRecorder).record(eq(SyncErrorType.INVALID_CREDENTIALS), startsWith("Redis de la cátedra rechazó la credencial"));
        assertThat(esperas).isEmpty();
    }

    @Test
    void losReintentosSonUnSoloPresupuestoParaTodoElLote() {
        cuandoSeLeaElProfesional201(true);
        when(changesProvider.readEntities(cambio(89)))
            .thenThrow(new ChangesSourceUnavailableException("Redis de la cátedra no respondió", null))
            .thenReturn(entidades(profesional(201, true)));
        when(changesProvider.readEntities(cambio(91)))
            .thenThrow(new ChangesSourceUnavailableException("Redis de la cátedra no respondió", null))
            .thenReturn(entidades(profesional(201, true)));
        AtomicInteger falloLaBase = new AtomicInteger();
        doAnswer(invocation -> {
            if (syncStatus.current().getLocalVersion() == 89L && falloLaBase.getAndIncrement() == 0) {
                throw new DataAccessResourceFailureException("sin conexión");
            }
            return null;
        })
            .when(catalogRepository)
            .saveAll(anyList(), anyList(), anyList());

        IncrementalSyncResult resultado = useCase.applyIncremental(SyncTrigger.KAFKA, 88, pendientes(89, 90, 91));

        // Una falla por versión, en pasos distintos: entre las tres usan los tres reintentos, y las esperas siguen la serie.
        assertThat(resultado).isEqualTo(new IncrementalSyncResult.Completed(91, 4));
        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void conElPresupuestoAgotadoLaCuartaFallaTerminaElLote() {
        cuandoSeLeaElProfesional201(true);
        for (long v = 89; v <= 92; v++) {
            when(changesProvider.readEntities(cambio(v)))
                .thenThrow(new ChangesSourceUnavailableException("Redis de la cátedra no respondió", null))
                .thenReturn(entidades(profesional(201, true)));
        }

        IncrementalSyncResult resultado = useCase.applyIncremental(SyncTrigger.KAFKA, 88, pendientes(89, 90, 91, 92));

        assertThat(resultado).isInstanceOf(IncrementalSyncResult.Failed.class);
        assertThat(resultado.localVersion()).isEqualTo(91);
        assertThat(resultado.attempts()).isEqualTo(4);
    }

    @Test
    void siElCommitFallaPeroQuedoConfirmadoElReintentoLaDaPorAplicadaYSigue() {
        // Lo que se guardó queda (el sync_status en memoria no hace rollback): es una base que confirmó y avisó que falló.
        cuandoSeLeaElProfesional201(true);
        AtomicInteger commits = new AtomicInteger();
        doAnswer(invocation -> {
            if (commits.getAndIncrement() == 0) {
                throw new TransactionSystemException("se cortó la conexión durante el commit");
            }
            return null;
        })
            .when(txManager)
            .commit(any());

        IncrementalSyncResult resultado = useCase.applyIncremental(SyncTrigger.KAFKA, 88, pendientes(89, 90), AVISO);

        assertThat(resultado).isEqualTo(new IncrementalSyncResult.Completed(90, 2));
        // La 89 se guardó una sola vez: el reintento encontró la local en 89 y siguió con la 90.
        verify(catalogRepository, times(2)).saveAll(anyList(), anyList(), anyList());
        verifyNoInteractions(syncErrorRecorder);
    }

    @Test
    void laLocalIgualAVSinUnaFallaAntesNoEsUnCommitDudosoSinoOtroProceso() {
        cuandoSeLeaElProfesional201(true);
        syncStatus.save(conVersion(89));

        assertThat(useCase.applyIncremental(SyncTrigger.KAFKA, 88, pendientes(89))).isInstanceOf(IncrementalSyncResult.Preempted.class);
    }

    @Test
    void alVencerElPlazoNoEmpiezaOtraVersionYSigueUnSnapshot() {
        cuandoSeLeaElProfesional201(true);
        doAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(31));
            return entidades(profesional(201, true));
        })
            .when(changesProvider)
            .readEntities(any());

        IncrementalSyncResult resultado = useCase.applyIncremental(SyncTrigger.KAFKA, 88, pendientes(89, 90, 91, 92), AVISO);

        // 89 en 31 s, 90 a los 62 s: vencido el plazo de 60 s, la 91 no empieza.
        assertThat(resultado).isEqualTo(
            new IncrementalSyncResult.NeedsSnapshot(90, "venció el plazo del lote con 2 versiones aplicadas", 1)
        );
        verify(changesProvider, times(2)).readEntities(any());
        verify(processedEventRepository, never()).registerIfAbsent(any());
    }

    @Test
    void alVencerElPlazoTampocoEmpiezaOtroReintento() {
        when(changesProvider.readEntities(any())).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(61));
            throw new ChangesSourceUnavailableException("Redis de la cátedra no respondió", null);
        });

        IncrementalSyncResult resultado = useCase.applyIncremental(SyncTrigger.KAFKA, 88, pendientes(89));

        assertThat(resultado).isInstanceOf(IncrementalSyncResult.NeedsSnapshot.class);
        assertThat(esperas).isEmpty();
        verifyNoInteractions(syncErrorRecorder);
    }

    @Test
    void elCasoRealTreintaYTresVersionesDeUnProfesionalEntranEnElPlazo() {
        // De 57 a 89, cada una con el profesional 201, y un viaje a Redis de 290 ms (el peor medido por ZeroTier).
        syncStatus.save(conVersion(56));
        doAnswer(invocation -> {
            clock.advance(Duration.ofMillis(290));
            return entidades(profesional(201, invocation.<VersionChanges>getArgument(0).version() % 2 == 0));
        })
            .when(changesProvider)
            .readEntities(any());
        long[] versiones = new long[33];
        for (int i = 0; i < 33; i++) {
            versiones[i] = 57 + i;
        }

        IncrementalSyncResult resultado = useCase.applyIncremental(SyncTrigger.KAFKA, 56, pendientes(versiones), AVISO);

        assertThat(resultado).isEqualTo(new IncrementalSyncResult.Completed(89, 1));
        assertThat(syncStatus.current().getLastSuccessPath()).isEqualTo(SyncPath.INCREMENTAL);
        verify(catalogRepository, times(33)).saveAll(anyList(), anyList(), anyList());
        verify(processedEventRepository, times(1)).registerIfAbsent(any());
    }

    private void cuandoSeLeaElProfesional201(boolean habilitado) {
        lenient().when(changesProvider.readEntities(any())).thenReturn(entidades(profesional(201, habilitado)));
    }

    private static SortedMap<Long, VersionChanges> pendientes(long... versiones) {
        SortedMap<Long, VersionChanges> pendientes = new TreeMap<>();
        for (long v : versiones) {
            pendientes.put(v, cambio(v));
        }
        return pendientes;
    }

    private static VersionChanges cambio(long version) {
        return new VersionChanges(version, Set.of(), Set.of(201L), Set.of());
    }

    private static ChangedEntities entidades(Professional... profesionales) {
        return new ChangedEntities(List.of(), List.of(profesionales), List.of());
    }

    private static Professional profesional(long id, boolean habilitado) {
        return new Professional(id, 101, "Ana", "Torres", habilitado, T, T);
    }

    private static Professional profesional(long id, long categoria) {
        return new Professional(id, categoria, "Ana", "Torres", true, T, T);
    }

    private static SyncStatus conVersion(long version) {
        return new SyncStatus(version, T, SyncPath.SNAPSHOT, SyncTrigger.STARTUP, null, null, null, null, null);
    }
}
