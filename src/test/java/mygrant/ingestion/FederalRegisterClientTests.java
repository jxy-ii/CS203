package mygrant.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import mygrant.ingestion.federalregister.FederalRegisterClient;

import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Verifies downloaded Federal Register text is safe for PostgreSQL storage. */
class FederalRegisterClientTests {

    @Test
    void removesNullCharactersFromDownloadedPolicyText() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String url = "https://www.federalregister.gov/example.txt";
        server.expect(requestTo(url)).andExpect(method(GET))
                .andRespond(withSuccess("F-1\u0000 student policy", org.springframework.http.MediaType.TEXT_PLAIN));

        FederalRegisterClient client = new FederalRegisterClient(builder);
        assertThat(client.downloadFullText(url)).isEqualTo("F-1 student policy");
        server.verify();
    }

    @Test
    void retriesTransientFederalRegisterServerErrors() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String url = "https://www.federalregister.gov/example.txt";
        server.expect(requestTo(url)).andExpect(method(GET)).andRespond(withServerError());
        server.expect(requestTo(url)).andExpect(method(GET))
                .andRespond(withSuccess("F-1 student policy", org.springframework.http.MediaType.TEXT_PLAIN));

        FederalRegisterClient client = new FederalRegisterClient(builder);
        assertThat(client.downloadFullText(url)).isEqualTo("F-1 student policy");
        server.verify();
    }
}
