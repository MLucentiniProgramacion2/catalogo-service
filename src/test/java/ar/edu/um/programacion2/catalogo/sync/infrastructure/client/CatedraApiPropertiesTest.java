package ar.edu.um.programacion2.catalogo.sync.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

/**
 * Sin URL o sin token de la cátedra la aplicación no arranca (como con {@code JWT_BASE64_SECRET}, ADR 0003), y el token
 * no se imprime. Levanta solo {@link CatedraClientConfiguration}, sin base de datos.
 */
class CatedraApiPropertiesTest {

    private static final String URL = "catedra.api.url=http://catedra.test:8080";
    private static final String TOKEN = "catedra.api.token=token-de-prueba-1a2b3c";

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(CatedraClientConfiguration.class);

    @Test
    void conUrlYTokenArrancaConLosTimeoutsPorDefecto() {
        runner
            .withPropertyValues(URL, TOKEN)
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(RestClient.class);
                CatedraApiProperties properties = context.getBean(CatedraApiProperties.class);
                assertThat(properties.getUrl()).isEqualTo(URI.create("http://catedra.test:8080"));
                assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
                assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(30));
            });
    }

    @Test
    void tomaLosTimeoutsConfigurados() {
        runner
            .withPropertyValues(URL, TOKEN, "catedra.api.connect-timeout=2s", "catedra.api.read-timeout=1m")
            .run(context -> {
                CatedraApiProperties properties = context.getBean(CatedraApiProperties.class);
                assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
                assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofMinutes(1));
            });
    }

    @Test
    void sinUrlNoArranca() {
        runner.withPropertyValues(TOKEN).run(context -> assertFailsOn(context.getStartupFailure(), "url"));
    }

    @Test
    void conUrlVaciaNoArranca() {
        runner.withPropertyValues("catedra.api.url=", TOKEN).run(context -> assertFailsOn(context.getStartupFailure(), "url"));
    }

    @Test
    void sinTokenNoArranca() {
        runner.withPropertyValues(URL).run(context -> assertFailsOn(context.getStartupFailure(), "token"));
    }

    @Test
    void conTokenEnBlancoNoArranca() {
        runner.withPropertyValues(URL, "catedra.api.token=   ").run(context -> assertFailsOn(context.getStartupFailure(), "token"));
    }

    /** Es lo que hace {@code application.yml} cuando falta {@code CATEDRA_JWT}: {@code ${CATEDRA_JWT:}}. */
    @Test
    void conLaVariableDeEntornoAusenteNoArranca() {
        runner
            .withPropertyValues(URL, "catedra.api.token=${CATEDRA_JWT_QUE_NO_EXISTE:}")
            .run(context -> assertFailsOn(context.getStartupFailure(), "token"));
    }

    /** Por qué el {@code :} vacío: sin él, el placeholder sin resolver quedaría como texto y pasaría la validación. */
    @Test
    void sinValorVacioElPlaceholderSinResolverQuedaComoTextoLiteral() {
        runner
            .withPropertyValues(URL, "catedra.api.token=${CATEDRA_JWT_QUE_NO_EXISTE}")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(CatedraApiProperties.class).getToken()).isEqualTo("${CATEDRA_JWT_QUE_NO_EXISTE}");
            });
    }

    @Test
    void toStringNoMuestraElToken() {
        CatedraApiProperties properties = new CatedraApiProperties();
        properties.setUrl(URI.create("http://catedra.test:8080"));
        properties.setToken("token-de-prueba-1a2b3c");

        assertThat(properties.toString()).doesNotContain("token-de-prueba-1a2b3c").contains("[oculto]").contains("catedra.test");
    }

    private static void assertFailsOn(Throwable failure, String field) {
        assertThat(failure).as("la aplicación tenía que no arrancar").isNotNull();
        assertThat(failure).hasStackTraceContaining("catedra.api").hasStackTraceContaining(field);
    }
}
