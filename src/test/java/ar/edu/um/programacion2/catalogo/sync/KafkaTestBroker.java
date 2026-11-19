package ar.edu.um.programacion2.catalogo.sync;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Un broker real para los tests, que hace lo que hace la cátedra: crear el topic (una partición y una réplica, como el
 * real) y publicar. También lee los offsets confirmados con {@link Admin}, como los ve el broker.
 * <p>
 * Uno solo por corrida, como la base de {@code DatabaseTestcontainer}. Testcontainers lo apaga al terminar.
 */
public final class KafkaTestBroker {

    private static final KafkaContainer KAFKA = start();
    private static final Admin ADMIN = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()));
    private static final KafkaProducer<String, byte[]> PRODUCER = new KafkaProducer<>(
        Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()),
        new StringSerializer(),
        new ByteArraySerializer()
    );

    private KafkaTestBroker() {}

    private static KafkaContainer start() {
        KafkaContainer container = new KafkaContainer("apache/kafka-native:4.1.0");
        container.start();
        return container;
    }

    public static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    /** Crea un topic con un nombre que no se repite y lo devuelve. */
    public static String createTopic() {
        String topic = "catedra.catalog.test-" + UUID.randomUUID();
        await(() -> ADMIN.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get());
        return topic;
    }

    /** Un {@code CatalogUpdated} como los del topic de la cátedra, con la versión como clave (contrato §15.1). */
    public static void publishCatalogUpdated(String topic, String eventId, long version) {
        publish(topic, Long.toString(version), CatalogUpdatedJson.of(eventId, version).getBytes(StandardCharsets.UTF_8));
    }

    /** Publica el valor tal cual: {@code null} es un tombstone. */
    public static void publish(String topic, String key, byte[] value) {
        await(() -> PRODUCER.send(new ProducerRecord<>(topic, key, value)).get());
    }

    /** Offset confirmado por el grupo en la única partición, o {@code null} si todavía no confirmó nada. */
    public static Long committedOffset(String group, String topic) {
        Map<TopicPartition, OffsetAndMetadata> offsets = await(() -> ADMIN.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get());
        OffsetAndMetadata offset = offsets.get(new TopicPartition(topic, 0));
        return offset == null ? null : offset.offset();
    }

    public static Set<String> topics() {
        return await(() -> ADMIN.listTopics().names().get());
    }

    public static List<String> groups() {
        return await(() -> ADMIN.listGroups().all().get()).stream().map(GroupListing::groupId).toList();
    }

    private interface BrokerCall<T> {
        T run() throws InterruptedException, ExecutionException;
    }

    private static <T> T await(BrokerCall<T> call) {
        try {
            return call.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Falló el pedido al broker de prueba", e);
        }
    }
}
