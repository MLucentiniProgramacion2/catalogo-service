package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.adapter;

import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.AvailabilityFilter;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.PageQuery;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalSearchCriteria;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalWithCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ResultPage;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.LongStream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El filtro de disponibilidad de CU-05 contra PostgreSQL real: intervalos semiabiertos {@code [inicio, fin)}, franjas
 * abiertas de un lado, horarios deshabilitados o vacíos y profesionales con varios horarios que coinciden.
 * <p>
 * Salvo que el test diga otra cosa, el profesional 1 atiende los lunes de 09:00 a 12:00.
 */
@IntegrationTest
class JpaProfessionalAvailabilitySearchIT {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final PageQuery PRIMERA = new PageQuery(0, 20);

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @BeforeEach
    @AfterEach
    void vaciarLasTablas() {
        SyncTestDatabase.reset(transactionTemplate, em);
    }

    @Test
    void soloConElDiaAlcanzaConUnHorarioDeEseDia() {
        guardar(List.of(profesional(1, true), profesional(2, true)), List.of(horario(10, 1, MONDAY, "09:00", "12:00"), horario(20, 2, TUESDAY, "09:00", "12:00")));

        assertThat(ids(buscar(AvailabilityFilter.on(MONDAY)))).containsExactly(1L);
        assertThat(ids(buscar(AvailabilityFilter.on(TUESDAY)))).containsExactly(2L);
        assertThat(buscar(AvailabilityFilter.on(DayOfWeek.SUNDAY)).items()).isEmpty();
    }

    @Test
    void conDiaYFranjaAlcanzaConQueSeSuperpongan() {
        lunesDe9a12();

        assertThat(ids(buscar(franja("10:00", "11:00")))).as("adentro").containsExactly(1L);
        assertThat(ids(buscar(franja("08:00", "10:00")))).as("cruza el inicio").containsExactly(1L);
        assertThat(ids(buscar(franja("11:00", "13:00")))).as("cruza el fin").containsExactly(1L);
        assertThat(ids(buscar(franja("08:00", "13:00")))).as("lo contiene").containsExactly(1L);
        assertThat(buscar(franja("13:00", "14:00")).items()).as("después").isEmpty();
        assertThat(buscar(franja("06:00", "08:00")).items()).as("antes").isEmpty();
    }

    @Test
    void unHorarioQueTerminaALas12NoSeSuperponeConUnaFranjaQueEmpiezaALas12() {
        lunesDe9a12();

        assertThat(buscar(franja("12:00", "13:00")).items()).isEmpty();
        assertThat(ids(buscar(franja("11:59", "13:00")))).containsExactly(1L);
    }

    @Test
    void unaFranjaQueTerminaALas9NoSeSuperponeConUnHorarioQueEmpiezaALas9() {
        lunesDe9a12();

        assertThat(buscar(franja("08:00", "09:00")).items()).isEmpty();
        assertThat(ids(buscar(franja("08:00", "09:01")))).containsExactly(1L);
    }

    @Test
    void unaFranjaAbiertaDeUnLadoSoloAcotaElOtro() {
        lunesDe9a12();

        assertThat(ids(buscar(new AvailabilityFilter(MONDAY, LocalTime.of(11, 0), null)))).as("desde las 11").containsExactly(1L);
        assertThat(buscar(new AvailabilityFilter(MONDAY, LocalTime.of(12, 0), null)).items()).as("desde las 12").isEmpty();
        assertThat(ids(buscar(new AvailabilityFilter(MONDAY, null, LocalTime.of(9, 1))))).as("hasta las 9:01").containsExactly(1L);
        assertThat(buscar(new AvailabilityFilter(MONDAY, null, LocalTime.of(9, 0))).items()).as("hasta las 9").isEmpty();
    }

    @Test
    void lasHorasConSegundosSeComparanExactas() {
        guardar(List.of(profesional(1, true)), List.of(horario(10, 1, MONDAY, "09:00:30", "12:00:00")));

        assertThat(buscar(new AvailabilityFilter(MONDAY, null, LocalTime.of(9, 0, 30))).items()).isEmpty();
        assertThat(ids(buscar(new AvailabilityFilter(MONDAY, null, LocalTime.of(9, 0, 31))))).containsExactly(1L);
    }

