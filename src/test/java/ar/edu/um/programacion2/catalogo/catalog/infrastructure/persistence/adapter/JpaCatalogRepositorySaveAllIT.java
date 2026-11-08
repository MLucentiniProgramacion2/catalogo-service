package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Crear o actualizar entidades sueltas, como lo hace CU-04, contra PostgreSQL real y a través del puerto.
 * <p>
 * Las sentencias se cuentan con las estadísticas de Hibernate, prendidas solo en esta clase: así se ve qué cuesta una
 * versión real (un profesional que cambia {@code enabled}) y que reaplicarla no escribe nada.
 */
@IntegrationTest
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class JpaCatalogRepositorySaveAllIT {

    private static final Instant CREADO = Instant.parse("2026-09-01T03:00:00Z");
    private static final Instant CAMBIADO = Instant.parse("2026-11-08T01:19:34.423296Z");

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics estadisticas;

    @BeforeEach
    void copiaConLaCategoria101YDosProfesionales() {
        SyncTestDatabase.reset(transactionTemplate, em);
        transactionTemplate.executeWithoutResult(tx ->
            catalogRepository.replaceAll(
                List.of(categoria(101, true)),
                List.of(profesional(201, true, CREADO), profesional(202, true, CREADO)),
                List.of(horario(342, 201, "16:00", "19:00"))
            )
        );
        estadisticas = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        estadisticas.clear();
    }

    @AfterEach
    void limpiar() {
        SyncTestDatabase.reset(transactionTemplate, em);
    }

    @Test
    void unaBajaLogicaQuedaConEnabledFalseYNoTocaALosDemas() {
        transactionTemplate.executeWithoutResult(tx -> catalogRepository.saveAll(List.of(), List.of(profesional(201, false, CAMBIADO)), List.of()));

        assertThat(fila("select enabled::text || ' ' || updated_at::text from professional where id = 201")).isEqualTo(
            "false 2026-11-08 01:19:34.423296"
        );
        assertThat(fila("select enabled::text from professional where id = 202")).isEqualTo("true");
        assertThat(SyncTestDatabase.scheduleIds(transactionTemplate, em)).containsExactly(342L);
    }

    @Test
    void creaLoQueNoExisteEnOrdenCategoriasProfesionalesYHorarios() {
        transactionTemplate.executeWithoutResult(tx ->
            catalogRepository.saveAll(
                List.of(categoria(105, true)),
                List.of(profesional(203, 105, true, CREADO)),
                List.of(horario(350, 203, "09:00", "12:00"))
            )
        );

        assertThat(SyncTestDatabase.categoryIds(transactionTemplate, em)).containsExactly(101L, 105L);
        assertThat(SyncTestDatabase.professionalIds(transactionTemplate, em)).containsExactly(201L, 202L, 203L);
        assertThat(SyncTestDatabase.scheduleIds(transactionTemplate, em)).containsExactly(342L, 350L);
        assertThat(estadisticas.getEntityInsertCount()).isEqualTo(3);
    }

    @Test
    void unaVersionRealSonTresSentenciasYReaplicarlaNoEscribeNada() {
        // Lo que hace CU-04 con la versión real: verificar la categoría y guardar el profesional 201.
        Runnable version = () ->
            transactionTemplate.executeWithoutResult(tx -> {
                catalogRepository.findExistingCategoryIds(Set.of(101L));
                catalogRepository.saveAll(List.of(), List.of(profesional(201, false, CAMBIADO)), List.of());
            });

        version.run();
        long primera = estadisticas.getPrepareStatementCount();
        long modificadas = estadisticas.getEntityUpdateCount();
        estadisticas.clear();
        version.run();

        // select de la categoría, select del profesional (el del merge) y update del profesional.
        assertThat(primera).isEqualTo(3);
        assertThat(modificadas).isEqualTo(1);
        // Reaplicada: los mismos dos select, y ningún update porque el estado ya era ese.
        assertThat(estadisticas.getPrepareStatementCount()).isEqualTo(2);
        assertThat(estadisticas.getEntityUpdateCount()).isZero();
    }

    @Test
    void informaQueReferenciasExistenIncluidasLasDeshabilitadas() {
        transactionTemplate.executeWithoutResult(tx -> catalogRepository.saveAll(List.of(categoria(101, false)), List.of(), List.of()));

        Set<Long> categorias = transactionTemplate.execute(tx -> catalogRepository.findExistingCategoryIds(Set.of(101L, 105L)));
        Set<Long> profesionales = transactionTemplate.execute(tx -> catalogRepository.findExistingProfessionalIds(Set.of(202L, 223L)));

        assertThat(categorias).containsExactly(101L);
        assertThat(profesionales).containsExactly(202L);
    }

    @Test
    void sinIdsNoConsulta() {
        transactionTemplate.executeWithoutResult(tx -> {
            assertThat(catalogRepository.findExistingCategoryIds(Set.of())).isEmpty();
            assertThat(catalogRepository.findExistingProfessionalIds(Set.of())).isEmpty();
        });

        assertThat(estadisticas.getPrepareStatementCount()).isZero();
    }

    @Test
    void sinTransaccionFallaAntesDeTocarNada() {
        assertThatThrownBy(() -> catalogRepository.saveAll(List.of(), List.of(profesional(201, false, CAMBIADO)), List.of())).isInstanceOf(
            IllegalTransactionStateException.class
        );
        assertThatThrownBy(() -> catalogRepository.findExistingCategoryIds(Set.of(101L))).isInstanceOf(IllegalTransactionStateException.class);

        assertThat(fila("select enabled::text from professional where id = 201")).isEqualTo("true");
    }

    @Test
    void unaReferenciaRotaLaRechazaLaBaseYNoQuedaNada() {
        // No debería llegar: CU-04 la verifica antes. Si llega, la FK la frena y la transacción no deja nada a medias.
        assertThatThrownBy(() ->
            transactionTemplate.executeWithoutResult(tx ->
                catalogRepository.saveAll(List.of(categoria(105, true)), List.of(profesional(203, 999, true, CREADO)), List.of())
            )
        ).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(SyncTestDatabase.categoryIds(transactionTemplate, em)).containsExactly(101L);
        assertThat(SyncTestDatabase.professionalIds(transactionTemplate, em)).containsExactly(201L, 202L);
    }

    private String fila(String sql) {
        return transactionTemplate.execute(tx -> (String) em.createNativeQuery(sql, String.class).getSingleResult());
    }

    private static ProfessionalCategory categoria(long id, boolean habilitada) {
        return new ProfessionalCategory(id, "Categoría " + id, null, habilitada, CREADO, CREADO);
    }

    private static Professional profesional(long id, boolean habilitado, Instant actualizado) {
        return profesional(id, 101, habilitado, actualizado);
    }

    private static Professional profesional(long id, long categoria, boolean habilitado, Instant actualizado) {
        return new Professional(id, categoria, "Ana", "Torres", habilitado, CREADO, actualizado);
    }

    private static WeeklySchedule horario(long id, long profesional, String desde, String hasta) {
        return new WeeklySchedule(id, profesional, DayOfWeek.THURSDAY, LocalTime.parse(desde), LocalTime.parse(hasta), 30, true, CREADO, CREADO);
    }
}
