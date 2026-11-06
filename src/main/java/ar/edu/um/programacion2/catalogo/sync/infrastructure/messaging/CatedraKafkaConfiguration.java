package ar.edu.um.programacion2.catalogo.sync.infrastructure.messaging;

import ar.edu.um.programacion2.catalogo.sync.application.service.SyncService;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.config.SyncProperties;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

/**
 * Consumidor de Kafka de la cátedra, armado en Java a partir de {@code catedra.kafka.*} (ADR 0005).
 * <p>
 * <strong>En Java y no con {@code spring.kafka.*}.</strong> El {@code config/application.yml} de {@code src/test} tapa al
 * principal: lo que estuviera en el YAML no lo ejercitarían los tests. Además, así ninguna propiedad {@code spring.kafka.*}
 * cambia este consumidor. Estos beans reemplazan a los de la autoconfiguración de Spring Boot, que se retira si existe un
 * {@link ConsumerFactory} o un bean llamado {@code kafkaListenerContainerFactory}.
 * <p>
 * Cada valor tiene su motivo, medido contra un broker real:
 * <ul>
 * <li>{@code String} en la clave y el valor: el mensaje se parsea en el adaptador, y uno roto llega al listener. Con
 * {@code JacksonJsonDeserializer} solo, un mensaje roto traba la partición y no se llega a procesar nada más.</li>
 * <li>{@code earliest}: el aviso no trae datos, así que releer el historial cuesta un snapshot y el resto "al día".</li>
 * <li>{@code max.poll.records=1}: el plazo de {@code max.poll.interval.ms} se cuenta por lote. Con lotes, varios avisos
 * largos lo superan y se repiten.</li>
 * <li>{@code allow.auto.create.topics=false}: el topic es de la cátedra, y sin esto el propio consumidor lo crearía si
 * el nombre estuviera mal escrito.</li>
 * <li>{@link AckMode#RECORD}: el contenedor confirma cuando el listener vuelve bien, después de persistir, y todos los
 * flujos esperados de CU-03 vuelven bien. Un {@code Acknowledgment} manual sumaría una rama para olvidarse en cada
 * flujo.</li>
 * <li>Un solo hilo: DP-09 ya serializa las sincronizaciones, y el topic tiene una partición.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(CatedraKafkaProperties.class)
public class CatedraKafkaConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(CatedraKafkaConfiguration.class);

    @Bean
    public ConsumerFactory<String, String> catalogConsumerFactory(CatedraKafkaProperties properties) {
        return new DefaultKafkaConsumerFactory<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                properties.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                properties.getConsumerGroup(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false,
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
                1,
                ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,
                Math.toIntExact(properties.getMaxPollInterval().toMillis()),
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG,
                false
            )
        );
    }

    /**
     * Para lo inesperado, como la base caída en una barrera. Las fallas esperadas no llegan acá: vuelven como resultado.
     * Los reintentos son los de DP-08 ({@code sync.retry.delays}), cada uno queda en el log (§18.4), y al agotarse actúa
     * {@link CatalogEventRecoverer}. El manejador por defecto haría 10 entregas seguidas, sin ninguna espera.
     */
    @Bean
    public CommonErrorHandler catalogEventErrorHandler(SyncService syncService, SyncProperties syncProperties) {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
            new CatalogEventRecoverer(syncService),
            backOff(syncProperties.getRetry().getDelays())
        );
        errorHandler.setRetryListeners((message, failure, attempt) ->
            LOG.warn(
                "Aviso de catálogo: falló el intento {} (partición {}, offset {}): {}",
                attempt,
                message.partition(),
                message.offset(),
                failure.getCause()
            )
        );
        return errorHandler;
    }

    /** El bean se llama así para que {@code @KafkaListener} lo use sin nombrarlo y la autoconfiguración no cree otro. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
        ConsumerFactory<String, String> consumerFactory,
        CommonErrorHandler errorHandler,
        CatedraKafkaProperties properties
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(1);
        factory.setAutoStartup(properties.isListenerEnabled());
        factory.getContainerProperties().setAckMode(AckMode.RECORD);
        factory.getContainerProperties().setAuthExceptionRetryInterval(properties.getAuthRetryInterval());
        if (!properties.isListenerEnabled()) {
            LOG.warn("El listener de CatalogUpdated está apagado (catedra.kafka.listener-enabled=false)");
        }
        return factory;
    }

    /** Una espera por cada valor de la lista, y después se termina. Sin {@code ExponentialBackOff}, para no tener otra fuente de DP-08. */
    static BackOff backOff(List<Duration> delays) {
        return () -> {
            Iterator<Duration> remaining = delays.iterator();
            return () -> remaining.hasNext() ? remaining.next().toMillis() : BackOffExecution.STOP;
        };
    }
}
