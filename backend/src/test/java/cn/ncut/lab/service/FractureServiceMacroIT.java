package cn.ncut.lab.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class FractureServiceMacroIT {
    @Autowired FractureService fracture;

    @Test
    void analyzeMacroViaHttpClient() {
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        String image = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        Map<String, Object> out = fracture.analyzeMacro(image, "TENS", "正面");
        assertNotNull(out.get("candidate"));
    }
}
