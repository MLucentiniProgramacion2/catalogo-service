package ar.edu.um.programacion2.catalogo.web.rest.errors;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests {@link ExceptionTranslator} controller advice.
 */
@WithMockUser
@AutoConfigureMockMvc
@IntegrationTest
class ExceptionTranslatorIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testConcurrencyFailure() throws Exception {
        mockMvc
            .perform(get("/api/exception-translator-test/concurrency-failure"))
            .andExpect(status().isConflict())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.message").value(ErrorConstants.ERR_CONCURRENCY_FAILURE));
    }

    @Test
    void testMethodArgumentNotValid() throws Exception {
        mockMvc
            .perform(post("/api/exception-translator-test/method-argument").content("{}").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.message").value(ErrorConstants.ERR_VALIDATION))
            .andExpect(jsonPath("$.fieldErrors.[0].objectName").value("test"))
            .andExpect(jsonPath("$.fieldErrors.[0].field").value("test"))
            .andExpect(jsonPath("$.fieldErrors.[0].message").value("must not be null"))
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.detail").value("Invalid request parameters"))
            .andExpect(content().string(not(containsString("java.lang"))));
    }

    @Test
    void unObjetoDeParametrosConUnValorFueraDeRangoTieneCodeYNoRepiteElValor() throws Exception {
        mockMvc
            .perform(get("/api/exception-translator-test/model-attribute").param("page", "-7"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value(ErrorConstants.CONSTRAINT_VIOLATION_TYPE.toString()))
            .andExpect(jsonPath("$.title").value("Method argument not valid"))
            .andExpect(jsonPath("$.message").value(ErrorConstants.ERR_VALIDATION))
            .andExpect(jsonPath("$.path").value("/api/exception-translator-test/model-attribute"))
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.detail").value("Invalid request parameters"))
            .andExpect(jsonPath("$.fieldErrors.[0].objectName").value("testParams"))
            .andExpect(jsonPath("$.fieldErrors.[0].field").value("page"))
            .andExpect(jsonPath("$.fieldErrors.[0].message").value("must be greater than or equal to 0"))
            .andExpect(content().string(not(containsString("-7"))))
            .andExpect(content().string(not(containsString("java.lang"))));
    }

    @Test
    void unObjetoDeParametrosConUnValorNoConvertibleUsaUnMensajeFijo() throws Exception {
        mockMvc
            .perform(get("/api/exception-translator-test/model-attribute").param("flag", "valorInventado"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.detail").value("Invalid request parameters"))
            .andExpect(jsonPath("$.fieldErrors.[0].field").value("flag"))
            .andExpect(jsonPath("$.fieldErrors.[0].message").value("Failed to convert value"))
            .andExpect(content().string(not(containsString("valorInventado"))))
            .andExpect(content().string(not(containsString("java.lang"))));
    }

    @Test
    void testMissingServletRequestPartException() throws Exception {
        mockMvc
            .perform(get("/api/exception-translator-test/missing-servlet-request-part"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.message").value("error.http.400"))
            .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void testMissingServletRequestParameterException() throws Exception {
        mockMvc
            .perform(get("/api/exception-translator-test/missing-servlet-request-parameter"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.message").value("error.http.400"));
    }

    @Test
    void testTypeMismatchConElFormatoDeJHipster() throws Exception {
        mockMvc
            .perform(get("/api/exception-translator-test/type-mismatch").param("flag", "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value(ErrorConstants.DEFAULT_TYPE.toString()))
            .andExpect(jsonPath("$.title").value("Bad Request"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.message").value("error.http.400"))
            .andExpect(jsonPath("$.path").value("/api/exception-translator-test/type-mismatch"))
            .andExpect(jsonPath("$.detail").value("Failed to convert 'flag'"))
            .andExpect(jsonPath("$.code").doesNotExist())
            .andExpect(content().string(not(containsString("abc"))))
            .andExpect(content().string(not(containsString("java.lang"))));
    }

    @Test
    void testAccessDenied() throws Exception {
        mockMvc
            .perform(get("/api/exception-translator-test/access-denied"))
            .andExpect(status().isForbidden())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.message").value("error.http.403"))
            .andExpect(jsonPath("$.detail").value("test access denied!"));
    }

    @Test
    void testUnauthorized() throws Exception {
        mockMvc
            .perform(get("/api/exception-translator-test/unauthorized"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.message").value("error.http.401"))
            .andExpect(jsonPath("$.path").value("/api/exception-translator-test/unauthorized"))
            .andExpect(jsonPath("$.detail").value("test authentication failed!"));
    }

    @Test
    void testMethodNotSupported() throws Exception {
        mockMvc
            .perform(post("/api/exception-translator-test/access-denied"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.message").value("error.http.405"))
            .andExpect(jsonPath("$.detail").value("Request method 'POST' is not supported"));
    }

    @Test
    void testExceptionWithResponseStatus() throws Exception {
        mockMvc
            .perform(get("/api/exception-translator-test/response-status"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.message").value("error.http.400"))
            .andExpect(jsonPath("$.title").value("test response status"));
    }

    @Test
    void testInternalServerError() throws Exception {
        mockMvc
            .perform(get("/api/exception-translator-test/internal-server-error"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.message").value("error.http.500"))
            .andExpect(jsonPath("$.title").value("Internal Server Error"));
    }
}
