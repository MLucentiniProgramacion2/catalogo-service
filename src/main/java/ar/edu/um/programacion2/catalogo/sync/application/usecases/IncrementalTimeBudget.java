package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import java.time.Duration;
import java.util.Objects;

/**
 * Cuánto puede durar un lote de CU-04 antes de dejar de empezar versiones y reintentos nuevos (DP-08 y DP-09). Al
 * vencer, se conservan las versiones aplicadas y CU-03 sigue con una sincronización completa.
 * <p>
 * Existe para que el peor caso de CU-03 tenga una cota: contando solo reintentos, cada versión puede tardar sus
 * timeouts enteros sin llegar a fallar, y el total crece con la cantidad de versiones. Se mide con el {@code Clock}
 * inyectado, así que los tests lo vencen sin dormir.
 */
public record IncrementalTimeBudget(Duration value) {
    public IncrementalTimeBudget {
        Objects.requireNonNull(value, "Falta el plazo del lote incremental");
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("El plazo del lote incremental tiene que ser positivo: " + value);
        }
    }
}
