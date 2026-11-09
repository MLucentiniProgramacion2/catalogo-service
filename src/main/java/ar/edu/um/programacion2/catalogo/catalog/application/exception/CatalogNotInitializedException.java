package ar.edu.um.programacion2.catalogo.catalog.application.exception;

import java.time.Duration;
import java.util.Optional;

/**
 * La copia local todavía no tiene una versión aplicada: nunca se confirmó un snapshot (DP-03, flujo 3a de CU-05, CU-06
 * y CU-07). Es lo que pasa, por ejemplo, si el snapshot del arranque falla: el servicio queda levantado y sin datos.
 * <p>
 * El criterio es la versión y no "cero filas": un catálogo aplicado puede no tener categorías, y eso es una respuesta
 * válida.
 * <p>
 * El adaptador web la traduce a {@code 503} con un {@code code} estable y, si lo hay, con el {@code Retry-After} que
 * trae ({@link #getRetryAfter()}). El mensaje llega al cliente como {@code detail}: no puede llevar datos internos.
 */
public class CatalogNotInitializedException extends RuntimeException {

    private final transient Duration retryAfter;

    /** @param retryAfter cuándo conviene volver a consultar; vacío si nada vuelve a intentar solo. */
    public CatalogNotInitializedException(Optional<Duration> retryAfter) {
        super("El catálogo todavía no está disponible: no se aplicó ninguna versión");
        this.retryAfter = retryAfter.orElse(null);
    }

    public Optional<Duration> getRetryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
