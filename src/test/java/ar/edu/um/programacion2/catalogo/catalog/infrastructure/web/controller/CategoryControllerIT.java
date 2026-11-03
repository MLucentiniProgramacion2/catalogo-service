package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.controller;

import static ar.edu.um.programacion2.catalogo.security.SecurityUtils.AUTHORITIES_CLAIM;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.BEARER;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.createExpiredToken;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.createSignedToken;
import static org.hamcrest.Matchers.contains;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CU-06 de punta a punta: JWT firmado con la clave de test, MockMvc y PostgreSQL real. La copia local se prepara con los
 * puertos, como la deja CU-02, y se limpia con SQL.
 * <p>
 * El token de usuario tiene la forma que emite turnos: {@code auth} es un texto con los roles separados por espacio.
 */
@AutoConfigureMockMvc
@IntegrationTest
class CategoryControllerIT {

    private static final String CATEGORIAS = "/api/categories";
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

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    @BeforeEach
    @AfterEach
    void volverAlEstadoInicial() {
        SyncTestDatabase.reset(transactionTemplate, em);
    }

    @Test
    void sinFiltroDevuelveTodasOrdenadasPorIdConLaVersionLocal() throws Exception {
        aplicarVersion1();

        pedir(CATEGORIAS, tokenDeUsuario("ROLE_USER"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(
                content().json(
                    """
                    {
                      "catalogVersion": 1,
                      "categories": [
                        {"id": 1, "name": "Pediatria", "description": null, "enabled": false},
                        {"id": 2, "name": "Cardiologia", "description": "Corazon", "enabled": true},
                        {"id": 3, "name": "Clinica medica", "description": "Atencion clinica general", "enabled": true}
                      ]
                    }
                    """,
                    // STRICT: sin campos de más (ni createdAt ni updatedAt, CU-06 paso 4) y en este orden.
                    JsonCompareMode.STRICT
                )
            );
    }

    @Test
    void conEnabledTrueSoloLasHabilitadas() throws Exception {
        aplicarVersion1();

        pedir(CATEGORIAS + "?enabled=true", tokenDeUsuario("ROLE_USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.catalogVersion").value(1))
            .andExpect(jsonPath("$.categories[*].id").value(contains(2, 3)));
    }

    @Test
    void conEnabledFalseSoloLasDeshabilitadas() throws Exception {
        aplicarVersion1();

        pedir(CATEGORIAS + "?enabled=false", tokenDeUsuario("ROLE_USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.categories[*].id").value(contains(1)));
    }

    @Test
    void unAdminSoloConRoleAdminTambienPuede() throws Exception {
        aplicarVersion1();

        pedir(CATEGORIAS, tokenDeUsuario("ROLE_ADMIN")).andExpect(status().isOk());
    }

    @Test
    void conVersionYSinCategoriasDevuelveUnaListaVacia() throws Exception {
        aplicar(1, List.of());

        pedir(CATEGORIAS, tokenDeUsuario("ROLE_USER"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"catalogVersion\": 1, \"categories\": []}", JsonCompareMode.STRICT));
    }

    @Test
    void sinTokenResponde401() throws Exception {
        mvc.perform(get(CATEGORIAS)).andExpect(status().isUnauthorized());
    }

    @Test
    void conLaFirmaAlteradaResponde401() throws Exception {
        aplicarVersion1();
        String[] partes = tokenDeUsuario("ROLE_USER").split("\\.");
        char[] firma = partes[2].toCharArray();
        firma[10] = firma[10] == 'A' ? 'B' : 'A';

        pedir(CATEGORIAS, partes[0] + "." + partes[1] + "." + new String(firma)).andExpect(status().isUnauthorized());
    }

    @Test
    void conElTokenVencidoResponde401() throws Exception {
        aplicarVersion1();

        pedir(CATEGORIAS, createExpiredToken(jwtKey)).andExpect(status().isUnauthorized());
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

        pedir(CATEGORIAS, tokenDeServicio).andExpect(status().isForbidden());
    }

    @Test
    void conUnEnabledInvalidoResponde400ConElFormatoDeJHipster() throws Exception {
        aplicarVersion1();

        pedir(CATEGORIAS + "?enabled=abc", tokenDeUsuario("ROLE_USER"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.message").value("error.http.400"))
            .andExpect(jsonPath("$.path").value(CATEGORIAS))
            .andExpect(jsonPath("$.type").value("https://www.jhipster.tech/problem/problem-with-message"));
    }

    @Test
    void conLaBaseVaciaResponde503ConUnCodeEstable() throws Exception {
        // Como queda el servicio si falla el snapshot del arranque: el error registrado y ninguna versión aplicada.
        transactionTemplate.executeWithoutResult(tx -> {
            SyncStatus estado = syncStatusRepository.load();
            estado.recordError(SyncErrorType.SNAPSHOT_FAILED, "La cátedra no respondió", T1);
            syncStatusRepository.save(estado);
        });

        pedir(CATEGORIAS, tokenDeUsuario("ROLE_USER"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(header().doesNotExist("Retry-After"))
            .andExpect(jsonPath("$.status").value(503))
            .andExpect(jsonPath("$.code").value(CatalogErrorResponses.CATALOG_NOT_INITIALIZED))
            .andExpect(jsonPath("$.title").value("Service Unavailable"))
            .andExpect(jsonPath("$.message").value("error.http.503"))
            .andExpect(jsonPath("$.path").value(CATEGORIAS))
            .andExpect(jsonPath("$.type").value("https://www.jhipster.tech/problem/problem-with-message"))
            .andExpect(jsonPath("$.detail").value("El catálogo todavía no está disponible: no se aplicó ninguna versión"));
    }

    private void aplicarVersion1() {
        // Desordenadas a propósito: el orden de la respuesta lo tiene que poner la consulta.
        aplicar(
            1,
            List.of(
                categoria(3, "Clinica medica", "Atencion clinica general", true),
                categoria(1, "Pediatria", null, false),
                categoria(2, "Cardiologia", "Corazon", true)
            )
        );
    }

    private void aplicar(long version, List<ProfessionalCategory> categorias) {
        transactionTemplate.executeWithoutResult(tx -> {
            catalogRepository.replaceAll(categorias, List.of(), List.of());
            SyncStatus estado = syncStatusRepository.load();
            estado.recordSnapshotApplied(version, SyncTrigger.STARTUP, T1);
            syncStatusRepository.save(estado);
        });
    }

    private ResultActions pedir(String url, String token) throws Exception {
        return mvc.perform(get(url).header(AUTHORIZATION, BEARER + token));
    }

    private String tokenDeUsuario(String roles) {
        return token("ana.perez", claims -> claims.claim(AUTHORITIES_CLAIM, roles).claim("userId", 7));
    }

    private String token(String sujeto, Consumer<JwtClaimsSet.Builder> claims) {
        return createSignedToken(jwtKey, sujeto, claims);
    }

    private static ProfessionalCategory categoria(long id, String nombre, String descripcion, boolean habilitada) {
        return new ProfessionalCategory(id, nombre, descripcion, habilitada, CREADO, CREADO);
    }
}
