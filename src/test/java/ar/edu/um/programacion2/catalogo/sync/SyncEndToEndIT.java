package ar.edu.um.programacion2.catalogo.sync;

import static ar.edu.um.programacion2.catalogo.security.SecurityUtils.AUTHORITIES_CLAIM;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.BEARER;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.createServiceToken;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.createSignedToken;
import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.category;
import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.schedule;
import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.sync.application.service.SyncService;
import ar.edu.um.programacion2.catalogo.sync.domain.model.ManualSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncMode;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.client.FakeCatedraServer;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.periodic.PeriodicSyncTrigger;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La sincronización de punta a punta, con las evidencias del enunciado (§11) y los flujos de recuperación (§8): todo lo
 * real menos la cátedra, que es {@link FakeCatedraServer}. PostgreSQL, Kafka y Redis (con la ACL de la cátedra) son
 * contenedores; se dispara por los caminos reales y se verifica por REST, como lo vería la app o turnos.
 * <p>
 * <strong>Disparadores.</strong> El aviso de Kafka se publica en el broker y se espera el offset confirmado: el listener
 * está prendido en este contexto ({@code catedra.kafka.listener-enabled=true}), con un topic y un grupo propios, así que
 * ningún otro contexto lee estos avisos. Cuando Spring pausa este contexto para usar otro, el consumidor se para, y al
 * reanudarlo arranca de nuevo: lo que respeta {@code autoStartup} es {@code listenerRespectsAutoStartup} (issue #31).
 * El arranque se dispara con {@link SyncService#initializeCatalog()}, que es lo que corre el disparador después de
 * {@code ApplicationReadyEvent}; la periódica, con {@link PeriodicSyncTrigger#checkCatalogVersion()}, lo que corre el
 * scheduler (prendida con 1 h para que el reloj no la dispare).
 * <p>
 * <strong>Casos independientes.</strong> Cada uno parte de la base, Redis y la cátedra vacíos y arma su estado: la copia
 * inicial, con un snapshot por la cátedra simulada (CU-08 en modo snapshot). Un solo contexto para todos, sin
 * {@code @DirtiesContext}.
 * <p>
 * La copia de partida es la 12: la categoría 1, el profesional 15 ("Ana") y su horario 21 del lunes. Lo que publica
 * Redis después cambia al 15 de nombre y le agrega el horario 22 del martes.
 */
@AutoConfigureMockMvc
@IntegrationTest
@TestPropertySource(
    properties = { "catedra.kafka.listener-enabled=true", "sync.periodic.enabled=true", "sync.periodic.interval=1h" }
)
class SyncEndToEndIT {

    private static final String TOPIC = KafkaTestBroker.createTopic();
    private static final String GRUPO = "alumnos-de-prueba-punta-a-punta";
    private static final FakeCatedraServer CATEDRA = startCatedra();

    /** Avisos publicados desde que arrancó la clase: el offset que tiene que quedar confirmado. */
    private static long publicados;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SyncService syncService;

    @Autowired
    private PeriodicSyncTrigger periodicSyncTrigger;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    @DynamicPropertySource
    static void dependenciasDePrueba(DynamicPropertyRegistry properties) {
        properties.add("catedra.api.url", () -> CATEDRA.baseUrl().toString());
        properties.add("catedra.kafka.bootstrap-servers", KafkaTestBroker::bootstrapServers);
        properties.add("catedra.kafka.catalog-topic", () -> TOPIC);
        properties.add("catedra.kafka.consumer-group", () -> GRUPO);
        properties.add("catedra.redis.host", RedisTestServer::host);
        properties.add("catedra.redis.port", RedisTestServer::port);
        properties.add("catedra.redis.username", () -> RedisTestServer.USERNAME);
        properties.add("catedra.redis.password", () -> RedisTestServer.PASSWORD);
    }

    @BeforeEach
    @AfterEach
    void volverAlEstadoInicial() {
        SyncTestDatabase.reset(transactionTemplate, em);
        RedisTestServer.flushAll();
    }

    @AfterAll
    static void apagarCatedra() {
        CATEDRA.close();
    }

    // ---- §11: inicialización desde snapshot ----

    @Test
    void conLaBaseVaciaElArranqueHaceElSnapshotYLasConsultasInformanSuVersion() throws Exception {
        estado().andExpect(jsonPath("$.localVersion").value((Object) null));
        consultar("/api/categories").andExpect(status().isServiceUnavailable());
        CATEDRA.respondJson(200, snapshotDeLa(12, "Ana"));

        syncService.initializeCatalog();

        estado()
            .andExpect(jsonPath("$.localVersion").value(12))
            .andExpect(jsonPath("$.lastSuccess.path").value("SNAPSHOT"))
            .andExpect(jsonPath("$.lastSuccess.trigger").value("STARTUP"))
            .andExpect(jsonPath("$.lastError").value((Object) null));
        consultar("/api/categories").andExpect(status().isOk()).andExpect(jsonPath("$.catalogVersion").value(12));
        agenda().andExpect(jsonPath("$.catalogVersion").value(12)).andExpect(jsonPath("$.weeklySchedules[0].id").value(21));
    }

    // ---- §11: actualización incremental ----

    @Test
    void unAvisoConRedisUnaVersionAdelanteAplicaPorIncrementalYSeVeEnLaBusquedaYEnLaAgenda() throws Exception {
        copiaEnLa12();
        publicaLa13EnRedis();
        int pedidosALaCatedra = CATEDRA.requests().size();

        avisar(UUID.randomUUID().toString(), 13);

        estado()
            .andExpect(jsonPath("$.localVersion").value(13))
            .andExpect(jsonPath("$.lastSuccess.path").value("INCREMENTAL"))
            .andExpect(jsonPath("$.lastSuccess.trigger").value("KAFKA"))
            .andExpect(jsonPath("$.remote.version").value(13));
        consultar("/api/professionals?name=Beatriz")
            .andExpect(jsonPath("$.catalogVersion").value(13))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.professionals[0].id").value(15));
        agenda()
            .andExpect(jsonPath("$.catalogVersion").value(13))
            .andExpect(jsonPath("$.professional.firstName").value("Beatriz"))
            .andExpect(jsonPath("$.weeklySchedules.length()").value(2))
            .andExpect(jsonPath("$.weeklySchedules[1].dayOfWeek").value("TUESDAY"));
        // El aviso no le pidió nada a la cátedra: los datos salieron de Redis.
        assertThat(CATEDRA.requests()).hasSize(pedidosALaCatedra);
    }

    // ---- §11: procesamiento idempotente de un mensaje duplicado ----

    @Test
    void elMismoAvisoOtraVezNoCambiaNada() throws Exception {
        copiaEnLa12();
        publicaLa13EnRedis();
        String eventId = UUID.randomUUID().toString();
        avisar(eventId, 13);
        String estadoDespuesDelPrimero = estado().andReturn().getResponse().getContentAsString();
        String agendaDespuesDelPrimero = agenda().andReturn().getResponse().getContentAsString();

        avisar(eventId, 13);

        // Ni la versión, ni la fecha del último éxito, ni la versión remota (la primera barrera ni lee Redis).
        assertThat(estado().andReturn().getResponse().getContentAsString()).isEqualTo(estadoDespuesDelPrimero);
        assertThat(agenda().andReturn().getResponse().getContentAsString()).isEqualTo(agendaDespuesDelPrimero);
        assertThat(SyncTestDatabase.processedEventIds(transactionTemplate, em)).containsExactly(eventId);
    }

    @Test
    void unAvisoViejoQueLlegaFueraDeOrdenNoCambiaNadaYQuedaRegistrado() throws Exception {
        // La copia ya está en la 12 y llega el aviso de la 11 (por ejemplo, reentregado): segunda barrera.
        copiaEnLa12();
        String antes = estado().andReturn().getResponse().getContentAsString();
        String eventId = UUID.randomUUID().toString();

        avisar(eventId, 11);

        assertThat(estado().andReturn().getResponse().getContentAsString()).isEqualTo(antes);
        assertThat(SyncTestDatabase.processedEventIds(transactionTemplate, em)).containsExactly(eventId);
    }

    // ---- §11: recuperación mediante snapshot luego de una discontinuidad ----

    @Test
    void conLaCopiaFueraDeLaVentanaDeRedisElAvisoTerminaEnUnSnapshot() throws Exception {
        // §18.2: la copia en la 12 y Redis retiene desde la 15: falta la versión que conecta.
        copiaEnLa12();
        RedisCatalog.shared().metadata(20, 15);
        CATEDRA.respondJson(200, snapshotDeLa(20, "Carla"));
        int pedidosALaCatedra = CATEDRA.requests().size();

        avisar(UUID.randomUUID().toString(), 20);

        estado()
            .andExpect(jsonPath("$.localVersion").value(20))
            .andExpect(jsonPath("$.lastSuccess.path").value("SNAPSHOT"))
            .andExpect(jsonPath("$.lastSuccess.trigger").value("KAFKA"))
            .andExpect(jsonPath("$.remote.version").value(20));
        agenda().andExpect(jsonPath("$.catalogVersion").value(20)).andExpect(jsonPath("$.professional.firstName").value("Carla"));
        assertThat(CATEDRA.requests()).hasSize(pedidosALaCatedra + 1);
    }

    @Test
    void siElIncrementalNoPuedeSeguirAMitadDeCaminoConservaLoAplicadoYTerminaEnUnSnapshot() throws Exception {
        // CU-04, 3a: la 13 se aplica; la 14 nombra al profesional 99, que no está en el Hash. Sigue CU-02 con el mismo aviso.
        copiaEnLa12();
        RedisCatalog.shared().metadata(14, 1).professionalsChangedIn(13, 15L).professionalsChangedIn(14, 99L).professional(15, 1, "Beatriz", true);
        CATEDRA.respondJson(200, snapshotDeLa(14, "Daniela"));
        String eventId = UUID.randomUUID().toString();

        avisar(eventId, 14);

        estado()
            .andExpect(jsonPath("$.localVersion").value(14))
            .andExpect(jsonPath("$.lastSuccess.path").value("SNAPSHOT"))
            .andExpect(jsonPath("$.lastSuccess.trigger").value("KAFKA"));
        agenda().andExpect(jsonPath("$.professional.firstName").value("Daniela"));
        // El eventId quedó en la transacción del snapshot.
        assertThat(SyncTestDatabase.processedEventIds(transactionTemplate, em)).containsExactly(eventId);
    }

    // ---- §8: pérdida de notificaciones y reinicio con la copia atrasada ----

    @Test
    void unAvisoPerdidoLoRecuperaLaComprobacionPeriodica() throws Exception {
        // Solo Redis avanza: no se publica ningún aviso.
        copiaEnLa12();
        publicaLa13EnRedis();

        periodicSyncTrigger.checkCatalogVersion();

        estado()
            .andExpect(jsonPath("$.localVersion").value(13))
            .andExpect(jsonPath("$.lastSuccess.path").value("INCREMENTAL"))
            .andExpect(jsonPath("$.lastSuccess.trigger").value("PERIODIC"));
        agenda().andExpect(jsonPath("$.weeklySchedules.length()").value(2));
    }

    @Test
    void unReinicioConLaCopiaAtrasadaLoRecuperaElArranque() throws Exception {
        // El servicio estuvo apagado mientras la cátedra publicaba la 13 y la 14 (y sus avisos).
        copiaEnLa12();
        publicaLa13EnRedis();
        RedisCatalog.shared().metadata(14, 1).professionalsChangedIn(14, 15L);

        syncService.initializeCatalog();

        estado()
            .andExpect(jsonPath("$.localVersion").value(14))
            .andExpect(jsonPath("$.lastSuccess.path").value("INCREMENTAL"))
            .andExpect(jsonPath("$.lastSuccess.trigger").value("STARTUP"))
            .andExpect(jsonPath("$.remote.version").value(14));
        agenda().andExpect(jsonPath("$.catalogVersion").value(14));
    }

    // ---- ayudas ----

    /** La copia de partida, en la 12, por el camino real de CU-08 en modo snapshot. */
    private void copiaEnLa12() {
        CATEDRA.respondJson(200, snapshotDeLa(12, "Ana"));
        assertThat(syncService.triggerManualSync(SyncMode.SNAPSHOT)).isInstanceOf(ManualSyncResult.Applied.class);
    }

    /** Redis publica la 13: el profesional 15 pasa a llamarse "Beatriz" y suma el horario 22 del martes. */
    private static void publicaLa13EnRedis() {
        RedisCatalog.shared()
            .metadata(13, 1)
            .changes(13, List.of(), List.of(15L), List.of(22L))
            .professional(15, 1, "Beatriz", true)
            .schedule(22, 15, "TUESDAY", "14:00:00", "18:00:00", true);
    }

    /** Publica el aviso y espera a que el listener confirme su offset, que es después de persistir. */
    private static void avisar(String eventId, long version) {
        KafkaTestBroker.publishCatalogUpdated(TOPIC, eventId, version);
        long esperado = ++publicados;
        await().atMost(Duration.ofSeconds(30)).until(() -> Long.valueOf(esperado).equals(KafkaTestBroker.committedOffset(GRUPO, TOPIC)));
    }

    private ResultActions estado() throws Exception {
        return consultar("/api/sync/status").andExpect(status().isOk());
    }

    private ResultActions consultar(String ruta) throws Exception {
        String token = createSignedToken(jwtKey, "ana.perez", claims -> claims.claim(AUTHORITIES_CLAIM, "ROLE_USER"));
        return mvc.perform(get(ruta).header(AUTHORIZATION, BEARER + token));
    }

    private ResultActions agenda() throws Exception {
        return mvc
            .perform(get("/api/service/professionals/15/agenda").header(AUTHORIZATION, BEARER + createServiceToken(jwtKey)))
            .andExpect(status().isOk());
    }

    private static String snapshotDeLa(long version, String nombre) {
        String profesional =
            "{\"id\": 15, \"categoryId\": 1, \"firstName\": \"" +
            nombre +
            "\", \"lastName\": \"Gomez\", \"enabled\": true, \"createdAt\": \"2026-07-01T12:00:00Z\", \"updatedAt\": \"2026-07-01T12:00:00Z\"}";
        return snapshot(version, List.of(category(1)), List.of(profesional), List.of(schedule(21, 15)));
    }

    private static FakeCatedraServer startCatedra() {
        try {
            return FakeCatedraServer.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
