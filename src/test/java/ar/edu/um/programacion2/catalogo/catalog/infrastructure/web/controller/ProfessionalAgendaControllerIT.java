package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.controller;

import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.BEARER;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.createServiceToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CU-07 de punta a punta: el token técnico de servicio firmado con la clave de test, MockMvc y PostgreSQL. Fija el
 * contrato con turnos: la ruta, la forma exacta del JSON (con las horas en {@code HH:mm:ss}), los errores y su
 * {@code code}. La matriz de seguridad de la ruta está en {@code ServiceTokenAuthorizationIT}.
 * <p>
 * La copia: la categoría 1 habilitada y la 2 deshabilitada; el profesional 15 (de la 1, con horarios habilitados y uno
 * deshabilitado), el 16 deshabilitado y el 17 habilitado en la categoría deshabilitada. Versión 57.
 */
@AutoConfigureMockMvc
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ProfessionalAgendaControllerIT {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");

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

    private ListAppender<ILoggingEvent> log;

    @BeforeEach
    void prepararLaCopiaYElLog() {
        SyncTestDatabase.reset(transactionTemplate, em);
        log = new ListAppender<>();
        log.start();
        logger().addAppender(log);
    }

    @AfterEach
    void limpiar() {
        logger().detachAppender(log);
        SyncTestDatabase.reset(transactionTemplate, em);
    }

    @Test
    void devuelveElProfesionalSuCategoriaYSusHorariosHabilitadosEnOrdenConLaVersion() throws Exception {
        copiaEnLa57();

        pedir("/api/service/professionals/15/agenda")
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(
                content().json(
                    """
                    {
                      "catalogVersion": 57,
                      "professional": {
                        "id": 15, "firstName": "Ana", "lastName": "Gomez", "enabled": true,
                        "category": {"id": 1, "name": "Clínica", "enabled": true}
                      },
                      "weeklySchedules": [
                        {"id": 23, "dayOfWeek": "MONDAY", "startTime": "08:00:00", "endTime": "09:00:00", "slotDurationMinutes": 15},
                        {"id": 21, "dayOfWeek": "MONDAY", "startTime": "09:00:00", "endTime": "12:00:00", "slotDurationMinutes": 30},
                        {"id": 24, "dayOfWeek": "FRIDAY", "startTime": "09:00:00", "endTime": "13:00:30", "slotDurationMinutes": 30}
                      ]
                    }
                    """,
                    JsonCompareMode.STRICT
                )
            );
    }

    @Test
    void unProfesionalDeshabilitadoVieneConEnabledFalseYSinHorarios() throws Exception {
        copiaEnLa57();

        pedir("/api/service/professionals/16/agenda")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.catalogVersion").value(57))
            .andExpect(jsonPath("$.professional.id").value(16))
            .andExpect(jsonPath("$.professional.enabled").value(false))
            .andExpect(jsonPath("$.weeklySchedules").isEmpty());
    }

    @Test
    void unProfesionalHabilitadoEnUnaCategoriaDeshabilitadaVieneConSusHorarios() throws Exception {
        copiaEnLa57();

        pedir("/api/service/professionals/17/agenda")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.professional.enabled").value(true))
            .andExpect(jsonPath("$.professional.category.enabled").value(false))
            .andExpect(jsonPath("$.weeklySchedules[0].id").value(26));
    }

    @Test
    void unProfesionalQueNoEstaEnLaCopiaResponde404ConUnCodeEstable() throws Exception {
        copiaEnLa57();

        pedir("/api/service/professionals/999/agenda")
            .andExpect(status().isNotFound())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.code").value(CatalogErrorResponses.PROFESSIONAL_NOT_FOUND))
            .andExpect(jsonPath("$.detail").value("El profesional no está en el catálogo"))
            .andExpect(jsonPath("$.path").value("/api/service/professionals/999/agenda"));
    }

    @Test
    void conLaBaseVaciaResponde503ConElMismoCodeQueLasConsultasDeUsuario() throws Exception {
        pedir("/api/service/professionals/15/agenda")
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value(CatalogErrorResponses.CATALOG_NOT_INITIALIZED))
            .andExpect(jsonPath("$.detail").value("El catálogo todavía no está disponible: no se aplicó ninguna versión"))
            // La periódica está apagada en src/test: sin plazo, sin header.
            .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    void unIdQueNoEsUnNumeroResponde400SinRepetirElValorEnElDetalle() throws Exception {
        copiaEnLa57();

        // El valor solo aparece como parte de la ruta pedida (path e instance), como en cualquier error de JHipster.
        pedir("/api/service/professionals/abc123xyz/agenda")
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").value("Failed to convert 'id'"))
            .andExpect(jsonPath("$.message").value("error.http.400"))
            .andExpect(jsonPath("$.path").value("/api/service/professionals/abc123xyz/agenda"));
    }

    @Test
    void lasHorasSalenComoEstanGuardadasConLaJvmEnMenosTres() throws Exception {
        copiaEnLa57();
        // Insertadas con SQL como texto: no pasan por ninguna conversión de Java al guardarse.
        transactionTemplate.executeWithoutResult(tx -> {
            em.createNativeQuery("delete from weekly_schedule where professional_id = 15").executeUpdate();
            em
                .createNativeQuery(
                    "insert into weekly_schedule (id, professional_id, day_of_week, start_time, end_time, slot_duration_minutes, enabled, created_at, updated_at) " +
                    "values (30, 15, 'MONDAY', '18:00', '21:00', 30, true, now(), now()), (31, 15, 'TUESDAY', '22:00:30', '02:00', 45, true, now(), now())"
                )
                .executeUpdate();
        });
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/Argentina/Buenos_Aires"));
        try {
            pedir("/api/service/professionals/15/agenda")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeklySchedules[0].startTime").value("18:00:00"))
                .andExpect(jsonPath("$.weeklySchedules[0].endTime").value("21:00:00"))
                .andExpect(jsonPath("$.weeklySchedules[1].startTime").value("22:00:30"))
                .andExpect(jsonPath("$.weeklySchedules[1].endTime").value("02:00:00"));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void elLoginDelUsuarioQuedaEnElLogYNoEnLaRespuesta() throws Exception {
        copiaEnLa57();

        mvc
            .perform(
                get("/api/service/professionals/15/agenda")
                    .header(AUTHORIZATION, BEARER + createServiceToken(jwtKey))
                    .header("X-User-Login", "ana.perez")
            )
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("ana.perez"))));

        assertThat(mensajes()).containsExactly("Agenda vigente del profesional 15 pedida por turnos (X-User-Login=ana.perez)");
    }

    @Test
    void sinElHeaderElLogLoDice() throws Exception {
        copiaEnLa57();

        pedir("/api/service/professionals/15/agenda").andExpect(status().isOk());

        assertThat(mensajes()).containsExactly("Agenda vigente del profesional 15 pedida por turnos (sin X-User-Login)");
    }

    @Test
    void unLoginConSaltoDeLineaLoRechazaElFirewallConUn400SinRepetirloNiLoguearlo(CapturedOutput salida) throws Exception {
        // Primera barrera: el StrictHttpFirewall de Spring Security no deja leer un header con caracteres de control. El
        // pedido no llega al controller, así que no hay línea de log que partir.
        copiaEnLa57();

        mvc
            .perform(
                get("/api/service/professionals/15/agenda")
                    .header(AUTHORIZATION, BEARER + createServiceToken(jwtKey))
                    .header("X-User-Login", "ana\r\n2026-11-09T13:19:00 INFO entrada falsa")
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("The request contains a header or a path with characters that are not allowed"))
            .andExpect(content().string(not(containsString("entrada falsa"))));

        assertThat(mensajes()).isEmpty();
        assertThat(salida.getAll()).doesNotContain("entrada falsa");
    }

    @Test
    void unLoginInvalidoQuePasaElFirewallSeLogueaComoInvalidoSinRepetirlo() throws Exception {
        // Segunda barrera: lo que el firewall deja pasar (espacios, símbolos, más de 50 caracteres) tampoco se escribe.
        copiaEnLa57();

        mvc
            .perform(
                get("/api/service/professionals/15/agenda")
                    .header(AUTHORIZATION, BEARER + createServiceToken(jwtKey))
                    .header("X-User-Login", "ana perez <script>")
            )
            .andExpect(status().isOk());

        assertThat(mensajes()).containsExactly("Agenda vigente del profesional 15 pedida por turnos (X-User-Login inválido)");
    }

    @Test
    void elHeaderNoAutorizaNiCambiaLosDatos() throws Exception {
        // Con o sin X-User-Login, la misma respuesta: no se usa para filtrar.
        copiaEnLa57();
        String sin = pedir("/api/service/professionals/15/agenda").andReturn().getResponse().getContentAsString();

        String con = mvc
            .perform(
                get("/api/service/professionals/15/agenda")
                    .header(AUTHORIZATION, BEARER + createServiceToken(jwtKey))
                    .header("X-User-Login", "otro.usuario")
            )
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(con).isEqualTo(sin);
    }

    private ResultActions pedir(String ruta) throws Exception {
        return mvc.perform(get(ruta).header(AUTHORIZATION, BEARER + createServiceToken(jwtKey)));
    }

    private List<String> mensajes() {
        return log.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(ProfessionalAgendaController.class);
    }

    private void copiaEnLa57() {
        transactionTemplate.executeWithoutResult(tx -> {
            catalogRepository.replaceAll(
                List.of(
                    new ProfessionalCategory(1, "Clínica", null, true, CREADO, CREADO),
                    new ProfessionalCategory(2, "Pediatría", null, false, CREADO, CREADO)
                ),
                List.of(
                    new Professional(15, 1, "Ana", "Gomez", true, CREADO, CREADO),
                    new Professional(16, 1, "Luis", "Perez", false, CREADO, CREADO),
                    new Professional(17, 2, "Eva", "Diaz", true, CREADO, CREADO)
                ),
                List.of(
                    horario(21, 15, DayOfWeek.MONDAY, "09:00", "12:00", 30, true),
                    horario(22, 15, DayOfWeek.TUESDAY, "14:00", "18:00", 20, false),
                    horario(23, 15, DayOfWeek.MONDAY, "08:00", "09:00", 15, true),
                    horario(24, 15, DayOfWeek.FRIDAY, "09:00", "13:00:30", 30, true),
                    horario(25, 16, DayOfWeek.MONDAY, "09:00", "12:00", 30, true),
                    horario(26, 17, DayOfWeek.WEDNESDAY, "09:00", "12:00", 30, true)
                )
            );
            SyncStatus estado = syncStatusRepository.load();
            estado.recordSnapshotApplied(57, SyncTrigger.STARTUP, CREADO);
            syncStatusRepository.save(estado);
        });
    }

    private static WeeklySchedule horario(long id, long profesional, DayOfWeek dia, String desde, String hasta, int duracion, boolean habilitado) {
        return new WeeklySchedule(id, profesional, dia, LocalTime.parse(desde), LocalTime.parse(hasta), duracion, habilitado, CREADO, CREADO);
    }
}
