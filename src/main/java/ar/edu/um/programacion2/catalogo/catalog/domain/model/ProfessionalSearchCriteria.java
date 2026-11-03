package ar.edu.um.programacion2.catalogo.catalog.domain.model;

/**
 * Filtros de la búsqueda de profesionales (CU-05, paso 1). Todos son opcionales salvo {@code enabled}, que siempre tiene
 * un valor: si el usuario no lo indica, vale {@code true} (DP-01).
 * <p>
 * El valor por defecto es una regla de negocio y por eso vive acá y no en la web: lo aplica {@link #of}, que recibe lo
 * que llegó (con {@code null} como "no lo indicó"). El constructor canónico exige el valor ya resuelto.
 * <p>
 * El nombre se normaliza siempre: se recortan los espacios de los extremos y un texto vacío o de solo espacios cuenta
 * como filtro ausente ({@code null}). Los espacios internos no se tocan. El largo máximo lo controla la web: es una
 * protección del límite de la API, no una regla del catálogo.
 *
 * @param categoryId ID de la categoría, o {@code null} para todas. Una categoría que no existe no es un error: no
 *                   coincide ningún profesional.
 * @param name       texto a buscar en "nombre apellido", sin distinguir mayúsculas, o {@code null} para no filtrar
 * @param enabled    {@code true} para los habilitados, {@code false} para los deshabilitados (bajas lógicas)
 */
public record ProfessionalSearchCriteria(Long categoryId, String name, boolean enabled) {
    /** Valor de {@code enabled} cuando el usuario no lo indica (DP-01): los que se pueden reservar. */
    public static final boolean DEFAULT_ENABLED = true;

    public ProfessionalSearchCriteria {
        name = name == null || name.isBlank() ? null : name.strip();
    }

    /**
     * Arma el criterio con lo que pidió el usuario, aplicando los valores por defecto.
     *
     * @param enabled {@code null} si el usuario no lo indicó: se toma {@link #DEFAULT_ENABLED}
     */
    public static ProfessionalSearchCriteria of(Long categoryId, String name, Boolean enabled) {
        return new ProfessionalSearchCriteria(categoryId, name, enabled == null ? DEFAULT_ENABLED : enabled);
    }
}
