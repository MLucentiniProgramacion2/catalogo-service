package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.controller;

import java.util.regex.Pattern;

/**
 * Cómo se escribe en el log el header {@code X-User-Login} que manda turnos (CU-07, DP-06): solo para trazabilidad,
 * nunca para autorizar ni filtrar, y nunca en la respuesta.
 * <p>
 * Lo controla el cliente, así que no se loguea cualquier cosa: solo si tiene de 3 a 50 caracteres (DPT-20) de los que
 * admite el login en turnos ({@code LOGIN_REGEX} de JHipster: letras, dígitos y {@code !$&*+=?^_`{|}~.@-}). Si no, se
 * escribe "inválido" <strong>sin repetir el valor</strong>: un {@code \r\n} podría partir la línea y fabricar una entrada
 * falsa (inyección en el log). El {@code CRLFLogConverter} de JHipster ya reemplaza esos caracteres en el patrón de la
 * consola, pero no cubre a un appender que use el mensaje crudo.
 */
final class UserLoginForLog {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9!$&*+=?^_`{|}~.@-]{3,50}");

    private UserLoginForLog() {}

    static String describe(String header) {
        if (header == null) {
            return "sin X-User-Login";
        }
        return ALLOWED.matcher(header).matches() ? "X-User-Login=" + header : "X-User-Login inválido";
    }
}
