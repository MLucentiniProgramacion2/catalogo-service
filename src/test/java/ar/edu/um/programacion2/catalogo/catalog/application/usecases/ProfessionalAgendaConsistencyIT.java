package ar.edu.um.programacion2.catalogo.catalog.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.application.service.CatalogService;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalAgenda;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Versioned;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.LocalCatalogVersionProvider;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.ProfessionalAgendaRepository;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.adapter.JpaLocalCatalogVersionAdapter;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * La versión y la agenda que responde CU-07 corresponden entre sí aunque un snapshot se confirme después de leer la
 * versión. La misma técnica que {@link SearchProfessionalsConsistencyIT}: la versión 1 tiene los horarios 21 y 22 del
 * profesional 15; la 2, confirmada en el medio por otra conexión (fuera del pool de una sola conexión de los tests),
 * deshabilita el 21 y agrega el 23. Turnos no puede recibir los horarios de la 2 con la versión 1.
 */
@IntegrationTest
@Import(ProfessionalAgendaConsistencyIT.SnapshotInTheMiddleConfiguration.class)
class ProfessionalAgendaConsistencyIT {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private LocalCatalogVersionProvider versionProvider;

    @Autowired
    private ProfessionalAgendaRepository agendaRepository;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private SyncStatusRepository syncStatusRepository;

    @Autowired
    private SnapshotInTheMiddle snapshotInTheMiddle;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @BeforeEach
    void aplicarLaVersion1() {
        SyncTestDatabase.reset(transactionTemplate, em);
        transactionTemplate.executeWithoutResult(tx -> {
            catalogRepository.replaceAll(
                List.of(new ProfessionalCategory(1, "Clínica", null, true, CREADO, CREADO)),
                List.of(new Professional(15, 1, "Ana", "Gomez", true, CREADO, CREADO)),
                List.of(horario(21, DayOfWeek.MONDAY), horario(22, DayOfWeek.TUESDAY))
            );
            SyncStatus estado = syncStatusRepository.load();
            estado.recordSnapshotApplied(1, SyncTrigger.STARTUP, CREADO);
            syncStatusRepository.save(estado);
        });
    }

    @AfterEach
    void limpiar() {
        snapshotInTheMiddle.disarm();
        SyncTestDatabase.reset(transactionTemplate, em);
    }

    @Test
    void unSnapshotConfirmadoDespuesDeLeerLaVersionNoMezclaVersionYHorarios() {
        snapshotInTheMiddle.arm();

        Versioned<ProfessionalAgenda> agenda = catalogService.getProfessionalAgenda(15);

        assertThat(snapshotInTheMiddle.applied()).as("el snapshot 2 se confirmó en el medio").isTrue();
        assertThat(agenda.version()).isEqualTo(1);
        assertThat(ids(agenda)).containsExactly(21L, 22L);

        // Lo confirmado sí quedó: el pedido siguiente ya ve la versión 2 con sus horarios.
        Versioned<ProfessionalAgenda> siguiente = catalogService.getProfessionalAgenda(15);
        assertThat(siguiente.version()).isEqualTo(2);
        assertThat(ids(siguiente)).containsExactly(22L, 23L);
    }

    @Test
    void controlConReadCommittedLaMismaIntercalacionMezclaVersiones() {
        snapshotInTheMiddle.arm();

        // TransactionTemplate usa el nivel por defecto de la base: en PostgreSQL, READ COMMITTED.
        Versioned<List<WeeklySchedule>> mezcla = transactionTemplate.execute(tx -> {
            long version = versionProvider.findAppliedVersion().orElseThrow();
            return new Versioned<>(version, agendaRepository.findEnabledSchedules(15));
        });

        assertThat(mezcla.version()).isEqualTo(1);
        assertThat(mezcla.content()).extracting(WeeklySchedule::id).containsExactlyInAnyOrder(22L, 23L);
    }

    @Test
    void laConexionVuelveAlPoolConElNivelPorDefecto() {
        catalogService.getProfessionalAgenda(15);

        Object nivel = transactionTemplate.execute(tx -> em.createNativeQuery("show transaction_isolation").getSingleResult());
        assertThat(nivel).isEqualTo("read committed");
    }

    private static List<Long> ids(Versioned<ProfessionalAgenda> agenda) {
        return agenda.content().weeklySchedules().stream().map(WeeklySchedule::id).toList();
    }

    private static WeeklySchedule horario(long id, DayOfWeek dia) {
        return new WeeklySchedule(id, 15, dia, LocalTime.of(9, 0), LocalTime.of(12, 0), 30, true, CREADO, CREADO);
    }

    @TestConfiguration
    static class SnapshotInTheMiddleConfiguration {

        @Bean
        @Primary
        SnapshotInTheMiddle agendaSnapshotInTheMiddle(JpaLocalCatalogVersionAdapter adapter, PostgreSQLContainer database) {
            return new SnapshotInTheMiddle(adapter, database);
        }
    }

    /** Después de leer la versión, si está armado, confirma por otra conexión la versión 2 de la agenda. Se desarma solo. */
    static class SnapshotInTheMiddle implements LocalCatalogVersionProvider {

        private final LocalCatalogVersionProvider delegate;
        private final PostgreSQLContainer database;
        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicBoolean applied = new AtomicBoolean();

        SnapshotInTheMiddle(LocalCatalogVersionProvider delegate, PostgreSQLContainer database) {
            this.delegate = delegate;
            this.database = database;
        }

        void arm() {
            applied.set(false);
            armed.set(true);
        }

        void disarm() {
            armed.set(false);
        }

        boolean applied() {
            return applied.get();
        }

        @Override
        public OptionalLong findAppliedVersion() {
            OptionalLong version = delegate.findAppliedVersion();
            if (armed.getAndSet(false)) {
                commitVersion2();
                applied.set(true);
            }
            return version;
        }

        private void commitVersion2() {
            try (
                Connection otra = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
                Statement statement = otra.createStatement()
            ) {
                otra.setAutoCommit(false);
                // Si la lectura en curso bloqueara al snapshot, esto fallaría en vez de colgar el test.
                statement.execute("set local lock_timeout = '2s'");
                statement.executeUpdate("update weekly_schedule set enabled = false where id = 21");
                statement.executeUpdate(
                    """
                    insert into weekly_schedule (id, professional_id, day_of_week, start_time, end_time, slot_duration_minutes, enabled, created_at, updated_at)
                    values (23, 15, 'WEDNESDAY', '09:00', '12:00', 30, true, now(), now())
                    """
                );
                statement.executeUpdate("update sync_status set local_version = 2 where id = 1");
                otra.commit();
            } catch (SQLException e) {
                throw new IllegalStateException("No se pudo confirmar la versión 2 desde otra conexión", e);
            }
        }
    }
}
