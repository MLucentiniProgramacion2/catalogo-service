package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UserLoginForLogTest {

    @ParameterizedTest
    @ValueSource(strings = { "ana.perez", "ana@example.com", "abc", "a_b-c.d", "x!$&*+=?^_`{|}~" })
    void unLoginComoLosQueAceptaTurnosSeEscribeTalCual(String login) {
        assertThat(UserLoginForLog.describe(login)).isEqualTo("X-User-Login=" + login);
    }

    @Test
    void cincuentaCaracteresSeAceptanYCincuentaYUnoNo() {
        assertThat(UserLoginForLog.describe("a".repeat(50))).isEqualTo("X-User-Login=" + "a".repeat(50));
        assertThat(UserLoginForLog.describe("a".repeat(51))).isEqualTo("X-User-Login inválido");
    }

    @ParameterizedTest
    @ValueSource(strings = { "ab", "", "ana\r\n2026-11-09 INFO falso", "ana perez", "ana%0d%0a", "ana\tperez", "añá", "<script>" })
    void unValorInvalidoSeInformaSinRepetirlo(String valor) {
        assertThat(UserLoginForLog.describe(valor)).isEqualTo("X-User-Login inválido");
    }

    @Test
    void sinHeaderSeDiceQueNoVino() {
        assertThat(UserLoginForLog.describe(null)).isEqualTo("sin X-User-Login");
    }
}
