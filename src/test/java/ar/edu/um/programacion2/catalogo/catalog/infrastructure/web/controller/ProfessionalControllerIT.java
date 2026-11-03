package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.controller;

import static ar.edu.um.programacion2.catalogo.security.SecurityUtils.AUTHORITIES_CLAIM;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.BEARER;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.createExpiredToken;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.createSignedToken;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * CU-05 de punta a punta: JWT firmado con la clave de test, MockMvc y PostgreSQL real. La copia local se prepara con los
 * puertos, como la deja CU-02, y se limpia con SQL.
 * <p>
 * La versión 1 tiene dos categorías (la 2 deshabilitada) y cinco profesionales, insertados con los IDs desordenados:
 * el orden de la respuesta lo tiene que poner la consulta.
 */
@AutoConfigureMockMvc
@IntegrationTest
class ProfessionalControllerIT {

    private static final String PROFESIONALES = "/api/professionals";
    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-10T13:45:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private SyncStatusRepository syncStatusRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @Autowired
    private PostgreSQLContainer databaseContainer;

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    @BeforeEach
    @AfterEach
    void volverAlEstadoInicial() {
        SyncTestDatabase.reset(transactionTemplate, em);
    }

    // ---- 200 ----

    @Test
    void sinFiltrosDevuelveLosHabilitadosOrdenadosPorIdConSuCategoriaYLaVersion() throws Exception {
        aplicarVersion1();

        pedir(PROFESIONALES, tokenDeUsuario("ROLE_USER"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(
                content().json(
                    """
                    {
                      "catalogVersion": 1,
                      "page": 0,
                      "size": 20,
                      "totalElements": 3,
                      "totalPages": 1,
                      "professionals": [
                        {"id": 1, "firstName": "Ana", "lastName": "Perez", "enabled": true,
                         "category": {"id": 1, "name": "Clinica medica", "enabled": true}},
                        {"id": 3, "firstName": "Ángel", "lastName": "Núñez", "enabled": true,
                         "category": {"id": 2, "name": "Pediatria", "enabled": false}},
                        {"id": 5, "firstName": "Carla", "lastName": "Diaz", "enabled": true,
                         "category": {"id": 1, "name": "Clinica medica", "enabled": true}}
                      ]
                    }
                    """,
                    // STRICT: sin campos de más (ni createdAt ni updatedAt) y en este orden.
                    JsonCompareMode.STRICT
                )
            );
    }

    @Test
    void conEnabledFalseDevuelveLosDeshabilitados() throws Exception {
        aplicarVersion1();

        pedir(PROFESIONALES + "?enabled=false", tokenDeUsuario("ROLE_USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.professionals[*].id").value(contains(2, 4)))
            .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void filtraPorCategoriaYPorNombre() throws Exception {
        aplicarVersion1();

        pedir(PROFESIONALES + "?categoryId=1", tokenDeUsuario("ROLE_USER")).andExpect(jsonPath("$.professionals[*].id").value(contains(1, 5)));
        pedir(PROFESIONALES + "?name=ÁNGEL nú", tokenDeUsuario("ROLE_USER")).andExpect(jsonPath("$.professionals[*].id").value(contains(3)));
        pedir(PROFESIONALES + "?categoryId=1&name=ana&enabled=false", tokenDeUsuario("ROLE_USER")).andExpect(
            jsonPath("$.professionals[*].id").value(contains(2))
        );
    }

    @Test
    void unNombreDeSoloEspaciosNoFiltra() throws Exception {
        aplicarVersion1();

        pedirConNombre("  ").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
        pedirConNombre("").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void ningunProfesionalCumpleLosFiltrosDevuelveUnaListaVacia() throws Exception {
        aplicarVersion1();

        pedir(PROFESIONALES + "?categoryId=99", tokenDeUsuario("ROLE_USER"))
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    "{\"catalogVersion\": 1, \"page\": 0, \"size\": 20, \"totalElements\": 0, \"totalPages\": 0, \"professionals\": []}",
                    JsonCompareMode.STRICT
                )
            );
    }

    @Test
    void paginaConLosTotalesDeTodasLasPaginas() throws Exception {
        aplicarVersion1();

        pedir(PROFESIONALES + "?page=1&size=2", tokenDeUsuario("ROLE_USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.professionals[*].id").value(contains(5)));
    }

    @Test
    void unaPaginaFueraDeRangoDevuelveUnaListaVaciaConLosTotales() throws Exception {
        aplicarVersion1();

        pedir(PROFESIONALES + "?page=50", tokenDeUsuario("ROLE_USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(50))
            .andExpect(jsonPath("$.professionals", hasSize(0)))
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void aceptaLosBordesDeLaPaginacionYDelNombre() throws Exception {
        aplicarVersion1();

        pedir(PROFESIONALES + "?page=0&size=1", tokenDeUsuario("ROLE_USER")).andExpect(status().isOk());
        pedir(PROFESIONALES + "?size=100", tokenDeUsuario("ROLE_USER")).andExpect(status().isOk());
        pedir(PROFESIONALES + "?name=" + "x".repeat(100), tokenDeUsuario("ROLE_USER")).andExpect(status().isOk());
    }

    @Test
    void unAdminSoloConRoleAdminTambienPuede() throws Exception {
        aplicarVersion1();

        pedir(PROFESIONALES, tokenDeUsuario("ROLE_ADMIN")).andExpect(status().isOk());
    }

    @Test
    void conVersionYSinProfesionalesDevuelveUnaListaVacia() throws Exception {
        aplicar(1, List.of(categoria(1, "Clinica medica", true)), List.of());

        pedir(PROFESIONALES, tokenDeUsuario("ROLE_USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.catalogVersion").value(1))
            .andExpect(jsonPath("$.professionals", hasSize(0)));
    }

    @Test
    void duranteUnaSincronizacionSinConfirmarRespondeConLaUltimaCopiaSinBloquearse() throws Exception {
        aplicarVersion1();

        // Un snapshot a mitad de camino en otra conexión: borró la copia, insertó otra y avanzó la versión, sin
        // confirmar. Fuera del pool, porque en los tests Hikari tiene una sola conexión.
        try (
            Connection sync = DriverManager.getConnection(
                databaseContainer.getJdbcUrl(),
                databaseContainer.getUsername(),
                databaseContainer.getPassword()
            );
            Statement statement = sync.createStatement()
        ) {
            sync.setAutoCommit(false);
            statement.executeUpdate("delete from professional");
            statement.executeUpdate(
                "insert into professional (id, category_id, first_name, last_name, enabled, created_at, updated_at) " +
                    "values (99, 1, 'Nuevo', 'Profesional', true, now(), now())"
            );
            statement.executeUpdate("update sync_status set local_version = 2 where id = 1");

            pedir(PROFESIONALES, tokenDeUsuario("ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogVersion").value(1))
                .andExpect(jsonPath("$.professionals[*].id").value(contains(1, 3, 5)));

            sync.rollback();
        }
    }

    // ---- 400 ----

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = {
            "page=-1|page|must be greater than or equal to 0",
            "size=0|size|must be greater than or equal to 1",
            "size=101|size|must be less than or equal to 100",
            "page=valorInventado|page|Failed to convert value",
            "size=valorInventado|size|Failed to convert value",
            "enabled=valorInventado|enabled|Failed to convert value",
            "categoryId=valorInventado|categoryId|Failed to convert value",
        }
    )
    void unParametroInvalidoResponde400ConElCampoYSinRepetirElValor(String query, String campo, String mensaje) throws Exception {
        aplicarVersion1();

        pedir(PROFESIONALES + "?" + query, tokenDeUsuario("ROLE_USER"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("https://www.jhipster.tech/problem/constraint-violation"))
            .andExpect(jsonPath("$.title").value("Method argument not valid"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.message").value("error.validation"))
            .andExpect(jsonPath("$.path").value(PROFESIONALES))
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.detail").value("Invalid request parameters"))
            .andExpect(jsonPath("$.fieldErrors", hasSize(1)))
            .andExpect(jsonPath("$.fieldErrors[0].objectName").value("professionalSearchParams"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value(campo))
            .andExpect(jsonPath("$.fieldErrors[0].message").value(mensaje))
            .andExpect(content().string(not(containsString("valorInventado"))))
            .andExpect(content().string(not(containsString("java.lang"))));
    }

    @Test
    void unNombreDeMasDe100CaracteresResponde400AunqueTengaEspaciosQueSeRecortarian() throws Exception {
        aplicarVersion1();
        String largo = "x".repeat(101);

        pedir(PROFESIONALES + "?name=" + largo, tokenDeUsuario("ROLE_USER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
            .andExpect(content().string(not(containsString(largo))));
        // El largo se controla sobre lo recibido, antes de recortar: 100 letras y un espacio también son 101.
        pedirConNombre("x".repeat(100) + " ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
        pedirConNombre("x".repeat(100)).andExpect(status().isOk());
    }

    @Test
    void variosParametrosInvalidosDanUnFieldErrorCadaUno() throws Exception {
        aplicarVersion1();

        pedir(PROFESIONALES + "?page=-1&size=0", tokenDeUsuario("ROLE_USER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors", hasSize(2)));
    }

    // ---- 401, 403 y 503 ----

    @Test
    void sinTokenResponde401() throws Exception {
        mvc.perform(get(PROFESIONALES)).andExpect(status().isUnauthorized());
    }

    @Test
    void conElTokenVencidoResponde401() throws Exception {
        aplicarVersion1();

        pedir(PROFESIONALES, createExpiredToken(jwtKey)).andExpect(status().isUnauthorized());
    }

    @Test
    void conLaFirmaAlteradaResponde401() throws Exception {
        aplicarVersion1();
        String[] partes = tokenDeUsuario("ROLE_USER").split("\\.");
        char[] firma = partes[2].toCharArray();
        firma[10] = firma[10] == 'A' ? 'B' : 'A';

        pedir(PROFESIONALES, partes[0] + "." + partes[1] + "." + new String(firma)).andExpect(status().isUnauthorized());
    }

    @Test
    void conElTokenTecnicoDeServicioResponde403() throws Exception {
        aplicarVersion1();
        String tokenDeServicio = token("turnos-service", claims ->
            claims
                .claim(AUTHORITIES_CLAIM, "ROLE_SERVICE")
                .audience(List.of("catalogo-service"))
                .issuer("turnos-service")
                .expiresAt(Instant.now().plusSeconds(300))
        );

        pedir(PROFESIONALES, tokenDeServicio).andExpect(status().isForbidden());
    }

    @Test
    void conUnTokenSinRolesResponde403() throws Exception {
        aplicarVersion1();

        pedir(PROFESIONALES, tokenDeUsuario("")).andExpect(status().isForbidden());
    }

    @Test
    void conLaBaseVaciaResponde503ConUnCodeEstable() throws Exception {
        transactionTemplate.executeWithoutResult(tx -> {
            SyncStatus estado = syncStatusRepository.load();
            estado.recordError(SyncErrorType.SNAPSHOT_FAILED, "La cátedra no respondió", T1);
            syncStatusRepository.save(estado);
        });

        pedir(PROFESIONALES, tokenDeUsuario("ROLE_USER"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(header().doesNotExist("Retry-After"))
            .andExpect(jsonPath("$.status").value(503))
            .andExpect(jsonPath("$.code").value(CatalogErrorResponses.CATALOG_NOT_INITIALIZED))
            .andExpect(jsonPath("$.message").value("error.http.503"))
            .andExpect(jsonPath("$.path").value(PROFESIONALES))
            .andExpect(jsonPath("$.type").value("https://www.jhipster.tech/problem/problem-with-message"));
    }

    // ---- ayudantes ----

    private void aplicarVersion1() {
        aplicar(
            1,
            List.of(categoria(1, "Clinica medica", true), categoria(2, "Pediatria", false)),
            List.of(
                profesional(5, 1, "Carla", "Diaz", true),
                profesional(2, 1, "Ana", "Ruiz", false),
                profesional(3, 2, "Ángel", "Núñez", true),
                profesional(1, 1, "Ana", "Perez", true),
                profesional(4, 2, "Bruno", "Gomez", false)
            )
        );
    }

    private void aplicar(long version, List<ProfessionalCategory> categorias, List<Professional> profesionales) {
        transactionTemplate.executeWithoutResult(tx -> {
            catalogRepository.replaceAll(categorias, profesionales, List.of());
            SyncStatus estado = syncStatusRepository.load();
            estado.recordSnapshotApplied(version, SyncTrigger.STARTUP, T1);
            syncStatusRepository.save(estado);
        });
    }

    private ResultActions pedir(String url, String token) throws Exception {
        return mvc.perform(get(url).header(AUTHORIZATION, BEARER + token));
    }

    /** Con {@code param} y no en la URL: {@code get(url)} es una plantilla y volvería a codificar un {@code %20}. */
    private ResultActions pedirConNombre(String nombre) throws Exception {
        return mvc.perform(get(PROFESIONALES).param("name", nombre).header(AUTHORIZATION, BEARER + tokenDeUsuario("ROLE_USER")));
    }

    private String tokenDeUsuario(String roles) {
        return token("ana.perez", claims -> claims.claim(AUTHORITIES_CLAIM, roles).claim("userId", 7));
    }

    private String token(String sujeto, Consumer<JwtClaimsSet.Builder> claims) {
        return createSignedToken(jwtKey, sujeto, claims);
    }

    private static ProfessionalCategory categoria(long id, String nombre, boolean habilitada) {
        return new ProfessionalCategory(id, nombre, null, habilitada, CREADO, CREADO);
    }

    private static Professional profesional(long id, long categoryId, String firstName, String lastName, boolean enabled) {
        return new Professional(id, categoryId, firstName, lastName, enabled, CREADO, CREADO);
    }
}
