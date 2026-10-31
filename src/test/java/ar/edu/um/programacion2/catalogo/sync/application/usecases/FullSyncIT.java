package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.category;
import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.professional;
import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.schedule;
import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.adapter.JpaCatalogRepositoryAdapter;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.application.service.SyncService;
import ar.edu.um.programacion2.catalogo.sync.domain.model.FullSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.client.FakeCatedraServer;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.startup.EmptyDatabaseBeforeStartup;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * CU-02 de punta a punta: cátedra simulada (socket real), PostgreSQL real y las transacciones de verdad. Las esperas
 * entre reintentos son las cortas de {@code src/test} (10, 20 y 40 ms).
 * <p>
 * Para cortar la conexión a la base se usa {@link ConnectionCuttingCatalogRepository}, que existe solo en el contexto de
 * esta clase: la configuración que lo registra está anidada acá y se importa solo acá. Como {@code @IntegrationTest} fija
 * las clases del contexto, Spring no la detecta sola; y como es una {@code @TestConfiguration}, el escaneo de
 * componentes de los demás tests la excluye ({@code StartupFullSyncIT} lo verifica).
 */
@IntegrationTest
@Import(FullSyncIT.ConnectionCuttingConfiguration.class)
class FullSyncIT {

    private static final FakeCatedraServer CATEDRA = startCatedra();

    @Autowired
    private ApplicationContext context;

    @Autowired
    private SyncService syncService;

    @Autowired
    private SyncStatusRepository syncStatusRepository;

    @Autowired
    private ConnectionCuttingCatalogRepository cuttingRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void catedraSimulada(DynamicPropertyRegistry registry) {
        registry.add("catedra.api.url", () -> CATEDRA.baseUrl().toString());
    }

    @AfterAll
    static void apagarCatedra() {
        CATEDRA.close();
    }

    @BeforeEach
    @AfterEach
    void volverAlEstadoInicial() {
        cuttingRepository.cutNextCalls(0);
        SyncTestDatabase.reset(transactionTemplate, em);
    }

    @Test
    void aplicaElSnapshotDeLaCatedraConLaCredencialTecnica() {
        CATEDRA.respondJson(200, snapshot(7, List.of(category(1), category(2)), List.of(professional(15, 1)), List.of(schedule(21, 15))));
        int pedidosAntes = CATEDRA.requests().size();

        FullSyncResult resultado = syncService.executeFullSync(SyncTrigger.MANUAL);

        assertThat(resultado).isEqualTo(new FullSyncResult.Applied(7, 1));
        assertThat(SyncTestDatabase.categoryIds(transactionTemplate, em)).containsExactly(1L, 2L);
        assertThat(SyncTestDatabase.professionalIds(transactionTemplate, em)).containsExactly(15L);
        assertThat(SyncTestDatabase.scheduleIds(transactionTemplate, em)).containsExactly(21L);
        SyncStatus estado = estado();
        assertThat(estado.getLocalVersion()).isEqualTo(7L);
        assertThat(estado.getLastSuccessPath()).isEqualTo(SyncPath.SNAPSHOT);
        assertThat(estado.getLastSuccessTrigger()).isEqualTo(SyncTrigger.MANUAL);
        List<FakeCatedraServer.ReceivedRequest> pedidos = CATEDRA.requests().subList(pedidosAntes, CATEDRA.requests().size());
        assertThat(pedidos).containsExactly(
            new FakeCatedraServer.ReceivedRequest("GET", "/api/synchronization/snapshot", "Bearer token-falso-solo-para-tests")
        );
    }

