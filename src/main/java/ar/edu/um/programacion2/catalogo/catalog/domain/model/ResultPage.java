package ar.edu.um.programacion2.catalogo.catalog.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * Una página de un resultado, con el total de elementos que cumplen los filtros (no solo los de esta página).
 * <p>
 * Es un tipo propio y no el {@code Page} de Spring Data por la misma razón que {@link PageQuery}. Una página fuera de
 * rango es válida: viene sin elementos y con el total correcto.
 *
 * @param items         los elementos de esta página, en el orden del resultado
 * @param number        número de página, desde 0
 * @param size          tamaño de página pedido (puede haber menos elementos en la última)
 * @param totalElements total de elementos que cumplen los filtros
 * @param <T>           el tipo de los elementos
 */
public record ResultPage<T>(List<T> items, int number, int size, long totalElements) {
    public ResultPage {
        items = List.copyOf(Objects.requireNonNull(items, "Faltan los elementos de la página"));
        if (number < 0) {
            throw new IllegalArgumentException("El número de página no puede ser negativo: " + number);
        }
        if (size < 1) {
            throw new IllegalArgumentException("El tamaño de página tiene que ser positivo: " + size);
        }
        if (items.size() > size) {
            throw new IllegalArgumentException("La página tiene " + items.size() + " elementos y su tamaño es " + size);
        }
        if (totalElements < 0) {
            throw new IllegalArgumentException("El total no puede ser negativo: " + totalElements);
        }
    }

    /** Cantidad de páginas: 0 si no hay elementos; la última puede estar incompleta. */
    public int totalPages() {
        return Math.toIntExact((totalElements + size - 1) / size);
    }
}
