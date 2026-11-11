package ar.edu.um.programacion2.catalogo.sync;

import io.lettuce.core.KillArgs;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

/**
 * Un Redis real para los tests, con la versión y la ACL de la cátedra (Redis 7.4, contrato §14.1 y lo medido el 7/11
 * con {@code redis-cli}).
 * <p>
 * El usuario {@link #USERNAME} tiene lo mismo que la cuenta técnica: lectura en {@code catedra:sync:*}, lectura y
 * escritura en su namespace {@code alumnos:...:*}, y negados {@code PING}, {@code ECHO}, {@code INFO},
 * {@code CLIENT SETNAME} y {@code CLIENT SETINFO}, con {@code HELLO} permitido. Así, cualquier intento de escribir en
 * {@code catedra:sync:*} o de usar un comando negado falla en el test igual que fallaría contra la cátedra.
 * <p>
 * Los datos se cargan con el usuario {@code default}, que solo existe en el test: hace lo que hace la cátedra al publicar.
 * Uno solo por corrida, como {@code KafkaTestBroker}; Testcontainers lo apaga al terminar.
 */
public final class RedisTestServer {

    public static final String USERNAME = "alumno-de-prueba";
    public static final String PASSWORD = "clave-de-prueba-redis";

    /** Lo mismo que permite y niega la ACL de la cuenta técnica de la cátedra. */
    static final List<String> ACL_RULES = List.of(
        "on",
        ">" + PASSWORD,
        "resetkeys",
        "%R~catedra:sync:*",
        "%RW~alumnos:proyecto-de-prueba:*",
        "resetchannels",
        "-@all",
        "+@read",
        "+@write",
        "-@dangerous",
        "+hello",
        "-ping",
        "-echo",
        "-info",
        "-client|setname",
        "-client|setinfo"
    );

    private static final GenericContainer<?> REDIS = start();
    private static final RedisClient ADMIN_CLIENT = RedisClient.create(RedisURI.create(host(), port()));
    private static final StatefulRedisConnection<String, String> ADMIN = ADMIN_CLIENT.connect();

    private RedisTestServer() {}

    /** Un contenedor nuevo con la ACL de la cátedra. Los tests que lo pausan o lo apagan usan uno propio. */
    public static GenericContainer<?> newContainer() {
        GenericContainer<?> container = new GenericContainer<>("redis:7.4").withExposedPorts(6379);
        container.start();
        String[] command = new String[ACL_RULES.size() + 4];
        command[0] = "redis-cli";
        command[1] = "ACL";
        command[2] = "SETUSER";
        command[3] = USERNAME;
        for (int i = 0; i < ACL_RULES.size(); i++) {
            command[i + 4] = ACL_RULES.get(i);
        }
        exec(container, command);
        return container;
    }

    private static GenericContainer<?> start() {
        return newContainer();
    }

    public static String host() {
        return REDIS.getHost();
    }

    public static int port() {
        return REDIS.getMappedPort(6379);
    }

    /** Comandos con el usuario {@code default}: cargar datos, borrar y mirar el estado del servidor. */
    public static RedisCommands<String, String> admin() {
        return ADMIN.sync();
    }

    public static void flushAll() {
        admin().flushall();
        admin().aclLogReset();
    }

    /**
     * Cuántas veces se negó cada comando o clave desde el último {@link #flushAll()}: el campo {@code count} del
     * {@code ACL LOG}, por {@code object}. Redis agrupa en una entrada las negaciones iguales.
     */
    public static Map<String, Long> deniedCounts() {
        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> entry : admin().aclLog()) {
            counts.merge(String.valueOf(entry.get("object")), ((Number) entry.get("count")).longValue(), Long::sum);
        }
        return counts;
    }

    /** Cuántas conexiones tiene abiertas el usuario de la cuenta técnica. */
    public static long connectionsOfTechnicalUser() {
        return admin().clientList().lines().filter(line -> line.contains(" user=" + USERNAME + " ")).count();
    }

    /** Corta las conexiones del usuario de la cuenta técnica, como un corte de red visto desde el servidor. */
    public static void killConnectionsOfTechnicalUser() {
        admin().clientKill(KillArgs.Builder.user(USERNAME));
    }

    private static void exec(GenericContainer<?> container, String... command) {
        try {
            ExecResult result = container.execInContainer(command);
            if (result.getExitCode() != 0 || !result.getStdout().contains("OK")) {
                throw new IllegalStateException("No se pudo configurar el Redis de prueba: " + result.getStdout() + result.getStderr());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
