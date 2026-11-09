package ar.edu.um.programacion2.catalogo.sync.infrastructure.periodic;

import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.category;
import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.professional;
import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.schedule;
import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.sync.RedisCatalog;
import ar.edu.um.programacion2.catalogo.sync.RedisTestServer;
import ar.edu.um.programacion2.catalogo.sync.SyncLockHolder;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.application.service.SyncService;
import ar.edu.um.programacion2.catalogo.sync.domain.model.FullSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.ExecuteFullSyncUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncLock;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.client.FakeCatedraServer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * La comprobación periódica de versión (DP-04) con PostgreSQL, la cátedra simulada y un Redis con la ACL de la cátedra.
 * <p>
 * La tarea está prendida, con un intervalo de 1 h para que el reloj no la dispare durante los tests: cada test llama al
 * disparador a mano ({@link PeriodicSyncTrigger#checkCatalogVersion()}), que es exactamente lo que corre el scheduler.
 * Que el scheduler la tenga registrada con ese intervalo lo verifica {@link #laTareaQuedaRegistradaConElIntervaloConfigurado}.
 * <p>
 * Redis es un contenedor propio, porque un test lo pausa (como en {@code RedisCatalogChangesProviderIT}), con timeouts
 * cortos para que Redis caído falle enseguida.
 */
@IntegrationTest
@TestPropertySource(
    properties = {
        "sync.periodic.enabled=true",
        "sync.periodic.interval=1h",
        "catedra.redis.connect-timeout=500ms",
        "catedra.redis.command-timeout=300ms",
    }
)
class PeriodicSyncIT {

    private static final GenericContainer<?> REDIS = RedisTestServer.newContainer();
    private static final RedisCommands<String, String> REDIS_ADMIN = RedisClient.create(
        RedisURI.create(REDIS.getHost(), REDIS.getMappedPort(6379))
    )
        .connect()
        .sync();
    private static final FakeCatedraServer CATEDRA = startCatedra();

    @Autowired
    private PeriodicSyncTrigger periodicSyncTrigger;

    @Autowired
    private SyncService syncService;

    @Autowired
    private ExecuteFullSyncUseCase executeFullSyncUseCase;

    @Autowired
    private SyncLock syncLock;

    @Autowired
    private SyncStatusRepository syncStatusRepository;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void catedraYRedisPropios(DynamicPropertyRegistry registry) {
        registry.add("catedra.api.url", () -> CATEDRA.baseUrl().toString());
        registry.add("catedra.redis.host", REDIS::getHost);
        registry.add("catedra.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("catedra.redis.username", () -> RedisTestServer.USERNAME);
        registry.add("catedra.redis.password", () -> RedisTestServer.PASSWORD);
    }

    @BeforeEach
    @AfterEach
    void volverAlEstadoInicial() {
        SyncTestDatabase.reset(transactionTemplate, em);
        REDIS_ADMIN.flushall();
        CATEDRA.respondJson(200, snapshot(12, List.of(category(1)), List.of(professional(15, 1)), List.of(schedule(21, 15))));
    }

    @AfterAll
    static void apagar() {
        CATEDRA.close();
        REDIS.stop();
    }

    @Test
    void laTareaQuedaRegistradaConElIntervaloConfigurado() {
        Set<ScheduledTask> tareas = context
            .getBeansOfType(ScheduledTaskHolder.class)
            .values()
            .stream()
            .flatMap(holder -> holder.getScheduledTasks().stream())
            .collect(Collectors.toSet());

        // Es la única tarea programada de la aplicación: nadie más usa el scheduler.
        assertThat(tareas).hasSize(1);
        FixedDelayTask tarea = (FixedDelayTask) tareas.iterator().next().getTask();
        assertThat(tarea.getIntervalDuration()).isEqualTo(Duration.ofHours(1));
        assertThat(tarea.getInitialDelayDuration()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void laPeriodicaAplicaUnaVersionQueNoLlegoPorAviso() {
        // Aviso perdido: Redis publicó la 13 y Kafka no avisó (o el aviso se descartó). Nada más la va a aplicar.
        copiaEnLa12();
        redis().metadata(13, 1).professionalsChangedIn(13, 15L).professional(15, 1, "Beatriz", true);

        periodicSyncTrigger.checkCatalogVersion();

        SyncStatus estado = estado();
        assertThat(estado.getLocalVersion()).isEqualTo(13L);
        assertThat(estado.getLastSuccessPath()).isEqualTo(SyncPath.INCREMENTAL);
        assertThat(estado.getLastSuccessTrigger()).isEqualTo(SyncTrigger.PERIODIC);
        assertThat(estado.getRemoteVersion()).isEqualTo(13L);
        assertThat(nombreDelProfesional15()).isEqualTo("Beatriz");
        // No trae eventId: su barrera es la comparación de versiones.
        assertThat(SyncTestDatabase.processedEventIds(transactionTemplate, em)).isEmpty();
    }

    @Test
    void conLaCopiaAlDiaSoloRegistraLaVersionRemota() {
        copiaEnLa12();
        redis().metadata(12, 1);

        periodicSyncTrigger.checkCatalogVersion();

        SyncStatus estado = estado();
        assertThat(estado.getLocalVersion()).isEqualTo(12L);
        assertThat(estado.getLastSuccessTrigger()).isEqualTo(SyncTrigger.MANUAL);
        assertThat(estado.getRemoteVersion()).isEqualTo(12L);
        assertThat(estado.getRemoteVersionCheckedAt()).isAfter(estado.getLastSuccessAt());
        assertThat(estado.getLastErrorType()).isNull();
    }

    @Test
    void conRedisCaidoAlArrancarLaSiguientePasadaRecuperaElAtraso() {
        // CU-01, flujo 3a: el arranque no puede leer Redis, sigue con la copia que tiene y la periódica recupera después.
        copiaEnLa12();
        redis().metadata(13, 1).professionalsChangedIn(13, 15L).professional(15, 1, "Beatriz", true);

        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            syncService.initializeCatalog();
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        SyncStatus despuesDelArranque = estado();
        assertThat(despuesDelArranque.getLocalVersion()).isEqualTo(12L);
        assertThat(despuesDelArranque.getLastErrorType()).isEqualTo(SyncErrorType.REDIS_UNAVAILABLE);
        assertThat(despuesDelArranque.isLastErrorResolved()).isFalse();

        periodicSyncTrigger.checkCatalogVersion();

        SyncStatus estado = estado();
        assertThat(estado.getLocalVersion()).isEqualTo(13L);
        assertThat(estado.getLastSuccessPath()).isEqualTo(SyncPath.INCREMENTAL);
        assertThat(estado.getLastSuccessTrigger()).isEqualTo(SyncTrigger.PERIODIC);
        assertThat(estado.getLastSuccessAt()).isAfter(estado.getLastErrorAt());
        assertThat(estado.isLastErrorResolved()).isTrue();
    }

    @Test
    void conRedisCaidoYDespuesLaCopiaAlDiaElErrorQuedaResueltoSinOtroExito() {
        // Lo que CU-09 informaba mal antes de "resolved": Redis vuelve, no hay nada que aplicar y lastSuccess no cambia.
        copiaEnLa12();
        redis().metadata(12, 1);
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            periodicSyncTrigger.checkCatalogVersion();
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        assertThat(estado().getLastErrorType()).isEqualTo(SyncErrorType.REDIS_UNAVAILABLE);
        assertThat(estado().isLastErrorResolved()).isFalse();

        periodicSyncTrigger.checkCatalogVersion();

        SyncStatus estado = estado();
        assertThat(estado.getLastSuccessAt()).isBefore(estado.getLastErrorAt());
        assertThat(estado.getRemoteVersionCheckedAt()).isAfter(estado.getLastErrorAt());
        assertThat(estado.getLastErrorType()).isEqualTo(SyncErrorType.REDIS_UNAVAILABLE);
        assertThat(estado.isLastErrorResolved()).isTrue();
    }

    @Test
    void conLaBaseVaciaYLaCatedraCaidaAlArrancarLaSiguientePasadaHaceElSnapshot() {
        // CU-01, flujo 2a: el arranque no pudo armar la primera copia. Sin versión local, la periódica va al snapshot
        // sin leer Redis (acá Redis no tiene nada publicado).
        CATEDRA.respondProblem(503, "SERVICE_UNAVAILABLE");
        syncService.initializeCatalog();
        SyncStatus despuesDelArranque = estado();
        assertThat(despuesDelArranque.hasLocalCopy()).isFalse();
        assertThat(despuesDelArranque.getLastErrorType()).isEqualTo(SyncErrorType.CATEDRA_UNAVAILABLE);

        CATEDRA.respondJson(200, snapshot(12, List.of(category(1)), List.of(professional(15, 1)), List.of(schedule(21, 15))));
        periodicSyncTrigger.checkCatalogVersion();

        SyncStatus estado = estado();
        assertThat(estado.getLocalVersion()).isEqualTo(12L);
        assertThat(estado.getLastSuccessPath()).isEqualTo(SyncPath.SNAPSHOT);
        assertThat(estado.getLastSuccessTrigger()).isEqualTo(SyncTrigger.PERIODIC);
        assertThat(estado.getRemoteVersion()).isNull();
        assertThat(SyncTestDatabase.professionalIds(transactionTemplate, em)).containsExactly(15L);
    }

    @Test
    void conOtraSincronizacionEnCursoLaPasadaSeSalteaSinDejarError() {
        copiaEnLa12();
        redis().metadata(13, 1).professionalsChangedIn(13, 15L).professional(15, 1, "Beatriz", true);

        try (SyncLockHolder otra = SyncLockHolder.hold(syncLock)) {
            periodicSyncTrigger.checkCatalogVersion();
        }

        SyncStatus estado = estado();
        assertThat(estado.getLocalVersion()).isEqualTo(12L);
        assertThat(estado.getRemoteVersion()).isNull();
        assertThat(estado.getLastErrorType()).isNull();
    }

    private void copiaEnLa12() {
        assertThat(executeFullSyncUseCase.executeFullSync(SyncTrigger.MANUAL)).isEqualTo(new FullSyncResult.Applied(12, 1));
    }

    private static RedisCatalog redis() {
        return new RedisCatalog(REDIS_ADMIN);
    }

    private SyncStatus estado() {
        return transactionTemplate.execute(tx -> syncStatusRepository.load());
    }

    private String nombreDelProfesional15() {
        return transactionTemplate.execute(tx ->
            (String) em.createNativeQuery("select first_name from professional where id = 15", String.class).getSingleResult()
        );
    }

    private static FakeCatedraServer startCatedra() {
        try {
            return FakeCatedraServer.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
