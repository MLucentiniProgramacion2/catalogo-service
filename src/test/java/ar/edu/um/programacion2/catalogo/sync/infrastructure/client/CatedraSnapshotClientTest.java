package ar.edu.um.programacion2.catalogo.sync.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogSnapshot;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.InvalidSnapshotException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SnapshotAccessDeniedException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SnapshotRetrievalException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SnapshotSourceUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cliente del snapshot contra una cátedra simulada con un servidor HTTP real ({@link FakeCatedraServer}). Cada falla
 * se clasifica por status (contrato §13) y el token nunca aparece en una excepción.
 */
class CatedraSnapshotClientTest {

    private static final String TOKEN = "token-de-prueba-9f8e7d";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Snapshot del contrato §7 con un profesional deshabilitado, los dos formatos de hora y campos que no conocemos. */
    private static final String VALID_SNAPSHOT = """
        {
          "snapshotVersion": 7,
          "generatedAt": "2026-09-10T13:44:59Z",
          "campoNuevoDeLaCatedra": {"anidado": true},
          "professionalCategories": [
            {"id": 1, "name": "Clinica medica", "description": "Atencion clinica general", "enabled": true,
             "createdAt": "2026-07-01T12:00:00Z", "updatedAt": "2026-09-10T13:40:00Z", "color": "azul"}
          ],
          "professionals": [
            {"id": 15, "categoryId": 1, "firstName": "Ana", "lastName": "Gomez", "enabled": false,
             "createdAt": "2026-07-01T12:00:00Z", "updatedAt": "2026-09-10T13:40:00Z", "matricula": "MP-123"}
          ],
          "weeklySchedules": [
            {"id": 21, "professionalId": 15, "dayOfWeek": "MONDAY", "startTime": "09:00:00", "endTime": "12:00:00",
             "slotDurationMinutes": 30, "enabled": true,
             "createdAt": "2026-07-01T12:00:00Z", "updatedAt": "2026-09-10T13:40:00Z", "sala": "3"},
            {"id": 22, "professionalId": 15, "dayOfWeek": "FRIDAY", "startTime": "14:00", "endTime": "18:30",
             "slotDurationMinutes": 20, "enabled": true,
             "createdAt": "2026-07-01T12:00:00Z", "updatedAt": "2026-09-10T13:40:00Z"}
          ]
        }
        """;

    private static final Instant CREATED = Instant.parse("2026-07-01T12:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-09-10T13:40:00Z");

    private FakeCatedraServer catedra;

    @BeforeEach
    void startCatedra() throws IOException {
        catedra = FakeCatedraServer.start();
    }

    @AfterEach
    void stopCatedra() {
        catedra.close();
    }

