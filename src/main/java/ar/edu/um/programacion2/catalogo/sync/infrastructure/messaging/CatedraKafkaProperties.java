package ar.edu.um.programacion2.catalogo.sync.infrastructure.messaging;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración del consumidor de Kafka de la cátedra ({@code catedra.kafka.*}, ver ADR 0005).
 * <p>
 * Bootstrap, topic y grupo no tienen valor por defecto: si faltan o están vacíos, la aplicación no arranca. Hace falta
 * validarlos acá porque Kafka solo se queja al crear el consumidor, y con el listener apagado eso nunca pasa. Un broker
 * que no responde, en cambio, no frena el arranque: el servicio sigue sirviendo la copia local (DP-03).
 */
@Validated
@ConfigurationProperties(prefix = "catedra.kafka")
@Getter
@Setter
public class CatedraKafkaProperties {

    /** Brokers de la cátedra (contrato §3), solo accesibles por ZeroTier. */
    @NotBlank
    private String bootstrapServers;

    /** Topic de {@code CatalogUpdated}: {@code kafkaCatalogTopic} de {@code integration} (contrato §15.2). */
    @NotBlank
    private String catalogTopic;

    /** Grupo de este servicio. Es propio y no el de turnos: ver ADR 0005. */
    @NotBlank
    private String consumerGroup;

    /**
     * Si el listener arranca. Solo se apaga en {@code src/test}, para que cada contexto no se conecte a un broker que no
     * existe. No tiene variable de entorno en {@code application.yml}: en dev no puede quedar sordo sin querer.
     */
    private boolean listenerEnabled = true;

    /**
     * {@code max.poll.interval.ms}: cuánto puede tardar una entrega antes de que Kafka saque al consumidor del grupo.
     * <p>
     * Tiene que cubrir el peor caso, en el hilo del listener: 30 s de la primera barrera (Hikari) + 360 s de espera de la
     * exclusión + 30 s de leer la versión + 297 s del peor caso de CU-02 + 7 s de las esperas entre reintentos del
     * {@code DefaultErrorHandler} dan 724 s. Es una cota: cada reentrega pasa por {@code poll} y arranca un plazo nuevo,
     * así que en una ventana cabe una sola espera (4 s como mucho, 721 s), pero esa espera sí cuenta dentro de la ventana.
     * Si cambian esos tiempos o llega CU-04, hay que recalcularlo.
     */
    @NotNull
    @DurationMin(millis = 1)
    private Duration maxPollInterval = Duration.ofMinutes(15);

    /**
     * Cada cuánto se reintenta después de una falla de autorización. Sin esto, spring-kafka detiene el contenedor para
     * siempre: la aplicación queda viva y sorda (medido, ADR 0005).
     */
    @NotNull
    @DurationMin(millis = 1)
    private Duration authRetryInterval = Duration.ofMinutes(1);
}
