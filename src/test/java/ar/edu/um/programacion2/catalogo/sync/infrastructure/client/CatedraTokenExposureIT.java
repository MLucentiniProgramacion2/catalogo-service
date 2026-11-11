package ar.edu.um.programacion2.catalogo.sync.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.security.AuthoritiesConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Los endpoints de actuator que muestran la configuración no exponen el JWT técnico de la cátedra, ni siquiera a un
 * administrador.
 * <p>
 * El {@code application.yml} de test tapa al principal, que es el que expone {@code env} y {@code configprops} bajo
 * {@code /management}: acá se repite esa configuración. Sin ella los endpoints darían 404 y el test pasaría sin probar
 * nada; por eso también se exige un 200 y que la propiedad aparezca (enmascarada).
 */
@AutoConfigureMockMvc
@WithMockUser(authorities = AuthoritiesConstants.ADMIN)
@IntegrationTest
@TestPropertySource(
    properties = { "management.endpoints.web.base-path=/management", "management.endpoints.web.exposure.include=env,configprops" }
)
class CatedraTokenExposureIT {

    @Autowired
    private MockMvc mvc;

    @Value("${catedra.api.token}")
    private String token;

    @Test
    void envMuestraLaPropiedadSinElToken() throws Exception {
        String body = mvc.perform(get("/management/env")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("catedra.api.token").doesNotContain(token);
    }

    @Test
    void configpropsMuestraLaPropiedadSinElToken() throws Exception {
        String body = mvc.perform(get("/management/configprops")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("catedra.api").contains("\"token\"").doesNotContain(token);
    }
}
