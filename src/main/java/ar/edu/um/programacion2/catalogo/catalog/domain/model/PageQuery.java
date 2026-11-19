package ar.edu.um.programacion2.catalogo.catalog.domain.model;

/**
 * Página pedida de un resultado: número (desde 0) y tamaño.
 * <p>
 * Es un tipo propio y no el {@code Pageable} de Spring Data porque el dominio no depende de frameworks (lo verifica
 * ArchUnit); el adaptador de persistencia lo traduce. El nombre evita chocar con {@code PageRequest} de Spring Data.
 * <p>
 * El tamaño tiene un máximo para que una sola consulta no pueda traer el catálogo entero de una vez.
 *
 * @param number número de página, desde 0
 * @param size   cantidad de elementos por página, de 1 a {@link #MAX_SIZE}
 */
public record PageQuery(int number, int size) {
    /** Tamaño máximo de página. */
    public static final int MAX_SIZE = 100;

    public PageQuery {
        if (number < 0) {
            throw new IllegalArgumentException("El número de página no puede ser negativo: " + number);
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("El tamaño de página tiene que estar entre 1 y " + MAX_SIZE + ": " + size);
        }
    }
}
