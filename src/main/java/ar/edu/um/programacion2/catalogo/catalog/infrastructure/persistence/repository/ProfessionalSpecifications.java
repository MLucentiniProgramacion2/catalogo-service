package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.repository;

import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity.ProfessionalEntity;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import java.util.Objects;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.jpa.domain.Specification;

/**
 * Los filtros de la búsqueda de profesionales (CU-05), uno por método, para leerlos y probarlos por separado. El
 * adaptador combina los que vienen en el criterio.
 * <p>
 * Ninguno hace un join: la categoría se compara por su ID, que está en la fila del profesional ({@code category_id}).
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
