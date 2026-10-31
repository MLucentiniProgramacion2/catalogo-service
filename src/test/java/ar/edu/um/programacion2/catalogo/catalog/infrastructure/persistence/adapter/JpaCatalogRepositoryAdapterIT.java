package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.adapter;

import static java.util.Comparator.comparingLong;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity.ProfessionalEntity;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.mapper.ProfessionalCategoryMapper;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.mapper.ProfessionalMapper;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.mapper.WeeklyScheduleMapper;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.repository.JpaProfessionalCategoryRepository;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.repository.JpaProfessionalRepository;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.repository.JpaWeeklyScheduleRepository;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Copia local del catálogo contra PostgreSQL real, a través del puerto.
 * <p>
 * La clase no es {@code @Transactional}: el adaptador exige que quien llama abra la transacción, y hay que probar
 * commits, rollbacks y llamadas sin transacción. Cada test abre las suyas con {@link TransactionTemplate} y lee en una
 * transacción aparte. Como se commitea de verdad, las tablas se vacían antes y después de cada test.
 */
@IntegrationTest
class JpaCatalogRepositoryAdapterIT {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final Instant ACTUALIZADO = Instant.parse("2026-09-10T13:40:00Z");
    private static final Instant CAMBIADO = Instant.parse("2026-09-11T10:00:00Z");

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @Autowired
    private JpaProfessionalCategoryRepository categoryRepository;

    @Autowired
    private JpaProfessionalRepository professionalRepository;

    @Autowired
    private JpaWeeklyScheduleRepository weeklyScheduleRepository;

    @Autowired
    private ProfessionalCategoryMapper categoryMapper;

    @Autowired
    private ProfessionalMapper professionalMapper;

    @Autowired
    private WeeklyScheduleMapper weeklyScheduleMapper;

    @Autowired
    private PostgreSQLContainer databaseContainer;

    @BeforeEach
    @AfterEach
    void vaciarLasTablas() {
        // Con SQL y no con el adaptador: la limpieza no puede depender del código que se prueba.
        transactionTemplate.executeWithoutResult(tx -> {
            em.createNativeQuery("delete from weekly_schedule").executeUpdate();
            em.createNativeQuery("delete from professional").executeUpdate();
            em.createNativeQuery("delete from professional_category").executeUpdate();
        });
    }

    @Test
    void unCatalogoGuardadoSeLeeIgual() {
        Catalogo catalogo = new Catalogo(
            List.of(
                categoria(1, "Clinica medica", "Atencion clinica general", true),
                categoria(2, "Pediatria", "", false),
                categoria(3, "Cardiologia", null, true)
            ),
            List.of(profesional(15, 1, "Ana", true), profesional(16, 2, "Luis", false)),
            List.of(
                // Los dos formatos del contrato: HH:mm y HH:mm:ss, con segundos distintos de cero.
                horario(21, 15, LocalTime.parse("09:00"), LocalTime.parse("12:00"), true),
                horario(22, 16, LocalTime.parse("14:30:15"), LocalTime.parse("18:45:30"), false)
            )
        );

        guardar(catalogo);

        assertThat(leer()).isEqualTo(catalogo);
    }

    @Test
    void reemplazarBorraActualizaYAgrega() {
        guardar(
            new Catalogo(
                List.of(categoria(1, "Clinica medica", "Atencion clinica general", true), categoria(2, "Pediatria", null, true)),
                List.of(profesional(15, 1, "Ana", true), profesional(16, 2, "Luis", true)),
                List.of(
                    horario(21, 15, LocalTime.of(9, 0), LocalTime.of(12, 0), true),
                    horario(22, 16, LocalTime.of(8, 0), LocalTime.of(10, 0), true)
                )
            )
        );
        // Sale la categoría 2 con su profesional 16 y su horario 22. Cambian la categoría 1, el profesional 15 (pasa a la
        // categoría nueva 3 y queda deshabilitado) y el horario 21. Se agregan la categoría 3, el profesional 17 y el
        // horario 23.
        Catalogo nuevo = new Catalogo(
            List.of(
                new ProfessionalCategory(1, "Clinica general", "Renombrada", true, CREADO, CAMBIADO),
                categoria(3, "Cardiologia", null, true)
            ),
            List.of(new Professional(15, 3, "Ana", "Gomez", false, CREADO, CAMBIADO), profesional(17, 1, "Sofia", true)),
            List.of(
                new WeeklySchedule(21, 15, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(13, 0), 20, false, CREADO, CAMBIADO),
                horario(23, 17, LocalTime.of(15, 0), LocalTime.of(19, 0), true)
            )
        );

        guardar(nuevo);

        assertThat(leer()).isEqualTo(nuevo);
    }

