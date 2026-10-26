package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Esquema del catálogo contra PostgreSQL real: las tablas que crea Liquibase guardan y devuelven las entidades, y las
 * restricciones de la base rechazan lo que tienen que rechazar.
 * <p>
 * Que cada entidad coincida con su tabla ya lo comprueba Hibernate al arrancar ({@code ddl-auto: validate}); acá se
 * prueba el ida y vuelta real. Cada test hace {@code flush} (manda el SQL) y {@code clear} (vacía el contexto de
 * persistencia), para que el {@code find} lea de la base y no de la memoria. {@code @Transactional} deshace todo al final.
 */
@IntegrationTest
@Transactional
class CatalogEntitiesIT {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final Instant ACTUALIZADO = Instant.parse("2026-09-10T13:40:00Z");

    @Autowired
    private EntityManager em;

    @Test
    void guardaYLeeUnaCategoria() {
        em.persist(categoria(1L));
        em.flush();
        em.clear();

        ProfessionalCategoryEntity leida = em.find(ProfessionalCategoryEntity.class, 1L);

        assertThat(leida.getName()).isEqualTo("Clinica medica");
        assertThat(leida.getDescription()).isEqualTo("Atencion clinica general");
        assertThat(leida.getEnabled()).isTrue();
        assertThat(leida.getCreatedAt()).isEqualTo(CREADO);
        assertThat(leida.getUpdatedAt()).isEqualTo(ACTUALIZADO);
    }

    @Test
    void guardaUnaCategoriaSinDescripcion() {
        ProfessionalCategoryEntity categoria = categoria(1L);
        categoria.setDescription(null);
        em.persist(categoria);
        em.flush();
        em.clear();

        assertThat(em.find(ProfessionalCategoryEntity.class, 1L).getDescription()).isNull();
    }

    @Test
    void guardaYLeeUnProfesionalConSuCategoria() {
        ProfessionalCategoryEntity categoria = categoria(1L);
        em.persist(categoria);
        em.persist(profesional(15L, categoria));
        em.flush();
        em.clear();

        ProfessionalEntity leido = em.find(ProfessionalEntity.class, 15L);

        assertThat(leido.getFirstName()).isEqualTo("Ana");
        assertThat(leido.getLastName()).isEqualTo("Gomez");
        assertThat(leido.getEnabled()).isFalse();
        assertThat(leido.getCreatedAt()).isEqualTo(CREADO);
        assertThat(leido.getUpdatedAt()).isEqualTo(ACTUALIZADO);
        assertThat(leido.getCategory().getId()).isEqualTo(1L);
    }