    @Test
    void unHorarioDeshabilitadoNoCuenta() {
        guardar(List.of(profesional(1, true)), List.of(horario(10, 1, MONDAY, "09:00", "12:00", 30, false)));

        assertThat(buscar(AvailabilityFilter.on(MONDAY)).items()).isEmpty();
        assertThat(buscar(franja("10:00", "11:00")).items()).isEmpty();
    }

    @Test
    void unProfesionalDeshabilitadoConHorariosSoloApareceAlBuscarDeshabilitados() {
        guardar(List.of(profesional(1, false)), List.of(horario(10, 1, MONDAY, "09:00", "12:00")));

        assertThat(buscar(AvailabilityFilter.on(MONDAY)).items()).isEmpty();
        assertThat(ids(buscar(false, AvailabilityFilter.on(MONDAY)))).containsExactly(1L);
    }

    @Test
    void unHorarioConInicioPosteriorAlFinNoCuentaNiConFranjaNiSinFranja() {
        guardar(List.of(profesional(1, true)), List.of(horario(10, 1, MONDAY, "14:00", "10:00")));

        assertThat(buscar(AvailabilityFilter.on(MONDAY)).items()).isEmpty();
        assertThat(buscar(franja("00:00", "23:59")).items()).isEmpty();
        assertThat(buscar(franja("09:00", "11:00")).items()).isEmpty();
        assertThat(buscar(franja("13:00", "15:00")).items()).isEmpty();
    }

    @Test
    void unHorarioConInicioIgualAlFinNoCuenta() {
        guardar(List.of(profesional(1, true)), List.of(horario(10, 1, MONDAY, "10:00", "10:00")));

        assertThat(buscar(AvailabilityFilter.on(MONDAY)).items()).isEmpty();
        assertThat(buscar(franja("09:00", "11:00")).items()).isEmpty();
    }

    @Test
    void unHorarioVacioNoTapaAOtroValidoDelMismoDia() {
        guardar(
            List.of(profesional(1, true)),
            List.of(horario(10, 1, MONDAY, "14:00", "10:00"), horario(11, 1, MONDAY, "15:00", "18:00"))
        );

        assertThat(ids(buscar(franja("16:00", "17:00")))).containsExactly(1L);
        assertThat(buscar(franja("09:00", "11:00")).items()).isEmpty();
    }

    @Test
    void unHorarioConDuracionDeTurnoCeroONegativaCuenta() {
        guardar(
            List.of(profesional(1, true), profesional(2, true)),
            List.of(horario(10, 1, MONDAY, "09:00", "12:00", 0, true), horario(20, 2, MONDAY, "09:00", "12:00", -15, true))
        );

        assertThat(ids(buscar(franja("10:00", "11:00")))).containsExactly(1L, 2L);
    }

    @Test
    void unProfesionalConDosHorariosQueCoincidenApareceUnaSolaVezYCuentaUnaVez() {
        guardar(
            List.of(profesional(1, true), profesional(2, true), profesional(3, true)),
            List.of(
                horario(10, 1, MONDAY, "09:00", "12:00"),
                horario(11, 1, MONDAY, "10:00", "13:00"),
                horario(12, 1, MONDAY, "08:00", "11:30"),
                horario(20, 2, MONDAY, "10:00", "11:00"),
                horario(30, 3, TUESDAY, "10:00", "11:00")
            )
        );

        ResultPage<ProfessionalWithCategory> todos = buscar(franja("10:00", "11:00"));
        assertThat(ids(todos)).containsExactly(1L, 2L);
        assertThat(todos.totalElements()).isEqualTo(2);

        // Con páginas de 1, el total tiene que seguir siendo 2 y la segunda página tiene que traer al 2.
        ResultPage<ProfessionalWithCategory> primera = buscar(true, franja("10:00", "11:00"), new PageQuery(0, 1));
        ResultPage<ProfessionalWithCategory> segunda = buscar(true, franja("10:00", "11:00"), new PageQuery(1, 1));
        assertThat(ids(primera)).containsExactly(1L);
        assertThat(ids(segunda)).containsExactly(2L);
        assertThat(primera.totalElements()).isEqualTo(2);
        assertThat(primera.totalPages()).isEqualTo(2);
    }

