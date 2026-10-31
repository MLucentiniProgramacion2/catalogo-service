package ar.edu.um.programacion2.catalogo.sync.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
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
}
