package ar.edu.um.programacion2.catalogo.sync.infrastructure.redis;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración del acceso de solo lectura al Redis de la cátedra ({@code catedra.redis.*}, ver ADR 0006): los
 * {@code redisHost}, {@code redisPort}, {@code redisUsername} y {@code redisPassword} de {@code integration}
 * (contrato §14.1).
 * <p>
 * Va en Java y no con {@code spring.data.redis.*}: el {@code application.yml} de {@code src/test} tapa al principal, y
 * además el proyecto no tiene la autoconfiguración de Redis de Spring Boot.
 * <p>
 * Los cuatro datos de acceso no tienen valor por defecto: si falta alguno, la aplicación no arranca. Un Redis que no
 * responde, en cambio, no frena el arranque: la conexión se abre recién con la primera lectura. Es una clase y no un
 * record porque el {@code toString()} de un record imprimiría la contraseña.
 */
@Validated
@ConfigurationProperties(prefix = "catedra.redis")
@Getter
@Setter
public class CatedraRedisProperties {

    /** Host de Redis (contrato §3), solo accesible por ZeroTier. */
    @NotBlank
    private String host;

    @NotNull
    @Min(1)
    @Max(65535)
    private Integer port;

    /** Usuario de la ACL de la cuenta técnica (contrato §14.1). */
    @NotBlank
    private String username;

    /** Contraseña de la ACL. Nunca se loguea ni se muestra. */
    @NotBlank
    private String password;

    /**
     * Tiempo máximo para abrir la conexión TCP. Es el mismo camino por ZeroTier que el de la API REST, así que se usa el
     * mismo valor que {@code catedra.api.connect-timeout}: unas 17 veces los 290 ms del peor viaje medido.
     */
    @NotNull
    @DurationMin(millis = 1)
    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * Tiempo máximo de cada comando, incluidos los del saludo al conectar ({@code HELLO} y {@code CLIENT SETINFO}). Las
     * respuestas pesan unos pocos KB, así que es un viaje: 3 s son unas 10 veces los 290 ms del peor viaje medido por
     * ZeroTier. Si se sube, hay que recalcular la espera de la exclusión y {@code max.poll.interval.ms}.
     */
    @NotNull
    @DurationMin(millis = 1)
    private Duration commandTimeout = Duration.ofSeconds(3);

    @Override
    public String toString() {
        return (
            "CatedraRedisProperties{host=" +
            host +
            ", port=" +
            port +
            ", username=[oculto], password=[oculta], connectTimeout=" +
            connectTimeout +
            ", commandTimeout=" +
            commandTimeout +
            "}"
        );
    }
}
