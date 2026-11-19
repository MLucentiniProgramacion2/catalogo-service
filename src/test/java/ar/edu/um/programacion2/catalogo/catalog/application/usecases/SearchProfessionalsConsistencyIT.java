package ar.edu.um.programacion2.catalogo.catalog.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.application.service.CatalogService;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.PageQuery;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalSearchCriteria;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalWithCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ResultPage;
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
 * La versión, la página y el total que responde CU-05 corresponden entre sí aunque un snapshot se confirme después de
 * leer la versión. Es la misma técnica que {@link ListCategoriesConsistencyIT}, ahora con tres lecturas: la versión, la
 * página y el count.
 * <p>
 * La versión 1 tiene 3 profesionales (1, 2 y 3) y la 2, confirmada en el medio, tiene 5 (10 a 14): la página de tamaño 2
 * y el total permiten distinguir de qué versión sale cada dato. La segunda conexión se abre con {@link DriverManager},
 * fuera del pool, porque Hikari tiene una sola conexión en los tests y la tiene tomada la transacción que se prueba.
 */
@IntegrationTest
@Import(SearchProfessionalsConsistencyIT.SnapshotInTheMiddleConfiguration.class)
class SearchProfessionalsConsistencyIT {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-10T13:45:00Z");
    private static final ProfessionalSearchCriteria TODOS = ProfessionalSearchCriteria.of(null, null, null);
    private static final PageQuery PRIMERA_DE_2 = new PageQuery(0, 2);

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
            catalogRepository.replaceAll(List.of(categoria()), List.of(profesional(1), profesional(2), profesional(3)), List.of());
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
    void unSnapshotConfirmadoDespuesDeLeerLaVersionNoMezclaVersionPaginaNiTotal() {
        snapshotInTheMiddle.arm();

        Versioned<ResultPage<ProfessionalWithCategory>> respuesta = catalogService.searchProfessionals(TODOS, PRIMERA_DE_2);

        assertThat(snapshotInTheMiddle.applied()).as("el snapshot 2 se confirmó en el medio").isTrue();
        assertThat(respuesta.version()).isEqualTo(1);
        assertThat(ids(respuesta.content())).containsExactly(1L, 2L);
        assertThat(respuesta.content().totalElements()).isEqualTo(3);

        // Lo confirmado sí quedó: la búsqueda siguiente ya ve la versión 2 con sus profesionales.
        Versioned<ResultPage<ProfessionalWithCategory>> siguiente = catalogService.searchProfessionals(TODOS, PRIMERA_DE_2);
        assertThat(siguiente.version()).isEqualTo(2);
        assertThat(ids(siguiente.content())).containsExactly(10L, 11L);
        assertThat(siguiente.content().totalElements()).isEqualTo(5);
    }

    @Test
    void controlConReadCommittedLaMismaIntercalacionMezclaVersiones() {
        snapshotInTheMiddle.arm();

        // TransactionTemplate usa el nivel por defecto de la base: en PostgreSQL, READ COMMITTED.
        Versioned<ResultPage<ProfessionalWithCategory>> mezcla = transactionTemplate.execute(tx -> {
            long version = versionProvider.findAppliedVersion().orElseThrow();
            return new Versioned<>(version, catalogRepository.searchProfessionals(TODOS, PRIMERA_DE_2));
        });

        assertThat(mezcla.version()).isEqualTo(1);
        assertThat(ids(mezcla.content())).containsExactly(10L, 11L);
        assertThat(mezcla.content().totalElements()).isEqualTo(5);
    }

    @Test
    void laConexionVuelveAlPoolConElNivelPorDefecto() {
        catalogService.searchProfessionals(TODOS, PRIMERA_DE_2);

        // El pool de test tiene una sola conexión: es la misma que acaba de usar el caso de uso.
        Object nivel = transactionTemplate.execute(tx -> em.createNativeQuery("show transaction_isolation").getSingleResult());
        assertThat(nivel).isEqualTo("read committed");
    }

    private static List<Long> ids(ResultPage<ProfessionalWithCategory> pagina) {
        return pagina.items().stream().map(r -> r.professional().id()).toList();
    }

    private static ProfessionalCategory categoria() {
        return new ProfessionalCategory(1, "Categoria 1", null, true, CREADO, CREADO);
    }

    private static Professional profesional(long id) {
        return new Professional(id, 1, "Nombre", "Apellido " + id, true, CREADO, CREADO);
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
     * profesionales nuevos (10 a 14) y versión 2. Se desarma solo, para no repetirlo en la lectura siguiente.
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
                statement.executeUpdate("delete from professional");
                statement.executeUpdate(
                    """
                    insert into professional (id, category_id, first_name, last_name, enabled, created_at, updated_at)
                    select id, 1, 'Nombre', 'Apellido ' || id, true, now(), now() from generate_series(10, 14) as id
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
