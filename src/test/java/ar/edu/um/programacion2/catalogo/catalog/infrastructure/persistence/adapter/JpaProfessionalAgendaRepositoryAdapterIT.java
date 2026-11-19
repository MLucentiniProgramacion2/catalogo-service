package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalWithCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.ProfessionalAgendaRepository;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La lectura de la agenda (CU-07) contra PostgreSQL: qué devuelve, cuántas sentencias cuesta (estadísticas de Hibernate)
 * y que las horas salen como están guardadas aunque la JVM esté en -03.
 */
@IntegrationTest
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class JpaProfessionalAgendaRepositoryAdapterIT {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");

    @Autowired
    private ProfessionalAgendaRepository agendaRepository;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    @AfterEach
    void volverAlEstadoInicial() {
        SyncTestDatabase.reset(transactionTemplate, em);
    }

    @Test
    void elProfesionalConSuCategoriaEsUnaSolaSentencia() {
        copia(List.of(new WeeklySchedule(21, 15, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), 30, true, CREADO, CREADO)));
        Statistics estadisticas = estadisticas();

        Optional<ProfessionalWithCategory> leido = transactionTemplate.execute(tx -> {
            Optional<ProfessionalWithCategory> profesional = agendaRepository.findProfessionalWithCategory(15);
            // La categoría ya viene cargada: leerla no hace otra consulta.
            profesional.map(p -> p.category().name());
            return profesional;
        });

        assertThat(leido).isPresent();
        assertThat(leido.orElseThrow().professional().firstName()).isEqualTo("Ana");
        assertThat(leido.orElseThrow().category().name()).isEqualTo("Clínica");
        assertThat(estadisticas.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void unProfesionalQueNoEstaEnLaCopiaEsVacio() {
        copia(List.of());

        Optional<ProfessionalWithCategory> leido = transactionTemplate.execute(tx -> agendaRepository.findProfessionalWithCategory(999));

        assertThat(leido).isEmpty();
    }

    @Test
    void losHorariosHabilitadosSonUnaSolaSentenciaYNoTraenLosDeshabilitadosNiLosDeOtro() {
        copia(
            List.of(
                new WeeklySchedule(21, 15, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), 30, true, CREADO, CREADO),
                new WeeklySchedule(22, 15, DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), 30, false, CREADO, CREADO),
                new WeeklySchedule(23, 16, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), 30, true, CREADO, CREADO)
            )
        );
        Statistics estadisticas = estadisticas();

        List<WeeklySchedule> horarios = transactionTemplate.execute(tx -> agendaRepository.findEnabledSchedules(15));

        assertThat(horarios).extracting(WeeklySchedule::id).containsExactly(21L);
        assertThat(estadisticas.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void lasHorasSalenComoEstanGuardadasConLaJvmEnMenosTres() {
        copia(List.of());
        // Insertadas con SQL, como texto: así no pasan por ninguna conversión de Java al guardarse.
        transactionTemplate.executeWithoutResult(tx ->
            em
                .createNativeQuery(
                    "insert into weekly_schedule (id, professional_id, day_of_week, start_time, end_time, slot_duration_minutes, enabled, created_at, updated_at) " +
                    "values (30, 15, 'MONDAY', '18:00', '21:00', 30, true, now(), now()), (31, 15, 'TUESDAY', '22:00:30', '02:00', 45, true, now(), now())"
                )
                .executeUpdate()
        );
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/Argentina/Buenos_Aires"));
        try {
            List<WeeklySchedule> horarios = transactionTemplate.execute(tx -> agendaRepository.findEnabledSchedules(15));

            assertThat(horarios)
                .extracting(WeeklySchedule::id, WeeklySchedule::startTime, WeeklySchedule::endTime)
                .containsExactlyInAnyOrder(
                    tuple(30L, LocalTime.of(18, 0), LocalTime.of(21, 0)),
                    tuple(31L, LocalTime.of(22, 0, 30), LocalTime.of(2, 0))
                );
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void sinTransaccionFallaAntesDeLeer() {
        assertThatThrownBy(() -> agendaRepository.findProfessionalWithCategory(15)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> agendaRepository.findEnabledSchedules(15)).isInstanceOf(IllegalTransactionStateException.class);
    }

    /** Las categorías 1 y el profesional 15 (más el 16, para que haya horarios de otro), con estos horarios. */
    private void copia(List<WeeklySchedule> horarios) {
        transactionTemplate.executeWithoutResult(tx ->
            catalogRepository.replaceAll(
                List.of(new ProfessionalCategory(1, "Clínica", null, true, CREADO, CREADO)),
                List.of(
                    new Professional(15, 1, "Ana", "Gomez", true, CREADO, CREADO),
                    new Professional(16, 1, "Luis", "Perez", true, CREADO, CREADO)
                ),
                horarios
            )
        );
        em.clear();
    }

    /** Las estadísticas desde cero: lo que cuente es solo lo que hace el test a partir de acá. */
    private Statistics estadisticas() {
        Statistics estadisticas = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        estadisticas.clear();
        return estadisticas;
    }
}
