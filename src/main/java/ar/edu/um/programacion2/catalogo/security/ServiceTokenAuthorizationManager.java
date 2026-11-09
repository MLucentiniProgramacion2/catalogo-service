package ar.edu.um.programacion2.catalogo.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Regla de autorización de los endpoints de servicio ({@code /api/service/**}): solo pasa el JWT técnico que emite turnos
 * (DP-06 y ADR 0007). La firma y la vigencia ya las validó el decoder; acá se exige, además:
 * <ul>
 * <li>{@code ROLE_SERVICE} y <strong>ningún rol de usuario</strong>: un token mixto no sirve en ningún lado;</li>
 * <li>{@code aud} que contenga {@value #AUDIENCE};</li>
 * <li>{@code iss} igual a {@value #ISSUER};</li>
 * <li><strong>vida corta</strong>: {@code iat} y {@code exp} presentes, {@code exp - iat} de hasta 5 min e {@code iat} no
 * más de 60 s en el futuro. Sin el último, un token con {@code iat} dentro de un año y {@code exp} 5 min después cumpliría
 * la diferencia y serviría todo ese tiempo.</li>
 * </ul>
 * Si falta cualquiera, el pedido está autenticado pero no autorizado: {@code 403}. Sin token o con uno inválido, el
 * decoder ya respondió {@code 401}.
 * <p>
 * <strong>Por qué no en el decoder:</strong> un validador de {@code aud} o {@code iss} en el {@code JwtDecoder} daría
 * {@code 401} y además rechazaría los tokens de usuario, que no los traen: DP-06 dice que es una regla de autorización del
 * endpoint, no de la validación general del token.
 * <p>
 * <strong>Por qué {@code getClaimAsString(iss)}:</strong> {@link Jwt#getIssuer()} devuelve una {@code URL} y falla con
 * {@code turnos-service}, que no lo es (medido en el #35).
 * <p>
 * Los valores son constantes y no propiedades: son las identidades del contrato entre los dos servicios propios, no
 * datos del despliegue; cambiarlos exige cambiar los dos servicios y el ADR. Los 5 min son el máximo de DPT-16: en turnos
 * el valor es configurable, pero no puede superarlo.
 * <p>
 * El reloj es propio ({@code Clock.systemUTC()}), no el bean de la sincronización: algunos tests lo reemplazan por uno
 * fijo, y la vigencia de un token se compara con la hora real.
 */
public final class ServiceTokenAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    /** El {@code aud} que tiene que traer el token: este servicio. */
    public static final String AUDIENCE = "catalogo-service";

    /** El {@code iss} que tiene que traer el token: el único servicio que llama a este. */
    public static final String ISSUER = "turnos-service";

    /** La vida máxima del token, {@code exp - iat} (DPT-16 de turnos). */
    public static final Duration MAX_LIFETIME = Duration.ofMinutes(5);

    /** Cuánto puede estar {@code iat} en el futuro: la misma tolerancia que usa el decoder para {@code exp}. */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private static final Set<String> USER_AUTHORITIES = Set.of(AuthoritiesConstants.USER, AuthoritiesConstants.ADMIN);

    private final Clock clock;

    public ServiceTokenAuthorizationManager() {
        this(Clock.systemUTC());
    }

    ServiceTokenAuthorizationManager(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "Falta el reloj");
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        return new AuthorizationDecision(isServiceToken(authentication.get()));
    }

    boolean isServiceToken(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication) || !authentication.isAuthenticated()) {
            return false;
        }
        Set<String> authorities = authentication
            .getAuthorities()
            .stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());
        if (!authorities.contains(AuthoritiesConstants.SERVICE) || authorities.stream().anyMatch(USER_AUTHORITIES::contains)) {
            return false;
        }
        Jwt jwt = jwtAuthentication.getToken();
        return hasAudience(jwt) && ISSUER.equals(jwt.getClaimAsString(JwtClaimNames.ISS)) && isShortLived(jwt);
    }

    private static boolean hasAudience(Jwt jwt) {
        List<String> audience = jwt.getAudience();
        return audience != null && audience.contains(AUDIENCE);
    }

    private boolean isShortLived(Jwt jwt) {
        Instant issuedAt = jwt.getIssuedAt();
        Instant expiresAt = jwt.getExpiresAt();
        // Que exp sea posterior a iat ya lo garantiza Jwt al construirse.
        if (issuedAt == null || expiresAt == null) {
            return false;
        }
        return !issuedAt.isAfter(clock.instant().plus(CLOCK_SKEW)) && Duration.between(issuedAt, expiresAt).compareTo(MAX_LIFETIME) <= 0;
    }
}
