package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogSnapshot;
import ar.edu.um.programacion2.catalogo.sync.domain.model.FullSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.CatalogSnapshotProvider;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.InvalidSnapshotException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SnapshotAccessDeniedException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SnapshotRetrievalException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SnapshotSourceUnavailableException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CU-02 sin red ni base: los puertos son mocks, el reloj es fijo y las esperas solo se anotan.
 * <p>
 * Las transacciones son reales en cuanto al {@link TransactionTemplate}, pero sobre un {@link PlatformTransactionManager}
 * mockeado: así se verifica dónde se abre cada una, si se confirma o se deshace y en qué orden respecto del pedido a la
 * cátedra.
 */
@ExtendWith(MockitoExtension.class)
class ExecuteFullSyncUseCaseImplTest {

    private static final Instant AHORA = Instant.parse("2026-10-30T15:00:00Z");
    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final String TOKEN = "token-secreto-de-prueba";
    private static final List<Duration> DP_08 = List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));

    @Mock
    private CatalogSnapshotProvider provider;

    @Mock
    private CatalogRepository catalogRepository;

    @Mock
    private SyncStatusRepository syncStatusRepository;

    @Mock
    private PlatformTransactionManager txManager;

    private final List<Duration> esperas = new ArrayList<>();

    private ExecuteFullSyncUseCaseImpl useCase;

    @BeforeEach
    void armarCasoDeUso() {
        lenient().when(txManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        lenient().when(syncStatusRepository.load()).thenAnswer(invocation -> SyncStatus.initial());
        useCase = casoDeUsoConSleeper(esperas::add);
    }

    @AfterEach
    void limpiarInterrupcion() {
        // Por si un test deja el hilo interrumpido: no puede afectar al siguiente.
        Thread.interrupted();
    }

    @Test
    void aplicaElSnapshotYGuardaLaVersionEnUnaSolaTransaccionPedidaAntesDeAbrirla() {
        when(provider.fetchSnapshot()).thenReturn(snapshot(7));

        FullSyncResult resultado = useCase.executeFullSync(SyncTrigger.STARTUP);

        assertThat(resultado).isEqualTo(new FullSyncResult.Applied(7, 1));
        // El pedido a la cátedra va antes de abrir la transacción, nunca adentro.
        InOrder orden = inOrder(provider, txManager, catalogRepository, syncStatusRepository);
        orden.verify(provider).fetchSnapshot();
        orden.verify(txManager).getTransaction(any());
        orden.verify(catalogRepository).replaceAll(List.of(categoria(1)), List.of(profesional(15, 1)), List.of(horario(21, 15)));
        orden.verify(syncStatusRepository).load();
        orden.verify(syncStatusRepository).save(any());
        orden.verify(txManager).commit(any());
        verify(txManager, times(1)).getTransaction(any());
        verify(txManager, never()).rollback(any());

        SyncStatus guardado = estadoGuardado();
        assertThat(guardado.getLocalVersion()).isEqualTo(7L);
        assertThat(guardado.getLastSuccessPath()).isEqualTo(SyncPath.SNAPSHOT);
        assertThat(guardado.getLastSuccessTrigger()).isEqualTo(SyncTrigger.STARTUP);
        assertThat(guardado.getLastSuccessAt()).isEqualTo(AHORA);
        assertThat(guardado.getLastErrorType()).isNull();
        assertThat(esperas).isEmpty();
    }

    @Test
    void siLaCatedraNoEstaDisponibleDosVecesEsperaUnoYDosSegundosYDespuesAplica() {
        when(provider.fetchSnapshot()).thenThrow(noDisponible(), noDisponible()).thenReturn(snapshot(7));

        FullSyncResult resultado = useCase.executeFullSync(SyncTrigger.MANUAL);

        assertThat(resultado).isEqualTo(new FullSyncResult.Applied(7, 3));
        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
        verify(provider, times(3)).fetchSnapshot();
        verify(catalogRepository, times(1)).replaceAll(any(), any(), any());
        assertThat(estadoGuardado().getLastErrorType()).isNull();
    }

    @Test
    void siLaCatedraNoEstaDisponibleCuatroVecesRegistraElErrorSinTocarLaCopia() {
        when(provider.fetchSnapshot()).thenThrow(noDisponible());

        FullSyncResult resultado = useCase.executeFullSync(SyncTrigger.STARTUP);

        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
        verify(provider, times(4)).fetchSnapshot();
        verify(catalogRepository, never()).replaceAll(any(), any(), any());
        String descripcion =
            "La cátedra no estuvo disponible después de 4 intentos: La cátedra respondió 503 (code=SERVICE_UNAVAILABLE) al pedir el snapshot";
        assertThat(resultado).isEqualTo(new FullSyncResult.Failed(SyncErrorType.CATEDRA_UNAVAILABLE, descripcion, 4));
        // Solo se abrió la transacción del error, y se confirmó.
        verify(txManager, times(1)).getTransaction(any());
        verify(txManager, times(1)).commit(any());
        SyncStatus guardado = estadoGuardado();
        assertThat(guardado.getLastErrorType()).isEqualTo(SyncErrorType.CATEDRA_UNAVAILABLE);
        assertThat(guardado.getLastErrorDescription()).isEqualTo(descripcion);
        assertThat(guardado.getLastErrorAt()).isEqualTo(AHORA);
        assertThat(guardado.hasLocalCopy()).isFalse();
    }

    static Stream<Arguments> fallasDefinitivasDeLaCatedra() {
        return Stream.of(
            Arguments.of(
                new SnapshotAccessDeniedException("La cátedra respondió 401 (sin code) al pedir el snapshot"),
                SyncErrorType.INVALID_CREDENTIALS,
                "La cátedra rechazó la credencial técnica: La cátedra respondió 401 (sin code) al pedir el snapshot"
            ),
            Arguments.of(
                new InvalidSnapshotException("Snapshot inválido: El horario 21 tiene un startTime inválido: '25:00'"),
                SyncErrorType.SNAPSHOT_FAILED,
                "No se aplicó el snapshot: Snapshot inválido: El horario 21 tiene un startTime inválido: '25:00'"
            )
        );
    }

    @ParameterizedTest
    @MethodSource("fallasDefinitivasDeLaCatedra")
    void lasFallasDefinitivasDeLaCatedraNoSeReintentan(SnapshotRetrievalException falla, SyncErrorType tipo, String descripcion) {
        when(provider.fetchSnapshot()).thenThrow(falla);

        FullSyncResult resultado = useCase.executeFullSync(SyncTrigger.STARTUP);

        assertThat(resultado).isEqualTo(new FullSyncResult.Failed(tipo, descripcion, 1));
        verify(provider, times(1)).fetchSnapshot();
        assertThat(esperas).isEmpty();
        verify(catalogRepository, never()).replaceAll(any(), any(), any());
        assertThat(estadoGuardado().getLastErrorType()).isEqualTo(tipo);
    }

    @Test
    void conReferenciasRotasNoAbreLaTransaccionDeAplicarYRegistraElError() {
        CatalogSnapshot conReferenciasRotas = new CatalogSnapshot(
            7,
            AHORA,
            List.of(categoria(1)),
            List.of(profesional(15, 1), profesional(16, 999)),
            List.of(horario(21, 15))
        );
        when(provider.fetchSnapshot()).thenReturn(conReferenciasRotas);

        FullSyncResult resultado = useCase.executeFullSync(SyncTrigger.STARTUP);

        String descripcion =
            "No se aplicó el snapshot 7: tiene 1 referencia inexistente: el profesional 16 apunta a la categoría 999, que no está en el snapshot";
        assertThat(resultado).isEqualTo(new FullSyncResult.Failed(SyncErrorType.SNAPSHOT_FAILED, descripcion, 1));
        verify(catalogRepository, never()).replaceAll(any(), any(), any());
        assertThat(esperas).isEmpty();
        // La única transacción es la que registra el error.
        InOrder orden = inOrder(provider, txManager, syncStatusRepository);
        orden.verify(provider).fetchSnapshot();
        orden.verify(txManager).getTransaction(any());
        orden.verify(syncStatusRepository).load();
        orden.verify(syncStatusRepository).save(any());
        orden.verify(txManager).commit(any());
        verify(txManager, times(1)).getTransaction(any());
        assertThat(estadoGuardado().getLastErrorDescription()).isEqualTo(descripcion);
    }

    @Test
    void listaHastaTresReferenciasRotasYCuentaElResto() {
        CatalogSnapshot conReferenciasRotas = new CatalogSnapshot(
            7,
            AHORA,
            List.of(),
            List.of(profesional(15, 1), profesional(16, 2), profesional(17, 3), profesional(18, 4), profesional(19, 5)),
            List.of()
        );
        when(provider.fetchSnapshot()).thenReturn(conReferenciasRotas);

        FullSyncResult.Failed resultado = (FullSyncResult.Failed) useCase.executeFullSync(SyncTrigger.STARTUP);

        assertThat(resultado.description())
            .startsWith("No se aplicó el snapshot 7: tiene 5 referencias inexistentes: el profesional 15 apunta a la categoría 1")
            .contains("el profesional 17 apunta a la categoría 3")
            .doesNotContain("el profesional 18")
            .endsWith(" (y 2 más)");
    }

    static Stream<RuntimeException> fallasTransitoriasDeLaBase() {
        return Stream.of(
            new DataAccessResourceFailureException("Unable to acquire JDBC Connection"),
            new QueryTimeoutException("canceling statement due to statement timeout"),
            new CannotAcquireLockException("could not obtain lock"),
            new RecoverableDataAccessException("conexión recuperable"),
            // La que sale cuando la conexión se corta a mitad de la transacción: el rollback también falla.
            new JpaSystemException(new RuntimeException("Unable to rollback against JDBC Connection"))
        );
    }

    @ParameterizedTest
    @MethodSource("fallasTransitoriasDeLaBase")
    void unaFallaTransitoriaDeLaBaseSeReintentaConOtraTransaccion(RuntimeException falla) {
        when(provider.fetchSnapshot()).thenReturn(snapshot(7));
        doThrow(falla).doNothing().when(catalogRepository).replaceAll(any(), any(), any());

        FullSyncResult resultado = useCase.executeFullSync(SyncTrigger.STARTUP);

        assertThat(resultado).isEqualTo(new FullSyncResult.Applied(7, 2));
        assertThat(esperas).containsExactly(Duration.ofSeconds(1));
        // El reintento vuelve a pedir el snapshot y abre una transacción nueva; la primera se deshizo.
        verify(provider, times(2)).fetchSnapshot();
        verify(txManager, times(2)).getTransaction(any());
        verify(txManager, times(1)).rollback(any());
        verify(txManager, times(1)).commit(any());
        assertThat(estadoGuardado().getLocalVersion()).isEqualTo(7L);
    }

    @Test
    void siNoSePuedeAbrirLaTransaccionSeReintenta() {
        when(provider.fetchSnapshot()).thenReturn(snapshot(7));
        when(txManager.getTransaction(any()))
            .thenThrow(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"))
            .thenAnswer(invocation -> new SimpleTransactionStatus());

        FullSyncResult resultado = useCase.executeFullSync(SyncTrigger.STARTUP);

        assertThat(resultado).isEqualTo(new FullSyncResult.Applied(7, 2));
        assertThat(esperas).containsExactly(Duration.ofSeconds(1));
        verify(catalogRepository, times(1)).replaceAll(any(), any(), any());
    }

    @Test
    void siFallaElCommitSeReintentaPorqueAplicarElSnapshotEsIdempotente() {
        when(provider.fetchSnapshot()).thenReturn(snapshot(7));
        doThrow(new TransactionSystemException("Could not commit JPA transaction")).doNothing().when(txManager).commit(any());

        FullSyncResult resultado = useCase.executeFullSync(SyncTrigger.STARTUP);

        assertThat(resultado).isEqualTo(new FullSyncResult.Applied(7, 2));
        verify(catalogRepository, times(2)).replaceAll(any(), any(), any());
    }

    static Stream<Arguments> fallasNoTransitorias() {
        return Stream.of(
            Arguments.of(
                new DataIntegrityViolationException("violates foreign key constraint"),
                "La base de datos local rechazó el snapshot: DataIntegrityViolationException"
            ),
            Arguments.of(
                new InvalidDataAccessApiUsageException("No existe la fila 1 de sync_status"),
                "La base de datos local rechazó el snapshot: InvalidDataAccessApiUsageException"
            ),
            Arguments.of(
                new InvalidDataAccessResourceUsageException("relation does not exist"),
                "La base de datos local rechazó el snapshot: InvalidDataAccessResourceUsageException"
            ),
            Arguments.of(
                new IllegalTransactionStateException("No existing transaction found"),
                "La base de datos local rechazó el snapshot: IllegalTransactionStateException"
            ),
            Arguments.of(
                new IllegalStateException("un error de programación"),
                "Error inesperado al aplicar el snapshot: IllegalStateException"
            )
        );
    }

    @ParameterizedTest
    @MethodSource("fallasNoTransitorias")
    void unaFallaNoTransitoriaNoSeReintenta(RuntimeException falla, String descripcion) {
        when(provider.fetchSnapshot()).thenReturn(snapshot(7));
        doThrow(falla).when(catalogRepository).replaceAll(any(), any(), any());

        FullSyncResult resultado = useCase.executeFullSync(SyncTrigger.STARTUP);

        assertThat(resultado).isEqualTo(new FullSyncResult.Failed(SyncErrorType.SNAPSHOT_FAILED, descripcion, 1));
        assertThat(esperas).isEmpty();
        verify(provider, times(1)).fetchSnapshot();
        assertThat(estadoGuardado().getLastErrorDescription()).isEqualTo(descripcion);
    }

    @Test
    void siLaBaseSigueFallandoQuedaComoBaseNoDisponible() {
        when(provider.fetchSnapshot()).thenReturn(snapshot(7));
        doThrow(new DataAccessResourceFailureException("Unable to acquire JDBC Connection"))
            .doThrow(new DataAccessResourceFailureException("Unable to acquire JDBC Connection"))
            .doThrow(new DataAccessResourceFailureException("Unable to acquire JDBC Connection"))
            .doThrow(new DataAccessResourceFailureException("Unable to acquire JDBC Connection"))
            .when(catalogRepository)
            .replaceAll(any(), any(), any());

        FullSyncResult resultado = useCase.executeFullSync(SyncTrigger.STARTUP);

        String descripcion =
            "La base de datos local no respondió al aplicar el snapshot después de 4 intentos: DataAccessResourceFailureException";
        assertThat(resultado).isEqualTo(new FullSyncResult.Failed(SyncErrorType.DATABASE_UNAVAILABLE, descripcion, 4));
        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
        assertThat(estadoGuardado().getLastErrorType()).isEqualTo(SyncErrorType.DATABASE_UNAVAILABLE);
    }

    @Test
    void elErrorSeRegistraEnUnaTransaccionNuevaDespuesDelRollback() {
        TransactionStatus aplicar = new SimpleTransactionStatus();
        TransactionStatus registrarError = new SimpleTransactionStatus();
        when(txManager.getTransaction(any())).thenReturn(aplicar, registrarError);
        when(provider.fetchSnapshot()).thenReturn(snapshot(7));
        doThrow(new DataIntegrityViolationException("violates foreign key constraint"))
            .when(catalogRepository)
            .replaceAll(any(), any(), any());

        useCase.executeFullSync(SyncTrigger.STARTUP);

        InOrder orden = inOrder(txManager, catalogRepository, syncStatusRepository);
        orden.verify(txManager).getTransaction(any());
        orden.verify(catalogRepository).replaceAll(any(), any(), any());
        orden.verify(txManager).rollback(aplicar);
        orden.verify(txManager).getTransaction(any());
        orden.verify(syncStatusRepository).load();
        orden.verify(syncStatusRepository).save(any());
        orden.verify(txManager).commit(registrarError);
        verify(txManager, never()).commit(aplicar);
    }

    static Stream<SnapshotRetrievalException> fallasDeLaCatedraConElTokenEnLaCausa() {
        RuntimeException causa = new RuntimeException("GET http://catedra/api/synchronization/snapshot Authorization: Bearer " + TOKEN);
        return Stream.of(
            new SnapshotAccessDeniedException("La cátedra respondió 401 (sin code) al pedir el snapshot", causa),
            new SnapshotSourceUnavailableException("La cátedra no respondió al pedir el snapshot (HttpTimeoutException)", causa),
            new InvalidSnapshotException("Snapshot inválido: el cuerpo no es el JSON esperado", causa)
        );
    }

    @ParameterizedTest
    @MethodSource("fallasDeLaCatedraConElTokenEnLaCausa")
    void laDescripcionNoLlevaLaCausaDeLaFallaDeLaCatedra(SnapshotRetrievalException falla) {
        when(provider.fetchSnapshot()).thenThrow(falla);

        FullSyncResult.Failed resultado = (FullSyncResult.Failed) useCase.executeFullSync(SyncTrigger.STARTUP);

        assertThat(resultado.description()).doesNotContain(TOKEN).doesNotContain("Bearer").doesNotContain("http");
        assertThat(estadoGuardado().getLastErrorDescription()).isEqualTo(resultado.description());
    }

    static Stream<RuntimeException> fallasDeLaBaseConElTokenEnElMensaje() {
        return Stream.of(
            new DataIntegrityViolationException("insert ... values ('" + TOKEN + "')"),
            new DataAccessResourceFailureException("conexión cortada " + TOKEN)
        );
    }

    @ParameterizedTest
    @MethodSource("fallasDeLaBaseConElTokenEnElMensaje")
    void laDescripcionNoLlevaElMensajeDeLaFallaDeLaBase(RuntimeException falla) {
        when(provider.fetchSnapshot()).thenReturn(snapshot(7));
        doThrow(falla).when(catalogRepository).replaceAll(any(), any(), any());

        FullSyncResult.Failed resultado = (FullSyncResult.Failed) useCase.executeFullSync(SyncTrigger.STARTUP);

        assertThat(resultado.description()).doesNotContain(TOKEN).doesNotContain("insert").doesNotContain("cortada");
        assertThat(estadoGuardado().getLastErrorDescription()).isEqualTo(resultado.description());
    }

    @Test
    void siTampocoSePuedeRegistrarElErrorDevuelveElFalloSinPropagar() {
        when(provider.fetchSnapshot()).thenThrow(
            new SnapshotAccessDeniedException("La cátedra respondió 403 (sin code) al pedir el snapshot")
        );
        doThrow(new DataAccessResourceFailureException("Unable to acquire JDBC Connection")).when(syncStatusRepository).save(any());

        FullSyncResult resultado = useCase.executeFullSync(SyncTrigger.STARTUP);

        assertThat(resultado).isInstanceOf(FullSyncResult.Failed.class);
        assertThat(((FullSyncResult.Failed) resultado).errorType()).isEqualTo(SyncErrorType.INVALID_CREDENTIALS);
        verify(txManager).rollback(any());
    }

    @Test
    void unSnapshotAnteriorALaVersionLocalSeAplicaIgual() {
        when(syncStatusRepository.load()).thenReturn(estadoConVersion(9));
        when(provider.fetchSnapshot()).thenReturn(snapshot(7));

        FullSyncResult resultado = useCase.executeFullSync(SyncTrigger.MANUAL);

        assertThat(resultado).isEqualTo(new FullSyncResult.Applied(7, 1));
        assertThat(estadoGuardado().getLocalVersion()).isEqualTo(7L);
    }

    @Test
    void siLoInterrumpenMientrasEsperaDejaDeReintentarYConservaLaInterrupcion() {
        ExecuteFullSyncUseCaseImpl interrumpido = casoDeUsoConSleeper(espera -> {
            throw new InterruptedException("apagado");
        });
        when(provider.fetchSnapshot()).thenThrow(noDisponible());

        FullSyncResult resultado = interrumpido.executeFullSync(SyncTrigger.STARTUP);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(provider, times(1)).fetchSnapshot();
        assertThat(resultado).isInstanceOf(FullSyncResult.Failed.class);
        FullSyncResult.Failed fallo = (FullSyncResult.Failed) resultado;
        assertThat(fallo.errorType()).isEqualTo(SyncErrorType.SNAPSHOT_FAILED);
        assertThat(fallo.description()).startsWith("La sincronización se interrumpió mientras esperaba para reintentar.");
    }

    private ExecuteFullSyncUseCaseImpl casoDeUsoConSleeper(RetryBackoff.Sleeper sleeper) {
        return new ExecuteFullSyncUseCaseImpl(
            provider,
            catalogRepository,
            syncStatusRepository,
            new TransactionTemplate(txManager),
            Clock.fixed(AHORA, ZoneOffset.UTC),
            new RetryBackoff(DP_08, sleeper)
        );
    }

    /** El último estado guardado. */
    private SyncStatus estadoGuardado() {
        ArgumentCaptor<SyncStatus> captor = ArgumentCaptor.forClass(SyncStatus.class);
        verify(syncStatusRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private static SnapshotSourceUnavailableException noDisponible() {
        return new SnapshotSourceUnavailableException("La cátedra respondió 503 (code=SERVICE_UNAVAILABLE) al pedir el snapshot");
    }

    private static SyncStatus estadoConVersion(long version) {
        SyncStatus estado = SyncStatus.initial();
        estado.recordSnapshotApplied(version, SyncTrigger.STARTUP, CREADO);
        return estado;
    }

    private static CatalogSnapshot snapshot(long version) {
        return new CatalogSnapshot(version, AHORA, List.of(categoria(1)), List.of(profesional(15, 1)), List.of(horario(21, 15)));
    }

    private static ProfessionalCategory categoria(long id) {
        return new ProfessionalCategory(id, "Categoria " + id, null, true, CREADO, CREADO);
    }

    private static Professional profesional(long id, long categoryId) {
        return new Professional(id, categoryId, "Ana", "Gomez", true, CREADO, CREADO);
    }

    private static WeeklySchedule horario(long id, long professionalId) {
        LocalTime nueve = LocalTime.of(9, 0);
        return new WeeklySchedule(id, professionalId, DayOfWeek.MONDAY, nueve, nueve.plusHours(3), 30, true, CREADO, CREADO);
    }
}
