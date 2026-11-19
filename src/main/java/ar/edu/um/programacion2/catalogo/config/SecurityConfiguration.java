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
                // cualquier usuario, y /api/service/** (la agenda de CU-07) le daría 403 al token de servicio.
                //
                // /api/service/** es la regla de los endpoints de servicio: solo el JWT técnico de turnos, con
                // ROLE_SERVICE, aud=catalogo-service, iss=turnos-service y vida corta (DP-06, ADR 0007). Si falta algo,
                // 403; sin token o con uno inválido, 401.
                //
                // /api/** es la regla de los endpoints de usuario (CU-05, CU-06, CU-09). Es una lista de permitidos:
                // pide ROLE_USER o ROLE_ADMIN y que no sea un token de servicio (ROLE_SERVICE), así que el token técnico
                // de servicio, uno mixto y uno sin roles reciben 403 (DP-06). ROLE_ADMIN está porque en turnos un admin
                // puede quedar solo con ese rol (lo permite /api/admin/users). Las reglas de admin piden lo mismo.
                // prettier-ignore
                authz
                    .requestMatchers("/api/service/**").access(TokenAuthorizations.serviceToken())
                    .requestMatchers("/api/admin/**").access(TokenAuthorizations.userToken(AuthoritiesConstants.ADMIN))
                    .requestMatchers("/api/**").access(TokenAuthorizations.userToken(AuthoritiesConstants.USER, AuthoritiesConstants.ADMIN))
                    .requestMatchers("/v3/api-docs/**").access(TokenAuthorizations.userToken(AuthoritiesConstants.ADMIN))
                    .requestMatchers("/management/health").permitAll()
                    .requestMatchers("/management/health/**").permitAll()
                    .requestMatchers("/management/info").permitAll()
                    .requestMatchers("/management/**").access(TokenAuthorizations.userToken(AuthoritiesConstants.ADMIN))
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
