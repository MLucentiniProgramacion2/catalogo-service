package ar.edu.um.programacion2.catalogo.security;

/**
 * Constants for Spring Security authorities.
 */
public final class AuthoritiesConstants {

    public static final String ADMIN = "ROLE_ADMIN";

    public static final String USER = "ROLE_USER";

    /**
     * El rol del JWT técnico de servicio que emite turnos para llamar al catálogo (DP-06). Es lo que marca un token como
     * de servicio: los endpoints de usuario lo rechazan aunque traiga además {@link #USER} o {@link #ADMIN}.
     */
    public static final String SERVICE = "ROLE_SERVICE";

    public static final String ANONYMOUS = "ROLE_ANONYMOUS";

    private AuthoritiesConstants() {}
}
