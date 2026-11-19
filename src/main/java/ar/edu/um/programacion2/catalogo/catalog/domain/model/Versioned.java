package ar.edu.um.programacion2.catalogo.catalog.domain.model;

import java.util.Objects;

/**
 * Un resultado leído de la copia local junto con la versión del catálogo a la que corresponde (CU-05, CU-06 y CU-07:
 * "la versión local con la que se resolvió").
 * <p>
 * La versión y el contenido salen de la misma transacción: los arma el caso de uso, que es quien la abre.
 *
 * @param version versión del catálogo aplicada en la copia local
 * @param content lo que se leyó con esa versión
 * @param <T> el tipo del contenido: una lista de categorías, de profesionales o una agenda
 */
public record Versioned<T>(long version, T content) {
    public Versioned {
        Objects.requireNonNull(content, "Falta el contenido de la versión " + version);
    }
}
