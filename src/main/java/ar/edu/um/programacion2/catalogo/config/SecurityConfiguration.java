package ar.edu.um.programacion2.catalogo.config;

import static org.springframework.security.config.Customizer.withDefaults;

import ar.edu.um.programacion2.catalogo.security.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import tech.jhipster.config.JHipsterProperties;

@Configuration
@EnableMethodSecurity(securedEnabled = true)
public class SecurityConfiguration {

    private final JHipsterProperties jHipsterProperties;

    public SecurityConfiguration(JHipsterProperties jHipsterProperties) {
        this.jHipsterProperties = jHipsterProperties;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) {
        http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(authz ->
                // Spring usa la primera regla cuyo patrón coincide con el pedido y no mira las siguientes: las rutas
                // específicas van antes que /api/**. Si /api/** estuviera primero, /api/admin/** quedaría abierto a
                // cualquier usuario. La regla del endpoint de agenda de CU-07 (ROLE_SERVICE con aud=catalogo-service e
                // iss=turnos-service, DP-06) también va antes de /api/**: si no, /api/** le daría 403 al token de servicio.
                //
                // /api/** es la regla de los endpoints de usuario (CU-05, CU-06, CU-09). Es una lista de permitidos:
                // pide ROLE_USER o ROLE_ADMIN, así que el token técnico de servicio (que solo trae ROLE_SERVICE) y un
                // token sin roles reciben 403 (DP-06). ROLE_ADMIN está porque en turnos un admin puede quedar solo con
                // ese rol (lo permite /api/admin/users).
                // prettier-ignore
                authz
                    .requestMatchers("/api/admin/**").hasAuthority(AuthoritiesConstants.ADMIN)
                    .requestMatchers("/api/**").hasAnyAuthority(AuthoritiesConstants.USER, AuthoritiesConstants.ADMIN)
                    .requestMatchers("/v3/api-docs/**").hasAuthority(AuthoritiesConstants.ADMIN)
                    .requestMatchers("/management/health").permitAll()
                    .requestMatchers("/management/health/**").permitAll()
                    .requestMatchers("/management/info").permitAll()
                    .requestMatchers("/management/**").hasAuthority(AuthoritiesConstants.ADMIN)
            )
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions ->
                exceptions
                    .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                    .accessDeniedHandler(new BearerTokenAccessDeniedHandler())
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(withDefaults()));
        return http.build();
    }
}