    @Test
    void traduceUnaRespuestaValidaAlDominio() {
        catedra.respondJson(200, VALID_SNAPSHOT);

        CatalogSnapshot snapshot = client().fetchSnapshot();

        assertThat(snapshot.snapshotVersion()).isEqualTo(7);
        assertThat(snapshot.generatedAt()).isEqualTo(Instant.parse("2026-09-10T13:44:59Z"));
        assertThat(snapshot.professionalCategories()).containsExactly(
            new ProfessionalCategory(1, "Clinica medica", "Atencion clinica general", true, CREATED, UPDATED)
        );
        assertThat(snapshot.professionals()).containsExactly(new Professional(15, 1, "Ana", "Gomez", false, CREATED, UPDATED));
        assertThat(snapshot.weeklySchedules()).containsExactly(
            new WeeklySchedule(21, 15, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), 30, true, CREATED, UPDATED),
            new WeeklySchedule(22, 15, DayOfWeek.FRIDAY, LocalTime.of(14, 0), LocalTime.of(18, 30), 20, true, CREATED, UPDATED)
        );
    }

    @Test
    void aceptaColeccionesVacias() {
        catedra.respondJson(
            200,
            """
            {"snapshotVersion": 0, "generatedAt": "2026-09-10T13:44:59Z",
             "professionalCategories": [], "professionals": [], "weeklySchedules": []}
            """
        );

        CatalogSnapshot snapshot = client().fetchSnapshot();

        assertThat(snapshot.snapshotVersion()).isZero();
        assertThat(snapshot.professionalCategories()).isEmpty();
        assertThat(snapshot.professionals()).isEmpty();
        assertThat(snapshot.weeklySchedules()).isEmpty();
    }

    @Test
    void pideElSnapshotConElJwtTecnicoEnAuthorization() {
        catedra.respondJson(200, VALID_SNAPSHOT);

        client().fetchSnapshot();

        assertThat(catedra.requests()).containsExactly(
            new FakeCatedraServer.ReceivedRequest("GET", "/api/synchronization/snapshot", "Bearer " + TOKEN)
        );
    }

    @Test
    void aceptaUnaUrlBaseConBarraFinal() {
        catedra.respondJson(200, VALID_SNAPSHOT);

        client(URI.create(catedra.baseUrl() + "/")).fetchSnapshot();

        assertThat(catedra.requests()).extracting(FakeCatedraServer.ReceivedRequest::path).containsExactly("/api/synchronization/snapshot");
    }

    @ParameterizedTest
    @ValueSource(ints = { 401, 403 })
    void unRechazoDeLaCredencialSinCodeEsAccesoDenegado(int status) {
        catedra.respondWithoutBody(status);

        SnapshotAccessDeniedException e = fetchExpecting(SnapshotAccessDeniedException.class);

        assertThat(e).hasMessageContaining(String.valueOf(status)).hasMessageContaining("sin code");
    }

    @Test
    void unRechazoConCodeLoIncluyeEnElMensajeSinElDetail() {
        catedra.respondProblem(403, "STUDENT_INTEGRATION_NOT_FOUND");

        SnapshotAccessDeniedException e = fetchExpecting(SnapshotAccessDeniedException.class);

        assertThat(e).hasMessageContaining("403").hasMessageContaining("STUDENT_INTEGRATION_NOT_FOUND").hasMessageNotContaining("detalle libre");
    }

    @ParameterizedTest
    @ValueSource(ints = { 408, 429, 500, 503 })
    void unaFallaTransitoriaDeLaCatedraEsNoDisponible(int status) {
        catedra.respondProblem(status, "ALGO_TRANSITORIO");

        SnapshotSourceUnavailableException e = fetchExpecting(SnapshotSourceUnavailableException.class);

        assertThat(e).hasMessageContaining(String.valueOf(status)).hasMessageContaining("ALGO_TRANSITORIO");
    }

    @Test
    void unServidorApagadoEsNoDisponible() {
        URI apagado = catedra.baseUrl();
        catedra.close();

        assertThat(fetchExpecting(client(apagado), SnapshotSourceUnavailableException.class)).hasMessageContaining("no respondió");
    }

    @Test
    void unaCatedraQueNoRespondeATiempoEsNoDisponibleYNoBloquea() {
        catedra.hangBeforeResponding();

        long start = System.nanoTime();
        fetchExpecting(SnapshotSourceUnavailableException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void unCuerpoQueSeCortaAMitadEsNoDisponibleYNoInvalido() {
        catedra.hangInTheMiddleOfTheBody(VALID_SNAPSHOT);

        long start = System.nanoTime();
        fetchExpecting(SnapshotSourceUnavailableException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
            Arguments.of("JSON malformado", "{\"snapshotVersion\": 7, \"generatedAt\": ", "JSON"),
            Arguments.of("cuerpo vacío", "", "JSON"),
            Arguments.of("cuerpo null", "null", "null"),
            Arguments.of("sin snapshotVersion", modify(root -> root.remove("snapshotVersion")), "snapshotVersion"),
            Arguments.of("sin generatedAt", modify(root -> root.remove("generatedAt")), "generatedAt"),
            Arguments.of("sin professionals", modify(root -> root.remove("professionals")), "professionals"),
            Arguments.of("sin id de profesional", modify(root -> item(root, "professionals", 0).remove("id")), "id"),
            Arguments.of("sin enabled de categoría", modify(root -> item(root, "professionalCategories", 0).remove("enabled")), "enabled"),
            Arguments.of("sin enabled de profesional", modify(root -> item(root, "professionals", 0).remove("enabled")), "enabled"),
            Arguments.of("sin firstName", modify(root -> item(root, "professionals", 0).remove("firstName")), "firstName"),
            Arguments.of(
                "sin slotDurationMinutes",
                modify(root -> item(root, "weeklySchedules", 0).remove("slotDurationMinutes")),
                "slotDurationMinutes"
            ),
            Arguments.of("dayOfWeek inválido", modify(root -> item(root, "weeklySchedules", 0).put("dayOfWeek", "LUNES")), "dayOfWeek"),
            Arguments.of("dayOfWeek en minúsculas", modify(root -> item(root, "weeklySchedules", 0).put("dayOfWeek", "monday")), "dayOfWeek"),
            Arguments.of("hora inválida", modify(root -> item(root, "weeklySchedules", 1).put("startTime", "25:00")), "startTime"),
            Arguments.of("hora con otro formato", modify(root -> item(root, "weeklySchedules", 1).put("endTime", "6 PM")), "endTime"),
            Arguments.of("instante inválido", modify(root -> item(root, "professionals", 0).put("createdAt", "2026-07-01")), "createdAt"),
            Arguments.of("elemento nulo", modify(root -> ((ArrayNode) root.get("weeklySchedules")).addNull()), "nulo")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void unCuerpoQueNoRespetaElContratoEsInvalido(String caso, String body, String expectedInMessage) {
        catedra.respondJson(200, body);

        assertThat(fetchExpecting(InvalidSnapshotException.class)).hasMessageContaining(expectedInMessage);
    }

    @ParameterizedTest
    @ValueSource(ints = { 204, 400, 404 })
    void unStatusQueNoEstaEnElContratoEsInvalido(int status) {
        catedra.respondWithoutBody(status);

        assertThat(fetchExpecting(InvalidSnapshotException.class)).hasMessageContaining(String.valueOf(status));
    }

    @Test
    void unaRedireccionNoSeSigueYEsInvalida() {
        catedra.redirectTo(catedra.baseUrl() + "/otro-lado");

        assertThat(fetchExpecting(InvalidSnapshotException.class)).hasMessageContaining("302");
        assertThat(catedra.requests()).hasSize(1);
    }

    private CatedraSnapshotClient client() {
        return client(catedra.baseUrl());
    }

    private static CatedraSnapshotClient client(URI baseUrl) {
        CatedraApiProperties properties = new CatedraApiProperties();
        properties.setUrl(baseUrl);
        properties.setToken(TOKEN);
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(READ_TIMEOUT);
        return new CatedraSnapshotClient(new CatedraClientConfiguration().catedraRestClient(properties));
    }

    private <T extends SnapshotRetrievalException> T fetchExpecting(Class<T> type) {
        return fetchExpecting(client(), type);
    }

    /** Espera la falla del tipo indicado y verifica que el token no aparezca en ninguna excepción de la cadena. */
    private static <T extends SnapshotRetrievalException> T fetchExpecting(CatedraSnapshotClient client, Class<T> type) {
        T failure = catchThrowableOfType(type, client::fetchSnapshot);
        assertThat(failure).as("tenía que fallar con %s", type.getSimpleName()).isNotNull();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            assertThat(String.valueOf(t.getMessage())).doesNotContain(TOKEN);
            assertThat(t.toString()).doesNotContain(TOKEN);
        }
        return failure;
    }

    private static String modify(Consumer<ObjectNode> change) {
        ObjectNode root = (ObjectNode) JSON.readTree(VALID_SNAPSHOT);
        change.accept(root);
        return JSON.writeValueAsString(root);
    }

    private static ObjectNode item(ObjectNode root, String collection, int index) {
        return (ObjectNode) root.get(collection).get(index);
    }
}
