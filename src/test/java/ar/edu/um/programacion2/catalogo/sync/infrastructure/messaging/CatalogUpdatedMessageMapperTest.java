package ar.edu.um.programacion2.catalogo.sync.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.sync.CatalogUpdatedJson;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogUpdateNotice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogUpdatedMessageMapperTest {

    @Test
    void entiendeUnMensajeRealDelTopic() {
        String real = CatalogUpdatedJson.of("8b097be5-7ba5-4618-97e8-c502d3f46723", 41);

        assertThat(CatalogUpdatedMessageMapper.toNotice(real)).contains(new CatalogUpdateNotice("8b097be5-7ba5-4618-97e8-c502d3f46723", 41));
    }

    @Test
    void ignoraLosCamposDesconocidos() {
        String conCamposNuevos = """
            {"eventId":"e1","newVersion":7,"campoNuevo":{"anidado":[1,2]},"otro":true}""";

        assertThat(CatalogUpdatedMessageMapper.toNotice(conCamposNuevos)).contains(new CatalogUpdateNotice("e1", 7));
    }

    @Test
    void noDependeDeLosCamposQueNoUsa() {
        assertThat(CatalogUpdatedMessageMapper.toNotice("""
            {"eventId":"e1","newVersion":7}""")).contains(new CatalogUpdateNotice("e1", 7));
    }

    @Test
    void unTombstoneEsInvalido() {
        assertThat(CatalogUpdatedMessageMapper.toNotice(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "esto no es json",
            "",
            "   ",
            "null",
            "[]",
            "\"texto\"",
            "{}",
            "{\"eventId\":\"e1\"}",
            "{\"newVersion\":7}",
            "{\"eventId\":null,\"newVersion\":7}",
            "{\"eventId\":\"e1\",\"newVersion\":null}",
            "{\"eventId\":\"  \",\"newVersion\":7}",
            "{\"eventId\":\"\",\"newVersion\":7}",
            "{\"eventId\":\"e1\",\"newVersion\":\"x\"}",
            "{\"eventId\":\"e1\",\"newVersion\":7.5}",
            "{\"eventId\":{},\"newVersion\":7}",
            "{\"eventId\":\"e1\",\"newVersion\":9223372036854775808}",
            "{\"eventId\":\"e1\",\"newVersion\":7} basura",
        }
    )
    void loQueNoEsUnAvisoCompletoEsInvalido(String mensaje) {
        assertThat(CatalogUpdatedMessageMapper.toNotice(mensaje)).isEmpty();
    }
}
