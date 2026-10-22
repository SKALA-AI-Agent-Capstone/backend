package org.skala.capstone.system;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EnvironmentController {
    private final EnvironmentService environment;

    public EnvironmentController(EnvironmentService environment) {
        this.environment = environment;
    }

    @GetMapping("/internal/bootstrap")
    public Map<String, Object> bootstrap() {
        return environment.bootstrap();
    }

    @GetMapping("/api/dev/status")
    public Map<String, Object> status() {
        return environment.status();
    }
}
