package ar.edu.um.programacion2.catalogo.sync.infrastructure.config;

import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRetryHint;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Adaptador de salida de {@link CatalogRetryHint} (puerto de {@code catalog}): con la base vacía, la próxima comprobación
 * periódica (DP-04) intenta el snapshot, así que el {@code Retry-After} es su intervalo. Es una cota y no una promesa: la
 * próxima pasada puede caer antes, y el snapshot puede tardar o volver a fallar.
 * <p>
 * Con la periódica apagada no hay plazo: el snapshot llegaría solo con un aviso de Kafka o una sincronización manual,
 * que no tienen fecha.
 */
@Component
@RequiredArgsConstructor
public class PeriodicCatalogRetryHint implements CatalogRetryHint {

    private final SyncProperties syncProperties;

    @Override
    public Optional<Duration> retryAfter() {
        SyncProperties.Periodic periodic = syncProperties.getPeriodic();
        return periodic.isEnabled() ? Optional.of(periodic.getInterval()) : Optional.empty();
    }
}
