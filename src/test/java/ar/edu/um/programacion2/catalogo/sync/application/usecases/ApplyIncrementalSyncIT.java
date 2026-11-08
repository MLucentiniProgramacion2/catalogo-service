package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.PageQuery;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalSearchCriteria;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalWithCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ResultPage;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogUpdateNotice;
import ar.edu.um.programacion2.catalogo.sync.domain.model.ChangedEntities;
import ar.edu.um.programacion2.catalogo.sync.domain.model.IncrementalSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.PublishedVersions;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.model.VersionChanges;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.CatalogChangesProvider;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ProcessedEventRepository;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CU-04 contra PostgreSQL real: lo que se confirma o se deshace en cada transacción. Redis es un proveedor falso; la
 * base, los adaptadores y el {@code JpaTransactionManager} son los de la aplicación.
 * <p>
 * El caso de uso se arma a mano con esos beans, sin otro contexto de Spring: así se le puede pasar un
 * {@code TransactionManager} que confirma de verdad y después avisa que falló (el commit dudoso). Con las estadísticas
 * de Hibernate prendidas, como {@code JpaCatalogRepositorySaveAllIT}: comparten el contexto.
 */
@IntegrationTest
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ApplyIncrementalSyncIT {

    private static final Instant CREADO = Instant.parse("2026-09-01T03:00:00Z");
    private static final Instant CAMBIADO = Instant.parse("2026-11-08T01:19:34.423296Z");
    private static final CatalogUpdateNotice AVISO = new CatalogUpdateNotice("evento-90", 90);

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private SyncStatusRepository syncStatusRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private SyncErrorRecorder syncErrorRecorder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private final FakeRedis redis = new FakeRedis();

    @BeforeEach
    void copiaEnLaVersion88() {
        SyncTestDatabase.reset(transactionTemplate, em);
        transactionTemplate.executeWithoutResult(tx -> {
            catalogRepository.replaceAll(
                List.of(new ProfessionalCategory(101, "Clinica", null, true, CREADO, CREADO)),
                List.of(profesional(201, true, CREADO), profesional(202, true, CREADO)),
                List.<WeeklySchedule>of()
            );
            var estado = syncStatusRepository.load();
            estado.recordSnapshotApplied(88, SyncTrigger.STARTUP, CREADO);
            syncStatusRepository.save(estado);
        });
        // La 89 da de baja al 201 y la 90 al 202, como hace la cátedra con el 201 cada hora.
        redis.version(89, profesional(201, false, CAMBIADO));
        redis.version(90, profesional(202, false, CAMBIADO));
    }

    @AfterEach
    void limpiar() {
        SyncTestDatabase.reset(transactionTemplate, em);
    }

    @Test
    void unaVersionDejaLosDatosYLaVersionJuntos() {
        IncrementalSyncResult resultado = casoDeUso(catalogRepository, transactionTemplate).applyIncremental(
            SyncTrigger.KAFKA,
            88,
            redis.pendientes(89, 89)
        );

        assertThat(resultado).isEqualTo(new IncrementalSyncResult.Completed(89, 1));
        assertThat(fila("select local_version::text || ' ' || last_success_path || ' ' || last_success_trigger from sync_status")).isEqualTo(
            "89 INCREMENTAL KAFKA"
        );
        assertThat(fila("select enabled::text from professional where id = 201")).isEqualTo("false");
        assertThat(fila("select enabled::text from professional where id = 202")).isEqualTo("true");
    }

    @Test
    void siFallaLaSegundaDeDosLaPrimeraQuedaYLaSegundaNoNiElEvento() {
        // Rechaza la 90 después de escribirla: tiene que deshacerse entera, también lo ya escrito.
        CatalogRepository rechazaLa90 = new DelegatingCatalogRepository(catalogRepository) {
            @Override
            public void saveAll(List<ProfessionalCategory> c, List<Professional> professionals, List<WeeklySchedule> s) {
                super.saveAll(c, professionals, s);
                if (professionals.stream().anyMatch(p -> p.id() == 202)) {
                    throw new InvalidDataAccessApiUsageException("la base rechazó la versión");
                }
            }
        };

        IncrementalSyncResult resultado = casoDeUso(rechazaLa90, transactionTemplate).applyIncremental(
            SyncTrigger.KAFKA,
            88,
            redis.pendientes(89, 90),
            AVISO
        );

        assertThat(resultado).isInstanceOf(IncrementalSyncResult.Failed.class);
        assertThat(((IncrementalSyncResult.Failed) resultado).errorType()).isEqualTo(SyncErrorType.INCREMENTAL_FAILED);
        assertThat(fila("select local_version::text || ' ' || last_error_type from sync_status")).isEqualTo("89 INCREMENTAL_FAILED");
        assertThat(fila("select enabled::text from professional where id = 201")).isEqualTo("false");
        assertThat(fila("select enabled::text from professional where id = 202")).isEqualTo("true");
        assertThat(SyncTestDatabase.processedEventIds(transactionTemplate, em)).isEmpty();
    }

    @Test
    void elEventoQuedaConLaUltimaVersion() {
        casoDeUso(catalogRepository, transactionTemplate).applyIncremental(SyncTrigger.KAFKA, 88, redis.pendientes(89, 90), AVISO);

        assertThat(fila("select local_version::text from sync_status")).isEqualTo("90");
        assertThat(SyncTestDatabase.processedEventIds(transactionTemplate, em)).containsExactly("evento-90");
    }

    @Test
    void reaplicarUnaVersionNoCambiaNadaYCuestaUnaSentenciaMenos() {
        Statistics estadisticas = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        ApplyIncrementalSyncUseCaseImpl useCase = casoDeUso(catalogRepository, transactionTemplate);

        estadisticas.clear();
        useCase.applyIncremental(SyncTrigger.KAFKA, 88, redis.pendientes(89, 89));
        long primeraVez = estadisticas.getPrepareStatementCount();
        String despuesDeLaPrimera = copia();

        // La versión local vuelve a la 88, como si la 89 llegara de nuevo.
        transactionTemplate.executeWithoutResult(tx -> em.createNativeQuery("update sync_status set local_version = 88").executeUpdate());
        estadisticas.clear();
        useCase.applyIncremental(SyncTrigger.KAFKA, 88, redis.pendientes(89, 89));
        long reaplicada = estadisticas.getPrepareStatementCount();

        assertThat(copia()).isEqualTo(despuesDeLaPrimera);
        // select de sync_status, de la categoría y del profesional (el del merge), update del profesional y de sync_status.
        assertThat(primeraVez).isEqualTo(5);
        // Lo mismo sin el update del profesional, que ya tenía ese estado.
        assertThat(reaplicada).isEqualTo(4);
    }

    @Test
    void siElCommitFallaPeroLaBaseConfirmoElReintentoSigueConLaSiguiente() {
        TransactionTemplate confirmaYFallaUnaVez = new TransactionTemplate(new CommitsThenFailsOnce(transactionManager));

        IncrementalSyncResult resultado = casoDeUso(catalogRepository, confirmaYFallaUnaVez).applyIncremental(
            SyncTrigger.KAFKA,
            88,
            redis.pendientes(89, 90),
            AVISO
        );

        assertThat(resultado).isEqualTo(new IncrementalSyncResult.Completed(90, 2));
        assertThat(fila("select local_version::text from sync_status")).isEqualTo("90");
        assertThat(fila("select string_agg(enabled::text, ',' order by id) from professional")).isEqualTo("false,false");
        assertThat(fila("select coalesce(last_error_type, 'sin error') from sync_status")).isEqualTo("sin error");
        assertThat(SyncTestDatabase.processedEventIds(transactionTemplate, em)).containsExactly("evento-90");
    }

    @Test
    void laFallaDelCommitEsDeLasQueSeReintentan() {
        // Si TransactionSystemException no estuviera en DatabaseFailures, el commit dudoso nunca llegaría a reintentarse.
        assertThat(DatabaseFailures.isTransient(new TransactionSystemException("commit"))).isTrue();
    }

    private ApplyIncrementalSyncUseCaseImpl casoDeUso(CatalogRepository catalog, TransactionTemplate transactions) {
        return new ApplyIncrementalSyncUseCaseImpl(
            redis,
            catalog,
            syncStatusRepository,
            processedEventRepository,
            transactions,
            Clock.systemUTC(),
            new RetryBackoff(List.of(Duration.ZERO, Duration.ZERO, Duration.ZERO), duration -> {}),
            syncErrorRecorder,
            new IncrementalTimeBudget(Duration.ofMinutes(1))
        );
    }

    private String copia() {
        return fila(
            "select string_agg(id || ':' || enabled || ':' || updated_at, ',' order by id) from professional"
        ) + "|" + fila("select local_version::text from sync_status");
    }

    private String fila(String sql) {
        return transactionTemplate.execute(tx -> (String) em.createNativeQuery(sql, String.class).getSingleResult());
    }

    private static Professional profesional(long id, boolean habilitado, Instant actualizado) {
        return new Professional(id, 101, "Ana", "Torres", habilitado, CREADO, actualizado);
    }

    /** Redis falso: cada versión cambia un profesional, y el Hash tiene su estado. */
    private static final class FakeRedis implements CatalogChangesProvider {

        private final Map<Long, Professional> profesionales = new HashMap<>();
        private final SortedMap<Long, VersionChanges> cambios = new TreeMap<>();

        void version(long version, Professional profesional) {
            profesionales.put(profesional.id(), profesional);
            cambios.put(version, new VersionChanges(version, Set.of(), Set.of(profesional.id()), Set.of()));
        }

        SortedMap<Long, VersionChanges> pendientes(long desde, long hasta) {
            return new TreeMap<>(cambios.subMap(desde, hasta + 1));
        }

        @Override
        public PublishedVersions readPublishedVersions() {
            return new PublishedVersions(cambios.lastKey(), 1);
        }

        @Override
        public SortedMap<Long, VersionChanges> readChanges(long fromVersion, long toVersion) {
            return pendientes(fromVersion, toVersion);
        }

        @Override
        public ChangedEntities readEntities(VersionChanges changes) {
            return new ChangedEntities(List.of(), changes.professionalIds().stream().map(profesionales::get).toList(), List.of());
        }
    }

    /** Confirma de verdad la primera transacción y después lanza, como una conexión que se corta durante el commit. */
    private static final class CommitsThenFailsOnce implements PlatformTransactionManager {

        private final PlatformTransactionManager delegate;
        private final AtomicBoolean failed = new AtomicBoolean();

        CommitsThenFailsOnce(PlatformTransactionManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return delegate.getTransaction(definition);
        }

        @Override
        public void commit(TransactionStatus status) {
            delegate.commit(status);
            if (failed.compareAndSet(false, true)) {
                throw new TransactionSystemException("se cortó la conexión durante el commit");
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
            delegate.rollback(status);
        }
    }

    /** Delega todo en el adaptador real; los tests sobrescriben lo que quieren hacer fallar. */
    private static class DelegatingCatalogRepository implements CatalogRepository {

        private final CatalogRepository delegate;

        DelegatingCatalogRepository(CatalogRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public void replaceAll(List<ProfessionalCategory> c, List<Professional> p, List<WeeklySchedule> s) {
            delegate.replaceAll(c, p, s);
        }

        @Override
        public void saveAll(List<ProfessionalCategory> c, List<Professional> p, List<WeeklySchedule> s) {
            delegate.saveAll(c, p, s);
        }

        @Override
        public Set<Long> findExistingCategoryIds(Collection<Long> ids) {
            return delegate.findExistingCategoryIds(ids);
        }

        @Override
        public Set<Long> findExistingProfessionalIds(Collection<Long> ids) {
            return delegate.findExistingProfessionalIds(ids);
        }

        @Override
        public List<ProfessionalCategory> findCategories(Boolean enabled) {
            return delegate.findCategories(enabled);
        }

        @Override
        public ResultPage<ProfessionalWithCategory> searchProfessionals(ProfessionalSearchCriteria criteria, PageQuery pageQuery) {
            return delegate.searchProfessionals(criteria, pageQuery);
        }
    }
}
