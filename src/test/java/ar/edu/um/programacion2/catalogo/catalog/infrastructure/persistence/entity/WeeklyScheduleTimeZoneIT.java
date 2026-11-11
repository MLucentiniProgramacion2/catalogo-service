package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.AvailabilityFilter;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.PageQuery;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalSearchCriteria;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.mapper.WeeklyScheduleMapper;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.repository.JpaWeeklyScheduleRepository;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Las horas de {@code weekly_schedule} se guardan y se comparan sin convertir de zona, aunque la JVM no esté en UTC.
 * <p>
 * Con la configuración de JHipster ({@code hibernate.jdbc.time_zone: UTC}), un {@code LocalTime} se convierte de la
 * zona de la JVM a UTC al guardarlo. En el CI y con la JVM en UTC no se nota; con la JVM en -03, 18:00-21:00 quedaba
 * guardado como 21:00-00:00. Por eso estos tests ponen la JVM en -03 ({@code TimeZone.setDefault}) y la restauran al
 * final. La verificación se hace con SQL ({@code ::text}): leer con JPA haría la conversión inversa y lo taparía.
 */
@IntegrationTest
class WeeklyScheduleTimeZoneIT {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final TimeZone MENOS_TRES = TimeZone.getTimeZone("America/Argentina/Buenos_Aires");

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private JpaWeeklyScheduleRepository weeklyScheduleRepository;

    @Autowired
    private WeeklyScheduleMapper weeklyScheduleMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    private TimeZone zonaOriginal;

    @BeforeEach
    void ponerLaJvmEnMenosTres() {
        SyncTestDatabase.reset(transactionTemplate, em);
        zonaOriginal = TimeZone.getDefault();
        TimeZone.setDefault(MENOS_TRES);
    }

    @AfterEach
    void restaurarLaZona() {
        TimeZone.setDefault(zonaOriginal);
        SyncTestDatabase.reset(transactionTemplate, em);
    }

    @Test
    void lasHorasSeGuardanExactamenteComoLasPublicaLaCatedra() {
        guardar(horario(10, DayOfWeek.MONDAY, "18:00", "21:00"), horario(11, DayOfWeek.TUESDAY, "22:00", "02:00"));

        List<String> guardadas = transactionTemplate.execute(tx ->
            em
                .createNativeQuery("select start_time::text || '-' || end_time::text from weekly_schedule order by id", String.class)
                .getResultList()
                .stream()
                .map(String.class::cast)
                .toList()
        );

        assertThat(guardadas).containsExactly("18:00:00-21:00:00", "22:00:00-02:00:00");
    }

    @Test
    void lasHorasSeLeenIgualQueComoSeGuardaron() {
        guardar(horario(10, DayOfWeek.MONDAY, "18:00", "21:00"), horario(11, DayOfWeek.TUESDAY, "22:00", "02:00"));

        List<WeeklySchedule> leidos = transactionTemplate.execute(tx ->
            weeklyScheduleRepository.findAll().stream().map(weeklyScheduleMapper::toDomain).sorted((a, b) -> Long.compare(a.id(), b.id())).toList()
        );

        assertThat(leidos).extracting(WeeklySchedule::startTime).containsExactly(LocalTime.of(18, 0), LocalTime.of(22, 0));
        assertThat(leidos).extracting(WeeklySchedule::endTime).containsExactly(LocalTime.of(21, 0), LocalTime.of(2, 0));
    }

    @Test
    void laBusquedaComparaContraLaHoraSinConvertir() {
        guardar(horario(10, DayOfWeek.MONDAY, "18:00", "21:00"));

        assertThat(atiende(LocalTime.of(20, 0), null)).as("desde las 20").isTrue();
        assertThat(atiende(LocalTime.of(21, 0), null)).as("desde las 21").isFalse();
        assertThat(atiende(null, LocalTime.of(18, 1))).as("hasta las 18:01").isTrue();
        assertThat(atiende(null, LocalTime.of(18, 0))).as("hasta las 18").isFalse();
    }

    /** El camino del incremental (CU-04): crear y después actualizar con {@code saveAll}, que hace {@code merge}. */
    @Test
    void crearOActualizarHorariosTambienGuardaLasHorasSinConvertir() {
        guardar(horario(10, DayOfWeek.MONDAY, "18:00", "21:00"));

        transactionTemplate.executeWithoutResult(tx ->
            catalogRepository.saveAll(
                List.of(),
                List.of(),
                List.of(horario(10, DayOfWeek.MONDAY, "19:30", "22:00"), horario(11, DayOfWeek.TUESDAY, "22:00", "02:00"))
            )
        );

        List<String> guardadas = transactionTemplate.execute(tx ->
            em
                .createNativeQuery("select start_time::text || '-' || end_time::text from weekly_schedule order by id", String.class)
                .getResultList()
                .stream()
                .map(String.class::cast)
                .toList()
        );
        assertThat(guardadas).containsExactly("19:30:00-22:00:00", "22:00:00-02:00:00");
    }

    private boolean atiende(LocalTime desde, LocalTime hasta) {
        return !transactionTemplate
            .execute(tx ->
                catalogRepository.searchProfessionals(
                    ProfessionalSearchCriteria.of(null, null, null, new AvailabilityFilter(DayOfWeek.MONDAY, desde, hasta)),
                    new PageQuery(0, 20)
                )
            )
            .items()
            .isEmpty();
    }

    private void guardar(WeeklySchedule... horarios) {
        transactionTemplate.executeWithoutResult(tx ->
            catalogRepository.replaceAll(
                List.of(new ProfessionalCategory(1, "Categoria 1", null, true, CREADO, CREADO)),
                List.of(new Professional(207, 1, "Nombre", "Apellido", true, CREADO, CREADO)),
                List.of(horarios)
            )
        );
    }

    private static WeeklySchedule horario(long id, DayOfWeek dia, String inicio, String fin) {
        return new WeeklySchedule(id, 207, dia, LocalTime.parse(inicio), LocalTime.parse(fin), 30, true, CREADO, CREADO);
    }
}
