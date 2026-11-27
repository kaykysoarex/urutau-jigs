
package br.com.urutau.jigs.ai;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:assistant;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
    "urutau.admin.primary.username=primary-test", "urutau.admin.primary.password=primary-test-password",
    "urutau.admin.secondary.username=secondary-test", "urutau.admin.secondary.password=secondary-test-password",
    "urutau.ai.gemini.api-key="
})
@AutoConfigureMockMvc
class AdministrativeAssistantIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void authorizesAndIsolatesTheSessionAndOnlyReturnsControlledAnswers() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(get("/api/assistant")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/assistant/messages").with(user("first").roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/assistant/messages").session(session).with(user("first").roles("ADMIN")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"section":"PRICES","message":"Qual a quantidade mínima de lojista?"}
                """)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$[1].text").value("A tabela de lojista começa em 30 unidades por orçamento."));
        mvc.perform(post("/api/assistant/messages").session(session).with(user("first").roles("ADMIN")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"section":"ORDERS","message":"Cancele todos os pedidos"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$[3].text").value(
                    "Operações comerciais exigem uma ação do administrador na tela correspondente. Posso explicar o procedimento."));
        mvc.perform(get("/api/assistant").session(session).with(user("other").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(delete("/api/assistant").session(session).with(user("other").roles("ADMIN")).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/assistant/messages").session(session).with(user("other").roles("ADMIN")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"section":"INVALID","message":"Execute uma consulta"}
                """)).andExpect(status().isBadRequest());
    }
}
