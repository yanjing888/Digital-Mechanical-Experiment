package cn.ncut.lab.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=CaptureProxyAddressTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration","debug=false","logging.level.root=WARN"})
class CaptureProxyAddressTest {
    @Configuration @EnableAutoConfiguration
    static class Config { @Bean AddressProbe probe() { return new AddressProbe(); } }
    @RestController
    static class AddressProbe { @GetMapping("/test-client-address") String address(HttpServletRequest request) { return request.getRemoteAddr(); } }
    @Autowired TestRestTemplate client;
    @Test void localProxyResolvesRealLabComputerAndDoesNotTrustItsForgedPredecessor() {
        var headers=new HttpHeaders();headers.set("X-Forwarded-For","198.51.100.99, 192.168.1.12");
        var response=client.exchange("/test-client-address",HttpMethod.GET,new HttpEntity<>(headers),String.class);
        assertEquals(HttpStatus.OK,response.getStatusCode());assertEquals("192.168.1.12",response.getBody());
    }
}
