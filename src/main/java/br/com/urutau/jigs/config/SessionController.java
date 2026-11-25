package br.com.urutau.jigs.config;

import java.security.Principal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SessionController {
    @GetMapping("/csrf")
    CsrfData csrf(CsrfToken csrf) {
        return new CsrfData(csrf.getToken(), csrf.getParameterName(), csrf.getHeaderName());
    }

    @GetMapping("/api/session")
    SessionData session(Principal principal, CsrfToken csrf) {
        return new SessionData(principal.getName(), csrf.getToken(), csrf.getHeaderName());
    }

    record CsrfData(String token, String parameterName, String headerName) {}
    record SessionData(String username, String csrfToken, String csrfHeader) {}
}