    @Test
    void siLaCatedraSigueCaidaLaCopiaYLaVersionAnterioresQuedanIntactas() {
        aplicarCopiaBuena();
        CATEDRA.respondProblem(503, "SERVICE_UNAVAILABLE");
        int pedidosAntes = CATEDRA.requests().size();

        FullSyncResult resultado = syncService.executeFullSync(SyncTrigger.MANUAL);

        assertThat(resultado).isInstanceOf(FullSyncResult.Failed.class);
        assertThat(((FullSyncResult.Failed) resultado).errorType()).isEqualTo(SyncErrorType.CATEDRA_UNAVAILABLE);
        assertThat(CATEDRA.requests().size() - pedidosAntes).isEqualTo(4);
        assertThat(SyncTestDatabase.categoryIds(transactionTemplate, em)).containsExactly(1L);
        SyncStatus estado = estado();
        assertThat(estado.getLocalVersion()).isEqualTo(5L);
        assertThat(estado.getLastErrorType()).isEqualTo(SyncErrorType.CATEDRA_UNAVAILABLE);
        assertThat(estado.getLastErrorDescription()).isEqualTo(
            "La cátedra no estuvo disponible después de 4 intentos: La cátedra respondió 503 (code=SERVICE_UNAVAILABLE) al pedir el snapshot"
        );
    }

    @Test
    void unSnapshotConReferenciasRotasNoCambiaNada() {
        aplicarCopiaBuena();
        CATEDRA.respondJson(200, snapshot(7, List.of(category(2)), List.of(professional(16, 999)), List.of()));
        int pedidosAntes = CATEDRA.requests().size();

        FullSyncResult resultado = syncService.executeFullSync(SyncTrigger.MANUAL);

        assertThat(((FullSyncResult.Failed) resultado).errorType()).isEqualTo(SyncErrorType.SNAPSHOT_FAILED);
        assertThat(CATEDRA.requests().size() - pedidosAntes).isEqualTo(1);
        assertThat(SyncTestDatabase.categoryIds(transactionTemplate, em)).containsExactly(1L);
        SyncStatus estado = estado();
        assertThat(estado.getLocalVersion()).isEqualTo(5L);
        assertThat(estado.getLastErrorDescription()).contains("el profesional 16 apunta a la categoría 999");
    }

    @Test
    void unaConexionCortadaAMitadDeLaTransaccionSeReintentaYDespuesAplica() {
        aplicarCopiaBuena();
        CATEDRA.respondJson(200, snapshot(7, List.of(category(1), category(2)), List.of(), List.of()));
        cuttingRepository.cutNextCalls(1);

        FullSyncResult resultado = syncService.executeFullSync(SyncTrigger.MANUAL);

        assertThat(resultado).isEqualTo(new FullSyncResult.Applied(7, 2));
        assertThat(cuttingRepository.cuts()).isEqualTo(1);
        assertThat(SyncTestDatabase.categoryIds(transactionTemplate, em)).containsExactly(1L, 2L);
        assertThat(estado().getLocalVersion()).isEqualTo(7L);
    }

    @Test
    void siLaConexionSeCortaEnTodosLosIntentosQuedanLaCopiaYLaVersionAnteriores() {
        aplicarCopiaBuena();
        CATEDRA.respondJson(200, snapshot(7, List.of(category(1), category(2)), List.of(), List.of()));
        cuttingRepository.cutNextCalls(4);

        FullSyncResult resultado = syncService.executeFullSync(SyncTrigger.MANUAL);

        assertThat(resultado).isInstanceOf(FullSyncResult.Failed.class);
        assertThat(((FullSyncResult.Failed) resultado).attempts()).isEqualTo(4);
        assertThat(cuttingRepository.cuts()).isEqualTo(4);
        assertThat(SyncTestDatabase.categoryIds(transactionTemplate, em)).containsExactly(1L);
        SyncStatus estado = estado();
        assertThat(estado.getLocalVersion()).isEqualTo(5L);
        // El error se registra en una transacción nueva, con otra conexión: la cortada ya no existe.
        assertThat(estado.getLastErrorType()).isEqualTo(SyncErrorType.DATABASE_UNAVAILABLE);
        assertThat(estado.getLastErrorDescription()).startsWith(
            "La base de datos local no respondió al aplicar el snapshot después de 4 intentos: "
        );
    }

