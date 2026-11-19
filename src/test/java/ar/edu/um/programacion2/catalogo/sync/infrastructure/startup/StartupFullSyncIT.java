package ar.edu.um.programacion2.catalogo.sync.infrastructure.startup;

import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.category;
import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.professional;
import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.schedule;
import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.adapter.JpaCatalogRepositoryAdapter;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.client.FakeCatedraServer;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CU-01 de punta a punta: con la base vacía, al levantar la aplicación se carga el snapshot y queda la versión con el
 * disparador "arranque".
 * <p>
 * El disparador está prendido solo acá y en {@link StartupFullSyncFailureIT}. Corre durante la creación del contexto,
 * antes de cualquier test: {@link EmptyDatabaseBeforeStartup} vacía la base justo antes, y la cátedra simulada ya está
 * respondiendo. El executor de los tests es sincrónico, así que al empezar los tests el arranque ya terminó.
 * <p>
 * Recorre el camino real: el listener, el service, CU-01, la lectura de solo lectura y CU-02 con su
 * {@code Propagation.NEVER}. Si alguien pusiera {@code @Transactional} en CU-01, CU-02 fallaría y este test también.
 */
@IntegrationTest
@Import(EmptyDatabaseBeforeStartup.class)
@TestPropertySource(properties = "sync.startup.enabled=true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StartupFullSyncIT {

    private static final FakeCatedraServer CATEDRA = startCatedra();

    @Autowired
    private ApplicationContext context;

    @Autowired
    private SyncStatusRepository syncStatusRepository;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void catedraSimulada(DynamicPropertyRegistry registry) {
        registry.add("catedra.api.url", () -> CATEDRA.baseUrl().toString());
    }

    @AfterAll
    void dejarLaBaseVaciaYApagarLaCatedra() {
        // Lo que cargó el arranque no puede quedar para los tests siguientes, que comparten la base.
        SyncTestDatabase.reset(transactionTemplate, em);
        CATEDRA.close();
    }

    @Test
    void conLaBaseVaciaElArranqueCargaElSnapshotYDejaLaVersionConElDisparadorDeArranque() {
        SyncStatus estado = transactionTemplate.execute(tx -> syncStatusRepository.load());
        assertThat(estado.getLocalVersion()).isEqualTo(7L);
        assertThat(estado.getLastSuccessPath()).isEqualTo(SyncPath.SNAPSHOT);
        assertThat(estado.getLastSuccessTrigger()).isEqualTo(SyncTrigger.STARTUP);
        assertThat(estado.getLastSuccessAt()).isNotNull();
        assertThat(estado.getLastErrorType()).isNull();
        assertThat(SyncTestDatabase.categoryIds(transactionTemplate, em)).containsExactly(1L, 2L);
        assertThat(SyncTestDatabase.professionalIds(transactionTemplate, em)).containsExactly(15L);
        assertThat(SyncTestDatabase.scheduleIds(transactionTemplate, em)).containsExactly(21L);
        assertThat(CATEDRA.requests()).containsExactly(
            new FakeCatedraServer.ReceivedRequest("GET", "/api/synchronization/snapshot", "Bearer token-falso-solo-para-tests")
        );
    }

    @Test
    void laLimpiezaDeArranqueEstaPorqueEsteTestLaImporta() {
        // Control positivo de la verificación de FullSyncIT, que comprueba que en un contexto sin el @Import no está.
        assertThat(context.getBeanNamesForType(EmptyDatabaseBeforeStartup.class)).hasSize(1);
    }

    @Test
    void elRepositorioDelCatalogoEsElAdaptadorJpaYNoElDecoradorDeFullSyncIT() {
        // FullSyncIT reemplaza el repositorio por uno que corta la conexión, con una @TestConfiguration anidada. Los
        // @SpringBootTest escanean test-classes: acá se confirma que esa configuración no se filtra a otros contextos.
        assertThat(AopUtils.getTargetClass(catalogRepository)).isEqualTo(JpaCatalogRepositoryAdapter.class);
    }

    private static FakeCatedraServer startCatedra() {
        try {
            FakeCatedraServer catedra = FakeCatedraServer.start();
            catedra.respondJson(
                200,
                snapshot(7, List.of(category(1), category(2)), List.of(professional(15, 1)), List.of(schedule(21, 15)))
            );
            return catedra;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
