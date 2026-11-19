package com.freesia.admin.dashboard;

import com.freesia.admin.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.modulith.test.ApplicationModuleTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Boots this module alone: it must not need a bean of any other business module. */
@ApplicationModuleTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardModuleTest extends IntegrationTest {
    @LocalServerPort int port;

    @Test
    void productInformationIsPublic() throws Exception {
        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/product")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(
                "{\"code\":200,\"msg\":\"OK\",\"data\":{\"productName\":\"Freesia\",\"applicationName\":\"Freesia Admin\",\"version\":\"0.1.0\"}}");
    }
}
