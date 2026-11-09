package ar.edu.um.programacion2.catalogo.catalog.domain.ports.out;

import java.time.Duration;
import java.util.Optional;

/**
 * Cuándo conviene volver a consultar si la copia local todavía no tiene versión (DP-03): el {@code Retry-After} del 503
 * de CU-05, CU-06 y CU-07.
 * <p>
 * Quién vuelve a intentar armar la copia es la sincronización, pero {@code catalog} no depende de {@code sync} (ArchUnit,
 * {@code catalogNoDependeDeSync}): igual que con {@link LocalCatalogVersionProvider}, el puerto es de {@code catalog} y
 * lo implementa un adaptador de {@code sync}.
 */
public interface CatalogRetryHint {
    /** Vacío si nada vuelve a intentar solo: un plazo prometería algo que no se cumple. */
    Optional<Duration> retryAfter();
}
