package ar.edu.um.programacion2.catalogo.sync.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ar.edu.um.programacion2.catalogo.sync.application.service.SyncService;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.config.SyncConfiguration;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.config.SyncProperties;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import ar.edu.um.programacion2.catalogo.sync.SyncWorstCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOffExecution;

/**
 * Sin bootstrap, topic o grupo la aplicación no arranca, y el consumidor queda como pide el ADR 0005. Levanta solo la
 * configuración, sin broker ni base.
 */
class CatedraKafkaPropertiesTest {

    private static final String BOOTSTRAP = "catedra.kafka.bootstrap-servers=broker.test:9092";
    private static final String TOPIC = "catedra.kafka.catalog-topic=catedra.catalog.de-prueba";
    private static final String GRUPO = "catedra.kafka.consumer-group=grupo-de-prueba-catalogo";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(CatedraKafkaConfiguration.class, SyncConfiguration.class)
        .withBean(SyncService.class, () -> mock(SyncService.class));

    @Test
    void conLasTresVariablesArrancaConElListenerPrendidoPorDefecto() {
        runner
            .withPropertyValues(BOOTSTRAP, TOPIC, GRUPO)
            .run(context -> {
                assertThat(context).hasNotFailed();
                CatedraKafkaProperties properties = context.getBean(CatedraKafkaProperties.class);
                assertThat(properties.isListenerEnabled()).isTrue();
                assertThat(properties.getCatalogTopic()).isEqualTo("catedra.catalog.de-prueba");
            });
    }

    @ParameterizedTest
    @MethodSource("configuracionesIncompletas")
    void sinAlgunaVariableNoArranca(String nombre, String[] propiedades) {
        runner
            .withPropertyValues(propiedades)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(nombre);
            });
    }

    static Stream<Arguments> configuracionesIncompletas() {
        return Stream.of(
            Arguments.of("bootstrapServers", new String[] { TOPIC, GRUPO }),
            Arguments.of("catalogTopic", new String[] { BOOTSTRAP, GRUPO }),
            Arguments.of("consumerGroup", new String[] { BOOTSTRAP, TOPIC }),
            Arguments.of("bootstrapServers", new String[] { "catedra.kafka.bootstrap-servers=", TOPIC, GRUPO }),
            Arguments.of("catalogTopic", new String[] { BOOTSTRAP, "catedra.kafka.catalog-topic=", GRUPO }),
            Arguments.of("consumerGroup", new String[] { BOOTSTRAP, TOPIC, "catedra.kafka.consumer-group=" })
        );
    }

    @Test
    void conElListenerApagadoTampocoArrancaSinVariables() {
        // Es lo que justifica validarlas acá: sin consumidor, Kafka nunca se quejaría.
        runner.withPropertyValues("catedra.kafka.listener-enabled=false").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void elConsumidorQuedaComoPideElAdr() {
        runner
            .withPropertyValues(BOOTSTRAP, TOPIC, GRUPO)
            .run(context -> {
                assertThat(context.getBean(ConsumerFactory.class).getConfigurationProperties())
                    .containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker.test:9092")
                    .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "grupo-de-prueba-catalogo")
                    .containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                    .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                    .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                    .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                    .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1)
                    .containsEntry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 1_500_000)
                    .containsEntry(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
            });
    }

    @Test
    void elContenedorConfirmaPorMensajeReintentaLaAutorizacionYUsaElManejadorDeErrores() {
        runner
            .withPropertyValues(BOOTSTRAP, TOPIC, GRUPO)
            .run(context -> {
                ConcurrentMessageListenerContainer<?, ?> contenedor = contenedor(context.getBean(ConcurrentKafkaListenerContainerFactory.class));

                assertThat(contenedor.getContainerProperties().getAckMode()).isEqualTo(AckMode.RECORD);
                assertThat(contenedor.getContainerProperties().getAuthExceptionRetryInterval()).isEqualTo(Duration.ofMinutes(1));
                assertThat(contenedor.getConcurrency()).isEqualTo(1);
                assertThat(contenedor.getCommonErrorHandler()).isInstanceOf(DefaultErrorHandler.class);
            });
    }

    @Test
    void elIntervaloDePollCubreElPeorCasoDeUnaEntrega() {
        // Con los valores reales, no con los de src/test: primera barrera + espera de la exclusión + CU-03 + esperas del
        // manejador. Los mismos números que CatedraKafkaProperties y que el ADR 0005.
        SyncWorstCase peor = new SyncWorstCase();
        assertThat(peor.retryDelays()).isEqualTo(Duration.ofSeconds(7));
        assertThat(peor.lockWait()).isEqualTo(Duration.ofMinutes(12));

        assertThat(peor.kafkaDelivery()).isEqualTo(Duration.ofSeconds(1375));
        assertThat(new CatedraKafkaProperties().getMaxPollInterval()).isEqualTo(Duration.ofMinutes(25)).isGreaterThan(peor.kafkaDelivery());
    }

    @Test
    void lasEsperasDelManejadorSonLasDeLaConfiguracionYDespuesTermina() {
        BackOffExecution esperas = CatedraKafkaConfiguration.backOff(new SyncProperties().getRetry().getDelays()).start();

        assertThat(List.of(esperas.nextBackOff(), esperas.nextBackOff(), esperas.nextBackOff())).containsExactly(1000L, 2000L, 4000L);
        assertThat(esperas.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    void cadaAvisoEmpiezaLaCuentaDeEsperasDeNuevo() {
        var backOff = CatedraKafkaConfiguration.backOff(List.of(Duration.ofSeconds(1)));

        assertThat(backOff.start().nextBackOff()).isEqualTo(1000L);
        assertThat(backOff.start().nextBackOff()).isEqualTo(1000L);
    }

    @Test
    void elListenerApagadoNoArrancaAunqueSeReinicieElContexto() {
        // Es lo que hace Spring 7 con los contextos en caché de los tests: los pausa y los reanuda.
        runner
            .withUserConfiguration(CatalogUpdatedListener.class)
            .withPropertyValues(
                "catedra.kafka.bootstrap-servers=localhost:1",
                GRUPO,
                "catedra.kafka.catalog-topic=topic-de-prueba",
                "catedra.kafka.listener-enabled=false"
            )
            .run(context -> {
                KafkaListenerEndpointRegistry registro = context.getBean(KafkaListenerEndpointRegistry.class);
                assertThat(registro.getListenerContainers()).hasSize(1);

                context.getSourceApplicationContext().stop();
                context.getSourceApplicationContext().start();

                assertThat(registro.getListenerContainers()).noneMatch(MessageListenerContainer::isRunning);
            });
    }

    private static ConcurrentMessageListenerContainer<?, ?> contenedor(ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
        return factory.createContainer("topic-de-prueba");
    }
}
