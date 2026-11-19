package ar.edu.um.programacion2.catalogo.security;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * Control de {@link ServiceTokenAuthorizationIT}: su endpoint de prueba bajo {@code /api/service/**} no queda en los
 * demás contextos. Usa la configuración de la mayoría de los IT, así que comparte su contexto.
 */
@IntegrationTest
class ServiceTestEndpointScanIT {

    @Autowired
    private ApplicationContext context;

    @Test
    void elEndpointDePruebaDeServicioNoEstaEnUnContextoQueNoLoImporta() {
        assertThat(context.getBeanNamesForType(ServiceTokenAuthorizationIT.EndpointDeServicioDePrueba.class)).isEmpty();
    }
}
