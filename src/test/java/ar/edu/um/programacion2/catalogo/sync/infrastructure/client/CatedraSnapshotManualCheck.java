package ar.edu.um.programacion2.catalogo.sync.infrastructure.client;

import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogSnapshot;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SnapshotRetrievalException;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.opentest4j.AssertionFailedError;

/**
 * Comprobación manual contra la cátedra real. <strong>Nunca corre en el CI</strong>: el nombre no coincide con los
 * patrones de surefire ({@code *Test}) ni de failsafe ({@code *IT*}) y, además, exige {@code -Dcatedra.manual=true}.
 * <p>
 * Con ZeroTier conectado ({@code sudo zerotier-cli listnetworks}) y {@code CATEDRA_API_URL} y {@code CATEDRA_JWT} en
 * el {@code .env} (una variable de entorno con el mismo nombre tiene prioridad):
 *
 * <pre>./mvnw test -Dtest=CatedraSnapshotManualCheck -Dcatedra.manual=true</pre>
 *
 * Imprime solo la versión y la cantidad de elementos de cada colección. Si falla, una línea con el tipo de error y el
 * status/code, sin stack trace. Nunca imprime el token ni la URL.
 */
@EnabledIfSystemProperty(named = "catedra.manual", matches = "true")
class CatedraSnapshotManualCheck {

    @Test
    void obtieneElSnapshotDeLaCatedraReal() throws IOException {
        Properties dotEnv = readDotEnv();
        CatedraApiProperties properties = new CatedraApiProperties();
        properties.setUrl(URI.create(required("CATEDRA_API_URL", dotEnv)));
        properties.setToken(required("CATEDRA_JWT", dotEnv));
        CatedraSnapshotClient client = new CatedraSnapshotClient(new CatedraClientConfiguration().catedraRestClient(properties));

        CatalogSnapshot snapshot;
        try {
            snapshot = client.fetchSnapshot();
        } catch (SnapshotRetrievalException e) {
            throw oneLineFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        System.out.println(
            "snapshotVersion=" +
            snapshot.snapshotVersion() +
            " categorías=" +
            snapshot.professionalCategories().size() +
            " profesionales=" +
            snapshot.professionals().size() +
            " horarios=" +
            snapshot.weeklySchedules().size()
        );
    }

    private static Properties readDotEnv() throws IOException {
        Properties dotEnv = new Properties();
        Path path = Path.of(".env");
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                dotEnv.load(reader);
            }
        }
        return dotEnv;
    }

    private static String required(String name, Properties dotEnv) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            value = dotEnv.getProperty(name);
        }
        if (value == null || value.isBlank()) {
            throw oneLineFailure("Falta " + name + " en el .env o en el entorno");
        }
        return value.strip();
    }

    /** Falla con un mensaje de una línea: sin stack trace y sin causa, que podría llevar la URL. */
    private static AssertionFailedError oneLineFailure(String message) {
        System.out.println("FALLÓ " + message);
        AssertionFailedError failure = new AssertionFailedError(message);
        failure.setStackTrace(new StackTraceElement[0]);
        return failure;
    }
}
