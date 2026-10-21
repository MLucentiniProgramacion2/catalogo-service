package ar.edu.um.programacion2.catalogo;

import ar.edu.um.programacion2.catalogo.config.AsyncSyncConfiguration;
import ar.edu.um.programacion2.catalogo.config.DatabaseTestcontainer;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Base composite annotation for integration tests.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
    classes = {
        CatalogoServiceApp.class,
        AsyncSyncConfiguration.class,
        ar.edu.um.programacion2.catalogo.config.JacksonHibernateConfiguration.class,
        DatabaseTestcontainer.class,
    }
)
public @interface IntegrationTest {}
