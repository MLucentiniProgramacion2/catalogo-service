package ar.edu.um.programacion2.catalogo.catalog.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.application.service.CatalogService;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Versioned;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.LocalCatalogVersionProvider;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.adapter.JpaLocalCatalogVersionAdapter;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * La versión y las categorías que responde CU-06 corresponden entre sí aunque un snapshot se confirme entre las dos
 * lecturas.
 * <p>
 * {@link SnapshotInTheMiddle} decora al adaptador real de la versión ({@code @Primary}, solo en esta clase): después de
 * leerla, confirma un "snapshot 2" por una segunda conexión. La abre con {@link DriverManager}, fuera del pool: en los
 * tests Hikari tiene una sola conexión y la tiene tomada la transacción que se está probando, así que pedirle otra al
 * {@code DataSource} esperaría hasta el timeout.
 * <p>
 * El control negativo hace las mismas dos lecturas con el nivel por defecto ({@code READ COMMITTED}) y ve la mezcla: así
 * se comprueba que la intercalación pasa de verdad y que lo que la evita es el aislamiento del caso de uso.
 */
@IntegrationTest
@Import(ListCategoriesConsistencyIT.SnapshotInTheMiddleConfiguration.class)
class ListCategoriesConsistencyIT {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-10T13:45:00Z");

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private LocalCatalogVersionProvider versionProvider;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private SyncStatusRepository syncStatusRepository;

    @Autowired
    private SnapshotInTheMiddle snapshotInTheMiddle;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @BeforeEach
    void aplicarLaVersion1() {
        SyncTestDatabase.reset(transactionTemplate, em);
        transactionTemplate.executeWithoutResult(tx -> {
            catalogRepository.replaceAll(List.of(categoria(1), categoria(2)), List.of(), List.of());
            SyncStatus estado = syncStatusRepository.load();
            estado.recordSnapshotApplied(1, SyncTrigger.STARTUP, T1);
            syncStatusRepository.save(estado);
        });
    }

    @AfterEach
    void limpiar() {
        snapshotInTheMiddle.disarm();
        SyncTestDatabase.reset(transactionTemplate, em);
    }

    @Test
    void unSnapshotConfirmadoEntreLasDosLecturasNoMezclaVersiones() {
        snapshotInTheMiddle.arm();

        Versioned<List<ProfessionalCategory>> respuesta = catalogService.listCategories(null);

        assertThat(snapshotInTheMiddle.applied()).as("el snapshot 2 se confirmó en el medio").isTrue();
        assertThat(respuesta.version()).isEqualTo(1);
        assertThat(respuesta.content()).extracting(ProfessionalCategory::id).containsExactly(1L, 2L);

        // Lo confirmado sí quedó: la consulta siguiente ya ve la versión 2 con sus categorías.
        Versioned<List<ProfessionalCategory>> siguiente = catalogService.listCategories(null);
        assertThat(siguiente.version()).isEqualTo(2);
        assertThat(siguiente.content()).extracting(ProfessionalCategory::id).containsExactly(10L, 11L);
    }

    @Test
    void controlConReadCommittedLaMismaIntercalacionMezclaVersiones() {
        snapshotInTheMiddle.arm();

        // TransactionTemplate usa el nivel por defecto de la base: en PostgreSQL, READ COMMITTED.
        Versioned<List<ProfessionalCategory>> mezcla = transactionTemplate.execute(tx -> {
            long version = versionProvider.findAppliedVersion().orElseThrow();
            return new Versioned<>(version, catalogRepository.findCategories(null));
        });

        assertThat(mezcla.version()).isEqualTo(1);
        assertThat(mezcla.content()).extracting(ProfessionalCategory::id).containsExactly(10L, 11L);
    }

    @Test
    void laConexionVuelveAlPoolConElNivelPorDefecto() {
        catalogService.listCategories(null);

        // El pool de test tiene una sola conexión: es la misma que acaba de usar el caso de uso.
        Object nivel = transactionTemplate.execute(tx -> em.createNativeQuery("show transaction_isolation").getSingleResult());
        assertThat(nivel).isEqualTo("read committed");
    }

    private static ProfessionalCategory categoria(long id) {
        return new ProfessionalCategory(id, "Categoria " + id, null, true, CREADO, CREADO);
    }

    @TestConfiguration
    static class SnapshotInTheMiddleConfiguration {

        @Bean
        @Primary
        SnapshotInTheMiddle snapshotInTheMiddle(JpaLocalCatalogVersionAdapter adapter, PostgreSQLContainer database) {
            return new SnapshotInTheMiddle(adapter, database);
        }
    }

    /**
     * Después de leer la versión, si está armado, confirma por otra conexión lo mismo que confirmaría un snapshot:
     * categorías nuevas (10 y 11) y versión 2. Se desarma solo, para no repetirlo en la lectura siguiente.
     */
    static class SnapshotInTheMiddle implements LocalCatalogVersionProvider {

        private final LocalCatalogVersionProvider delegate;
        private final PostgreSQLContainer database;
        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicBoolean applied = new AtomicBoolean();

        SnapshotInTheMiddle(LocalCatalogVersionProvider delegate, PostgreSQLContainer database) {
            this.delegate = delegate;
            this.database = database;
        }

        void arm() {
            applied.set(false);
            armed.set(true);
        }

        void disarm() {
            armed.set(false);
        }

        boolean applied() {
            return applied.get();
        }

        @Override
        public OptionalLong findAppliedVersion() {
            OptionalLong version = delegate.findAppliedVersion();
            if (armed.getAndSet(false)) {
                commitSnapshot2();
                applied.set(true);
            }
            return version;
        }

        private void commitSnapshot2() {
            try (
                Connection otra = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
                Statement statement = otra.createStatement()
            ) {
                otra.setAutoCommit(false);
                // Si la lectura en curso bloqueara al snapshot, esto fallaría en vez de colgar el test.
                statement.execute("set local lock_timeout = '2s'");
                statement.executeUpdate("delete from professional_category");
                statement.executeUpdate(
                    """
                    insert into professional_category (id, name, description, enabled, created_at, updated_at) values
                        (10, 'Categoria 10', null, true, now(), now()),
                        (11, 'Categoria 11', null, true, now(), now())
                    """
                );
                statement.executeUpdate(
                    """
                    update sync_status set local_version = 2, last_success_at = now(), last_success_path = 'SNAPSHOT',
                        last_success_trigger = 'MANUAL'
                    where id = 1
                    """
                );
                otra.commit();
            } catch (SQLException e) {
                throw new IllegalStateException("No se pudo confirmar el snapshot 2 desde otra conexión", e);
            }
        }
    }
}
