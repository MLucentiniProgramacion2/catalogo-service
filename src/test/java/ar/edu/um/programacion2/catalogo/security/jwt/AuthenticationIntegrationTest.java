package ar.edu.um.programacion2.catalogo.security.jwt;

import ar.edu.um.programacion2.catalogo.config.SecurityConfiguration;
import ar.edu.um.programacion2.catalogo.config.SecurityJwtConfiguration;
import ar.edu.um.programacion2.catalogo.config.WebConfigurer;
import ar.edu.um.programacion2.catalogo.management.SecurityMetersService;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import tech.jhipster.config.JHipsterProperties;

/**
 * Contexto chico para probar la validación y la autorización del JWT, sin base de datos.
 * <p>
 * {@code @SpringBootTest} con clases fijas no aplica la autoconfiguración de Spring Boot. Se importa a mano la del
 * resource server: es la que arma, con {@code spring.security.oauth2.resourceserver.jwt.*} de {@code application.yml},
 * el conversor que lee los roles del claim {@code auth}. Sin ella, el token pasa la validación pero llega sin roles, y
 * cualquier regla por rol responde 403 aunque en la aplicación real pase.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ImportAutoConfiguration(OAuth2ResourceServerAutoConfiguration.class)
@SpringBootTest(
    classes = {
        JHipsterProperties.class,
        WebConfigurer.class,
        SecurityConfiguration.class,
        SecurityJwtConfiguration.class,
        SecurityMetersService.class,
        JwtAuthenticationTestUtils.class,
    }
)
public @interface AuthenticationIntegrationTest {}