    @Test
    void guardaYLeeUnHorarioSemanal() {
        ProfessionalCategoryEntity categoria = categoria(1L);
        ProfessionalEntity profesional = profesional(15L, categoria);
        em.persist(categoria);
        em.persist(profesional);
        em.persist(horario(21L, profesional));
        em.flush();
        em.clear();

        WeeklyScheduleEntity leido = em.find(WeeklyScheduleEntity.class, 21L);

        assertThat(leido.getProfessional().getId()).isEqualTo(15L);
        assertThat(leido.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(leido.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(leido.getEndTime()).isEqualTo(LocalTime.of(12, 0));
        assertThat(leido.getSlotDurationMinutes()).isEqualTo(30);
        assertThat(leido.getEnabled()).isTrue();
        assertThat(leido.getCreatedAt()).isEqualTo(CREADO);
        assertThat(leido.getUpdatedAt()).isEqualTo(ACTUALIZADO);
    }

    @Test
    void elDiaDeLaSemanaSeGuardaPorNombre() {
        ProfessionalCategoryEntity categoria = categoria(1L);
        ProfessionalEntity profesional = profesional(15L, categoria);
        em.persist(categoria);
        em.persist(profesional);
        em.persist(horario(21L, profesional));
        em.flush();

        Object dia = em.createNativeQuery("select day_of_week from weekly_schedule where id = 21").getSingleResult();

        assertThat(dia).isEqualTo("MONDAY");
    }

    @Test
    void rechazaUnProfesionalConUnaCategoriaInexistente() {
        // getReference no consulta la base: arma una referencia al ID 999, que no existe.
        em.persist(profesional(15L, em.getReference(ProfessionalCategoryEntity.class, 999L)));

        assertThatThrownBy(em::flush)
            .isInstanceOf(ConstraintViolationException.class)
            .extracting(e -> ((ConstraintViolationException) e).getConstraintName())
            .isEqualTo("fk_professional_category");
    }

    @Test
    void rechazaUnHorarioConUnProfesionalInexistente() {
        em.persist(horario(21L, em.getReference(ProfessionalEntity.class, 999L)));

        assertThatThrownBy(em::flush)
            .isInstanceOf(ConstraintViolationException.class)
            .extracting(e -> ((ConstraintViolationException) e).getConstraintName())
            .isEqualTo("fk_weekly_schedule_professional");
    }

    @Test
    void rechazaUnDiaDeLaSemanaInexistente() {
        ProfessionalCategoryEntity categoria = categoria(1L);
        em.persist(categoria);
        em.persist(profesional(15L, categoria));
        em.flush();

        // Con la entidad no se puede: el enum no lo permite. Se inserta con SQL para probar el CHECK de la base.
        var insert = em.createNativeQuery(
            """
            insert into weekly_schedule
                (id, professional_id, day_of_week, start_time, end_time, slot_duration_minutes, enabled, created_at, updated_at)
            values (21, 15, 'LUNES', '09:00', '12:00', 30, true, now(), now())
            """
        );

        assertThatThrownBy(insert::executeUpdate)
            .isInstanceOf(ConstraintViolationException.class)
            .extracting(e -> ((ConstraintViolationException) e).getConstraintName())
            .isEqualTo("ck_weekly_schedule_day_of_week");
    }

    @Test
    void existenLosIndicesDeLaBusqueda() {
        // ddl-auto: validate no revisa índices; se consultan en el catálogo de PostgreSQL.
        List<String> indices = em
            .createNativeQuery("select indexname from pg_indexes where tablename in ('professional', 'weekly_schedule')", String.class)
            .getResultList();

        assertThat(indices).contains("idx_professional_category_id", "idx_weekly_schedule_professional_day");
    }

    private static ProfessionalCategoryEntity categoria(Long id) {
        ProfessionalCategoryEntity categoria = new ProfessionalCategoryEntity();
        categoria.setId(id);
        categoria.setName("Clinica medica");
        categoria.setDescription("Atencion clinica general");
        categoria.setEnabled(true);
        categoria.setCreatedAt(CREADO);
        categoria.setUpdatedAt(ACTUALIZADO);
        return categoria;
    }

    // Deshabilitado a propósito: una baja lógica se guarda igual que un alta.
    private static ProfessionalEntity profesional(Long id, ProfessionalCategoryEntity categoria) {
        ProfessionalEntity profesional = new ProfessionalEntity();
        profesional.setId(id);
        profesional.setCategory(categoria);
        profesional.setFirstName("Ana");
        profesional.setLastName("Gomez");
        profesional.setEnabled(false);
        profesional.setCreatedAt(CREADO);
        profesional.setUpdatedAt(ACTUALIZADO);
        return profesional;
    }

    private static WeeklyScheduleEntity horario(Long id, ProfessionalEntity profesional) {
        WeeklyScheduleEntity horario = new WeeklyScheduleEntity();
        horario.setId(id);
        horario.setProfessional(profesional);
        horario.setDayOfWeek(DayOfWeek.MONDAY);
        horario.setStartTime(LocalTime.of(9, 0));
        horario.setEndTime(LocalTime.of(12, 0));
        horario.setSlotDurationMinutes(30);
        horario.setEnabled(true);
        horario.setCreatedAt(CREADO);
        horario.setUpdatedAt(ACTUALIZADO);
        return horario;
    }
}