    @Test
    void reemplazarDosVecesConElMismoCatalogoDejaElMismoResultado() {
        Catalogo catalogo = catalogoChico();

        guardar(catalogo);
        guardar(catalogo);

        assertThat(leer()).isEqualTo(catalogo);
    }

    @Test
    void reemplazarPorUnCatalogoVacioDejaLaCopiaVacia() {
        guardar(catalogoChico());

        guardar(new Catalogo(List.of(), List.of(), List.of()));

        assertThat(leer()).isEqualTo(new Catalogo(List.of(), List.of(), List.of()));
    }

    @Test
    void reemplazaUnCatalogoDelTamanioDelDeLaCatedra() {
        Catalogo catalogo = catalogoDeLaCatedra();
        guardar(catalogoChico());

        guardar(catalogo);

        assertThat(leer()).isEqualTo(catalogo);
    }

    @Test
    void siLaTransaccionSeDeshaceLaCopiaAnteriorQuedaIntacta() {
        Catalogo anterior = catalogoChico();
        guardar(anterior);

        transactionTemplate.executeWithoutResult(tx -> {
            catalogRepository.replaceAll(List.of(), List.of(), List.of());
            tx.setRollbackOnly();
        });

        assertThat(leer()).isEqualTo(anterior);
    }

    @Test
    void sinTransaccionFallaYNoBorraNada() {
        Catalogo anterior = catalogoChico();
        guardar(anterior);

        assertThatThrownBy(() -> catalogRepository.replaceAll(List.of(), List.of(), List.of())).isInstanceOf(
            IllegalTransactionStateException.class
        );

        assertThat(leer()).isEqualTo(anterior);
    }

    @Test
    void unaReferenciaInexistenteFallaDentroDelReemplazoYNoCambiaNada() {
        Catalogo anterior = catalogoChico();
        guardar(anterior);

        // El profesional 15 apunta a la categoría 999, que no viene: lo rechaza la clave foránea en el flush del
        // adaptador, ya traducido por @Repository.
        Catalogo roto = new Catalogo(
            List.of(categoria(1, "Clinica medica", null, true)),
            List.of(profesional(15, 999, "Ana", true)),
            List.of()
        );

        assertThatThrownBy(() -> guardar(roto))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("fk_professional_category");

        assertThat(leer()).isEqualTo(anterior);
    }

    @Test
    void leerLaCategoriaDeUnProfesionalNoInicializaElProxy() {
        guardar(catalogoChico());

        transactionTemplate.executeWithoutResult(tx -> {
            ProfessionalEntity entidad = professionalRepository.findById(15L).orElseThrow();

            Professional profesional = professionalMapper.toDomain(entidad);

            assertThat(profesional.categoryId()).isEqualTo(1L);
            assertThat(em.getEntityManagerFactory().getPersistenceUnitUtil().isLoaded(entidad.getCategory())).isFalse();
        });
    }

    @Test
    void mientrasNoSeConfirmaOtraTransaccionSigueViendoLaCopiaAnterior() throws SQLException {
        guardar(catalogoChico());

        // Conexión aparte, fuera del pool (en los tests, Hikari tiene una sola conexión, la que usa la transacción).
        try (
            Connection otra = DriverManager.getConnection(
                databaseContainer.getJdbcUrl(),
                databaseContainer.getUsername(),
                databaseContainer.getPassword()
            )
        ) {
            try (Statement statement = otra.createStatement()) {
                // Si el reemplazo tomara un lock exclusivo (TRUNCATE), la lectura esperaría hasta el commit.
                statement.execute("set statement_timeout = '2s'");
            }

            transactionTemplate.executeWithoutResult(tx -> {
                catalogRepository.replaceAll(List.of(categoria(9, "Nueva", null, true)), List.of(), List.of());

                assertThat(idsDeCategorias(otra)).as("lo que ve otra transacción antes del commit").containsExactly(1L, 2L);
            });

            assertThat(idsDeCategorias(otra)).as("lo que ve otra transacción después del commit").containsExactly(9L);
        }
    }