    @Test
    void laLimpiezaDeArranqueDeLosTestsDeCU01NoEstaEnUnContextoQueNoLaImporta() {
        // EmptyDatabaseBeforeStartup vacía la base al arrancar el contexto. Si el escaneo de componentes la encontrara,
        // estaría en todos los contextos de test; este no la importa, así que no tiene que estar.
        assertThat(context.getBeanNamesForType(EmptyDatabaseBeforeStartup.class)).isEmpty();
    }

    @Test
    void noSePuedeLlamarDentroDeUnaTransaccionYNoLlegaAPedirElSnapshot() {
        CATEDRA.respondJson(200, snapshot(7, List.of(category(1)), List.of(), List.of()));
        int pedidosAntes = CATEDRA.requests().size();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> syncService.executeFullSync(SyncTrigger.MANUAL)))
            .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(CATEDRA.requests().size() - pedidosAntes).isZero();
        assertThat(estado().hasLocalCopy()).isFalse();
    }

    private void aplicarCopiaBuena() {
        CATEDRA.respondJson(200, snapshot(5, List.of(category(1)), List.of(), List.of()));
        assertThat(syncService.executeFullSync(SyncTrigger.STARTUP)).isEqualTo(new FullSyncResult.Applied(5, 1));
    }

    private SyncStatus estado() {
        return transactionTemplate.execute(tx -> syncStatusRepository.load());
    }

    private static FakeCatedraServer startCatedra() {
        try {
            return FakeCatedraServer.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Solo para esta clase: reemplaza al {@link CatalogRepository} del contexto ({@code @Primary}) por un decorador del
     * adaptador real que puede cortar la conexión de la transacción antes de delegar.
     */
    @TestConfiguration
    static class ConnectionCuttingConfiguration {

        @Bean
        @Primary
        ConnectionCuttingCatalogRepository connectionCuttingCatalogRepository(
            JpaCatalogRepositoryAdapter adapter,
            EntityManager em,
            PostgreSQLContainer database
        ) {
            return new ConnectionCuttingCatalogRepository(adapter, em, database);
        }
    }

    /**
     * Antes de delegar, mata en PostgreSQL ({@code pg_terminate_backend}) la conexión de la transacción en curso, desde
     * otra conexión. Es lo mismo que le pasa a la aplicación si PostgreSQL se reinicia a mitad del reemplazo: la
     * siguiente sentencia falla con {@code 57P01} y el rollback sobre la conexión muerta también.
     */
    static class ConnectionCuttingCatalogRepository implements CatalogRepository {

        private final CatalogRepository delegate;
        private final EntityManager em;
        private final PostgreSQLContainer database;
        private final AtomicInteger pendingCuts = new AtomicInteger();
        private final AtomicInteger cuts = new AtomicInteger();

        ConnectionCuttingCatalogRepository(CatalogRepository delegate, EntityManager em, PostgreSQLContainer database) {
            this.delegate = delegate;
            this.em = em;
            this.database = database;
        }

        void cutNextCalls(int times) {
            pendingCuts.set(times);
            cuts.set(0);
        }

        int cuts() {
            return cuts.get();
        }

        @Override
        public void replaceAll(
            List<ProfessionalCategory> categories,
            List<Professional> professionals,
            List<WeeklySchedule> weeklySchedules
        ) {
            if (pendingCuts.getAndUpdate(pending -> Math.max(0, pending - 1)) > 0) {
                Object pid = em.createNativeQuery("select pg_backend_pid()").getSingleResult();
                try (
                    Connection admin = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
                    Statement statement = admin.createStatement()
                ) {
                    statement.execute("select pg_terminate_backend(" + pid + ")");
                } catch (SQLException e) {
                    throw new IllegalStateException("No se pudo cortar la conexión", e);
                }
                cuts.incrementAndGet();
            }
            delegate.replaceAll(categories, professionals, weeklySchedules);
        }
    }
}
