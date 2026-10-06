package id.jakforge.openorchestrator.controller;

import id.jakforge.openorchestrator.config.OpenOrchestratorProperties;
import id.jakforge.openorchestrator.support.TestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pengalihan dari alamat API ke dasbor: halaman akar, dan /assistant/connect untuk Open Assistant. */
class RootControllerTest {

    private static RootController controller(String corsOrigins) {
        OpenOrchestratorProperties defaults = TestProperties.defaults();

        return new RootController(new OpenOrchestratorProperties(defaults.displayTimezone(), defaults.secret(),
                defaults.jwt(), new OpenOrchestratorProperties.Cors(corsOrigins, Duration.ofHours(1)), defaults.robot(),
                defaults.permissionCache(), defaults.scheduler(), defaults.limits(), defaults.bootstrap(),
                defaults.agent()));
    }

    private static MockHttpServletRequest request(String host, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/assistant/connect");
        request.setServerName(host);
        request.setServerPort(8080);
        request.setQueryString(query);
        return request;
    }

    @Test
    @DisplayName("localhost di CORS_ORIGINS = komputer server: peramban di VM diarahkan ke IP yang sama")
    void loopbackFollowsTheRequestHost() {
        assertEquals("http://10.37.114.59:3000", RootController.dashboardUrlFor("http://localhost:3000", "10.37.114.59"));
        assertEquals("http://localhost:3000", RootController.dashboardUrlFor("http://localhost:3000/", "localhost"));
        assertEquals("http://127.0.0.1:3000", RootController.dashboardUrlFor("http://127.0.0.1:3000", "localhost"));
        assertEquals("https://orchestrator.contoh.id",
                RootController.dashboardUrlFor("https://orchestrator.contoh.id/", "api.contoh.id"));
    }

    @Test
    @DisplayName("/assistant/connect: 302 ke halaman dasbor dengan parameter yang sama persis, tanpa disimpan")
    void assistantConnectRedirect() {
        String query = "client=open-assistant&state=Ab-12_x&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
                + "&code_challenge_method=S256&redirect_uri=openassistant%3A%2F%2Fsignin&machine=DESKTOP-01";

        ResponseEntity<Void> response = controller("http://localhost:3000")
                .redirectAssistantConnect(request("192.168.231.1", query));

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals("http://192.168.231.1:3000/assistant/connect?" + query,
                response.getHeaders().getFirst(HttpHeaders.LOCATION));
        assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));

        ResponseEntity<Void> withDomain = controller("https://orchestrator.contoh.id,http://localhost:3000")
                .redirectAssistantConnect(request("api.contoh.id", null));
        assertEquals("https://orchestrator.contoh.id/assistant/connect",
                withDomain.getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    @Test
    @DisplayName("halaman akar juga mengikuti alamat peramban")
    void rootRedirect() {
        ResponseEntity<Void> response = controller("http://localhost:3000").redirectToDashboard(request("10.0.0.5", null));

        assertEquals("http://10.0.0.5:3000", response.getHeaders().getFirst(HttpHeaders.LOCATION));
    }
}