    private static List<Long> idsDeCategorias(Connection connection) {
        try (
            Statement statement = connection.createStatement();
            ResultSet filas = statement.executeQuery("select id from professional_category order by id")
        ) {
            List<Long> ids = new ArrayList<>();
            while (filas.next()) {
                ids.add(filas.getLong("id"));
            }
            return ids;
        } catch (SQLException e) {
            return fail("Otra transacción no pudo leer la copia mientras se reemplazaba (¿un lock exclusivo?): " + e.getMessage(), e);
        }
    }

    private void guardar(Catalogo catalogo) {
        transactionTemplate.executeWithoutResult(tx ->
            catalogRepository.replaceAll(catalogo.categorias(), catalogo.profesionales(), catalogo.horarios())
        );
    }

    /** Lee las tres tablas en una transacción nueva, ordenadas por ID, ya traducidas al dominio. */
    private Catalogo leer() {
        return transactionTemplate.execute(tx ->
            new Catalogo(
                categoryRepository
                    .findAll()
                    .stream()
                    .map(categoryMapper::toDomain)
                    .sorted(comparingLong(ProfessionalCategory::id))
                    .toList(),
                professionalRepository
                    .findAll()
                    .stream()
                    .map(professionalMapper::toDomain)
                    .sorted(comparingLong(Professional::id))
                    .toList(),
                weeklyScheduleRepository
                    .findAll()
                    .stream()
                    .map(weeklyScheduleMapper::toDomain)
                    .sorted(comparingLong(WeeklySchedule::id))
                    .toList()
            )
        );
    }

    /** Las tres colecciones, ordenadas por ID para poder comparar con {@code equals}. */
    private record Catalogo(List<ProfessionalCategory> categorias, List<Professional> profesionales, List<WeeklySchedule> horarios) {}

    private static Catalogo catalogoChico() {
        return new Catalogo(
            List.of(categoria(1, "Clinica medica", "Atencion clinica general", true), categoria(2, "Pediatria", null, false)),
            List.of(profesional(15, 1, "Ana", true), profesional(16, 2, "Luis", false)),
            List.of(horario(21, 15, LocalTime.of(9, 0), LocalTime.of(12, 0), true))
        );
    }

    /** Del tamaño del snapshot real de la cátedra: 8 categorías, 24 profesionales y 44 horarios. */
    private static Catalogo catalogoDeLaCatedra() {
        List<ProfessionalCategory> categorias = IntStream.rangeClosed(1, 8)
            .mapToObj(i -> categoria(i, "Categoria " + i, null, i % 4 != 0))
            .toList();
        List<Professional> profesionales = IntStream.rangeClosed(1, 24)
            .mapToObj(i -> profesional(100 + i, 1 + (i % 8), "Profesional " + i, i % 6 != 0))
            .toList();
        List<WeeklySchedule> horarios = IntStream.rangeClosed(1, 44)
            .mapToObj(i -> horario(1000 + i, 101 + (i % 24), LocalTime.of(8 + (i % 4), 0), LocalTime.of(13, 30), i % 5 != 0))
            .toList();
        return new Catalogo(categorias, profesionales, horarios);
    }

    private static ProfessionalCategory categoria(long id, String nombre, String descripcion, boolean habilitada) {
        return new ProfessionalCategory(id, nombre, descripcion, habilitada, CREADO, ACTUALIZADO);
    }

    private static Professional profesional(long id, long categoriaId, String nombre, boolean habilitado) {
        return new Professional(id, categoriaId, nombre, "Gomez", habilitado, CREADO, ACTUALIZADO);
    }

    private static WeeklySchedule horario(long id, long profesionalId, LocalTime desde, LocalTime hasta, boolean habilitado) {
        return new WeeklySchedule(id, profesionalId, DayOfWeek.MONDAY, desde, hasta, 30, habilitado, CREADO, ACTUALIZADO);
    }
}
