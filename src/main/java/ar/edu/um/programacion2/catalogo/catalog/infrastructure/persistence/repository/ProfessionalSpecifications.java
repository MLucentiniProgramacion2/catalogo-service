package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.repository;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.AvailabilityFilter;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity.ProfessionalEntity;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity.WeeklyScheduleEntity;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.jpa.domain.Specification;

/**
 * Los filtros de la búsqueda de profesionales (CU-05), uno por método, para leerlos y probarlos por separado. El
 * adaptador combina los que vienen en el criterio.
 * <p>
 * Ninguno hace un join: la categoría se compara por su ID, que está en la fila del profesional ({@code category_id}), y
 * la disponibilidad es una subconsulta {@code EXISTS}.
 */
public final class ProfessionalSpecifications {

    /** Carácter de escape del {@code LIKE}. */
    static final char LIKE_ESCAPE = '\\';

    private ProfessionalSpecifications() {}

    /** Habilitados o deshabilitados ({@code enabled = ?}). */
    public static Specification<ProfessionalEntity> hasEnabled(boolean enabled) {
        return (root, query, cb) -> cb.equal(root.get("enabled"), enabled);
    }

    /** De una categoría ({@code category_id = ?}). Una categoría que no existe no coincide con nadie. */
    public static Specification<ProfessionalEntity> inCategory(long categoryId) {
        return (root, query, cb) -> cb.equal(root.get("category").get("id"), categoryId);
    }

    /**
     * El texto aparece en "nombre apellido", sin distinguir mayúsculas:
     * {@code lower(first_name || ' ' || last_name) like lower('%texto%') escape '\'}.
     * <p>
     * Concatenados con un espacio, para que "ana pe" encuentre a "Ana Perez". {@code lower} se aplica en la base a los
     * dos lados, así el plegado de mayúsculas es uno solo (el de PostgreSQL, que con un locale UTF-8 incluye las letras
     * con tilde). El texto se escapa: un {@code %} o un {@code _} del usuario se busca como letra, no como comodín.
     * <p>
     * El patrón va como parámetro de la sentencia ({@code ?}), no escrito dentro del SQL. {@code cb.literal(...)} lo
     * escribiría dentro ({@code like lower('%texto%')}): Hibernate escapa las comillas, pero el texto del usuario no
     * tiene que formar parte de la sentencia. Para poder aplicarle {@code lower} hace falta una expresión, y la única
     * forma de armar una expresión-parámetro dentro de una {@code Specification} es {@code value(...)}, de la API de
     * Hibernate.
     */
    public static Specification<ProfessionalEntity> nameContains(String text) {
        String pattern = "%" + escapeLike(Objects.requireNonNull(text, "Falta el texto a buscar")) + "%";
        return (root, query, cb) -> {
            Expression<String> fullName = cb.concat(cb.concat(root.get("firstName"), " "), root.get("lastName"));
            return cb.like(cb.lower(fullName), cb.lower(parameter(cb, pattern)), LIKE_ESCAPE);
        };
    }

    /**
     * Atiende según sus horarios ({@link AvailabilityFilter}): existe al menos un horario del profesional, habilitado, de
     * ese día, no vacío y que se superpone con la franja.
     *
     * <pre>
     * exists (select ws.id from weekly_schedule ws
     *         where ws.professional_id = p.id and ws.enabled and ws.day_of_week = ?
     *           and ws.start_time &lt; ws.end_time      -- [start, end) no vacío
     *           and ws.end_time &gt; ?                  -- solo si hay from
     *           and ws.start_time &lt; ?)               -- solo si hay to
     * </pre>
     *
     * Con {@code EXISTS} y no con un join: un profesional con dos horarios que coinciden aparecería dos veces con el
     * join, y el count de la paginación contaría filas de más. {@code EXISTS} solo pregunta si hay alguno, así que cada
     * profesional aparece una vez sin necesitar {@code distinct}. La subconsulta está correlacionada: compara contra el
     * profesional de la consulta de afuera ({@code root}).
     */
    public static Specification<ProfessionalEntity> attendsOn(AvailabilityFilter availability) {
        Objects.requireNonNull(availability, "Falta el filtro de disponibilidad");
        return (root, query, cb) -> {
            Subquery<Long> schedules = query.subquery(Long.class);
            Root<WeeklyScheduleEntity> schedule = schedules.from(WeeklyScheduleEntity.class);
            Expression<LocalTime> start = schedule.get("startTime");
            Expression<LocalTime> end = schedule.get("endTime");

            List<Predicate> conditions = new ArrayList<>();
            conditions.add(cb.equal(schedule.get("professional"), root));
            conditions.add(cb.isTrue(schedule.get("enabled")));
            conditions.add(cb.equal(schedule.get("dayOfWeek"), availability.day()));
            conditions.add(cb.lessThan(start, end));
            if (availability.from() != null) {
                conditions.add(cb.greaterThan(end, availability.from()));
            }
            if (availability.to() != null) {
                conditions.add(cb.lessThan(start, availability.to()));
            }
            schedules.select(schedule.get("id")).where(conditions.toArray(Predicate[]::new));
            return cb.exists(schedules);
        };
    }

    /** El valor como parámetro de la sentencia (ver {@link #nameContains}). */
    private static Expression<String> parameter(CriteriaBuilder cb, String value) {
        if (cb instanceof HibernateCriteriaBuilder hibernate) {
            return hibernate.value(value);
        }
        throw new IllegalStateException("La búsqueda por nombre necesita el CriteriaBuilder de Hibernate");
    }

    /** Antepone el escape a sí mismo y a los dos comodines del {@code LIKE}: primero el escape, para no duplicarlo. */
    static String escapeLike(String text) {
        return text
            .replace(String.valueOf(LIKE_ESCAPE), "" + LIKE_ESCAPE + LIKE_ESCAPE)
            .replace("%", LIKE_ESCAPE + "%")
            .replace("_", LIKE_ESCAPE + "_");
    }
}
