package ar.edu.um.programacion2.catalogo.sync.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración de la sincronización ({@code sync.*}).
 * <p>
 * Prefijo propio y no {@code application.*}: {@code ApplicationProperties} de JHipster rechaza las propiedades que no
 * declara ({@code ignoreUnknownFields = false}). Tampoco {@code catedra.*}: esto no es configuración de la cátedra.
 */
@Validated
@ConfigurationProperties(prefix = "sync")
@Getter
@Setter
public class SyncProperties {

    @Valid
    @NotNull
    private Startup startup = new Startup();

    @Valid
    @NotNull
    private Retry retry = new Retry();

    @Valid
    @NotNull
    private Lock lock = new Lock();

    @Valid
    @NotNull
    private Incremental incremental = new Incremental();

    @Getter
    @Setter
    public static class Startup {

        /** CU-01: al arrancar, si no hay versión local, sincroniza con un snapshot. */
        private boolean enabled = true;
    }

    @Getter
    @Setter
    public static class Retry {

        /**
         * Esperas antes de cada reintento (DP-08). La cantidad de reintentos es la cantidad de esperas: con
         * {@code 1s,2s,4s} se intenta hasta 4 veces.
         */
        @NotNull
        private List<@NotNull Duration> delays = new ArrayList<>(
            List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4))
        );
    }

    @Getter
    @Setter
    public static class Lock {

        /**
         * Cuánto espera un disparador a que termine otra sincronización (DP-09). Si se supera, termina sin cambios y
         * registra {@code LOCK_TIMEOUT}.
         * <p>
         * Tiene que cubrir el peor caso de CU-02: 4 intentos de 65 s (5 s de conexión y 30 s de respuesta de la cátedra,
         * más 30 s de espera de una conexión de Hikari), 7 s entre reintentos y otros 30 s para registrar el error dan
         * 297 s. Con 5 min sobrarían 3 s sin contar el reemplazo mismo; por eso 6 min. Si se suben esos timeouts, hay que
         * recalcularlo.
         */
        @NotNull
        @DurationMin(millis = 1)
        private Duration waitTimeout = Duration.ofMinutes(6);
    }

    @Getter
    @Setter
    public static class Incremental {

        /**
         * Plazo de un lote de CU-04: después de esto no empieza otra versión ni otro reintento, se conservan las aplicadas
         * y CU-03 sigue con un snapshot. El caso real (33 versiones de a un profesional) tarda unos 10 s por ZeroTier,
         * así que 60 s dejan margen. Entra en la cuenta de la espera de la exclusión: si se sube, hay que recalcularla.
         */
        @NotNull
        @DurationMin(millis = 1)
        private Duration timeBudget = Duration.ofSeconds(60);
    }
}
