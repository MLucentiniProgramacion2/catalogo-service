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
    private Periodic periodic = new Periodic();

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
    public static class Periodic {

        /** DP-04: comprobar cada {@link #interval} la versión local contra la publicada en Redis (CU-03). */
        private boolean enabled = true;

        /**
         * Cada cuánto, contado desde que termina una pasada ({@code fixedDelay}); la primera, este tiempo después de
         * arrancar. Es también el {@code Retry-After} del 503 con la base vacía ({@code PeriodicCatalogRetryHint}).
         * <p>
         * 5 min: un aviso perdido se recupera en ese tiempo, y con la copia al día cada pasada es un {@code HMGET} y una
         * transacción corta. El mínimo de 1 min corta un error de tipeo: {@code 5} sin unidad son 5 <strong>ms</strong>.
         */
        @NotNull
        @DurationMin(minutes = 1)
        private Duration interval = Duration.ofMinutes(5);
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
         * Tiene que cubrir el peor caso de CU-03 con la exclusión tomada (issue #33): leer la versión local (30 s de
         * Hikari); la lectura previa (metadata, versión remota y cambios: 14 + 30 + 14 s) con sus 3 reintentos de hasta
         * 30 s y 7 s de esperas; el plazo del lote incremental (60 s) y la versión en curso al vencer (4 + 3 × 14 + 30 s);
         * y después CU-02 (297 s). Un intento contra Redis son 14 s: 5 de conexión y 3 por cada comando del saludo y del
         * pedido. Total: 618 s. Con 12 min sobran 102 s. Si se suben esos timeouts o el plazo, hay que recalcularlo: lo
         * hace {@code SyncWorstCase} en los tests.
         */
        @NotNull
        @DurationMin(millis = 1)
        private Duration waitTimeout = Duration.ofMinutes(12);
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
