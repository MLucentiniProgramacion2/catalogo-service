package ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.repository;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Registra los repositorios de Spring Data de la sincronización.
 * <p>
 * El {@code @EnableJpaRepositories} de {@code DatabaseConfiguration} (JHipster) solo busca en
 * {@code catalogo.repository}, y al estar declarado, Spring Boot ya no busca en el resto. Cada feature declara el suyo,
 * limitado a su paquete, sin tocar la configuración generada.
 */
@Configuration
@EnableJpaRepositories(basePackageClasses = SyncJpaRepositoriesConfiguration.class)
public class SyncJpaRepositoriesConfiguration {}
