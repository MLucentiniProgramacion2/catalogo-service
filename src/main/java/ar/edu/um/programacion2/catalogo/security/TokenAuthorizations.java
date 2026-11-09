package ar.edu.um.programacion2.catalogo.security;

import static org.springframework.security.authorization.AuthorityAuthorizationManager.hasAnyAuthority;
import static org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority;
import static org.springframework.security.authorization.AuthorizationManagers.allOf;
import static org.springframework.security.authorization.AuthorizationManagers.not;

import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Las dos clases de token que acepta el catálogo (DP-06), para las reglas de {@code SecurityConfiguration}:
 * <ul>
 * <li><strong>De usuario</strong> ({@link #userToken}): alguno de los roles pedidos y <strong>no</strong>
 * {@code ROLE_SERVICE}. {@code ROLE_SERVICE} es lo que marca un token como de servicio: un token que lo trae junto con
 * {@code ROLE_USER} o {@code ROLE_ADMIN} es un token de servicio mal armado y no sirve en los endpoints de usuario. No se
 * usa el {@code aud} para eso: un token de servicio mal armado puede no traerlo.</li>
 * <li><strong>De servicio</strong> ({@link #serviceToken}): ver {@link ServiceTokenAuthorizationManager}.</li>
 * </ul>
 * Un token mixto, entonces, da {@code 403} en todos lados.
 */
public final class TokenAuthorizations {

    private TokenAuthorizations() {}

    @SafeVarargs
    private static <T> AuthorizationManager<T> both(AuthorizationManager<T>... managers) {
        return allOf(managers);
    }

    /** Un token de usuario con alguno de esos roles. */
    public static AuthorizationManager<RequestAuthorizationContext> userToken(String... authorities) {
        return both(hasAnyAuthority(authorities), not(hasAuthority(AuthoritiesConstants.SERVICE)));
    }

    /** El token técnico de servicio de turnos, con {@code aud}, {@code iss} y vida corta. */
    public static AuthorizationManager<RequestAuthorizationContext> serviceToken() {
        return new ServiceTokenAuthorizationManager();
    }
}