    @Test
    void laDisponibilidadSeCombinaConLosOtrosFiltros() {
        guardar(
            List.of(
                new Professional(1, 1, "Ana", "Perez", true, CREADO, CREADO),
                new Professional(2, 2, "Ana", "Ruiz", true, CREADO, CREADO),
                new Professional(3, 1, "Bruno", "Diaz", true, CREADO, CREADO)
            ),
            List.of(horario(10, 1, MONDAY, "09:00", "12:00"), horario(20, 2, MONDAY, "09:00", "12:00"), horario(30, 3, MONDAY, "09:00", "12:00"))
        );

        ResultPage<ProfessionalWithCategory> resultado = transactionTemplate.execute(tx ->
            catalogRepository.searchProfessionals(ProfessionalSearchCriteria.of(1L, "ana", null, franja("10:00", "11:00")), PRIMERA)
        );

        assertThat(ids(resultado)).containsExactly(1L);
    }

    @Test
    void conDisponibilidadUnaPaginaLlenaSiguenSiendoDosSentencias() {
        guardar(
            LongStream.rangeClosed(1, 25).mapToObj(id -> profesional(id, true)).toList(),
            LongStream.rangeClosed(1, 25).mapToObj(id -> horario(100 + id, id, MONDAY, "09:00", "12:00")).toList()
        );
        Statistics estadisticas = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        estadisticas.setStatisticsEnabled(true);
        try {
            estadisticas.clear();

            ResultPage<ProfessionalWithCategory> pagina = buscar(franja("10:00", "11:00"));

            assertThat(pagina.items()).hasSize(20);
            assertThat(pagina.totalElements()).isEqualTo(25);
            assertThat(estadisticas.getPrepareStatementCount()).isEqualTo(2);
        } finally {
            estadisticas.setStatisticsEnabled(false);
        }
    }

    // ---- ayudantes ----

    private void lunesDe9a12() {
        guardar(List.of(profesional(1, true)), List.of(horario(10, 1, MONDAY, "09:00", "12:00")));
    }

    private ResultPage<ProfessionalWithCategory> buscar(AvailabilityFilter disponibilidad) {
        return buscar(null, disponibilidad);
    }

    private ResultPage<ProfessionalWithCategory> buscar(Boolean enabled, AvailabilityFilter disponibilidad) {
        return buscar(enabled, disponibilidad, PRIMERA);
    }

    private ResultPage<ProfessionalWithCategory> buscar(Boolean enabled, AvailabilityFilter disponibilidad, PageQuery pagina) {
        return transactionTemplate.execute(tx ->
            catalogRepository.searchProfessionals(ProfessionalSearchCriteria.of(null, null, enabled, disponibilidad), pagina)
        );
    }

    private static AvailabilityFilter franja(String desde, String hasta) {
        return new AvailabilityFilter(MONDAY, LocalTime.parse(desde), LocalTime.parse(hasta));
    }

    private static List<Long> ids(ResultPage<ProfessionalWithCategory> pagina) {
        return pagina.items().stream().map(r -> r.professional().id()).toList();
    }

    private void guardar(List<Professional> profesionales, List<WeeklySchedule> horarios) {
        List<ProfessionalCategory> categorias = List.of(
            new ProfessionalCategory(1, "Categoria 1", null, true, CREADO, CREADO),
            new ProfessionalCategory(2, "Categoria 2", null, true, CREADO, CREADO)
        );
        transactionTemplate.executeWithoutResult(tx -> catalogRepository.replaceAll(categorias, profesionales, horarios));
    }

    private static Professional profesional(long id, boolean enabled) {
        return new Professional(id, 1, "Nombre", "Apellido " + id, enabled, CREADO, CREADO);
    }

    private static WeeklySchedule horario(long id, long profesional, DayOfWeek dia, String inicio, String fin) {
        return horario(id, profesional, dia, inicio, fin, 30, true);
    }

    private static WeeklySchedule horario(long id, long profesional, DayOfWeek dia, String inicio, String fin, int duracion, boolean enabled) {
        return new WeeklySchedule(id, profesional, dia, LocalTime.parse(inicio), LocalTime.parse(fin), duracion, enabled, CREADO, CREADO);
    }
}
