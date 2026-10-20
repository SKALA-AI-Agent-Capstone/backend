package org.skala.capstone;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReferenceController {
    private final ObjectMapper json;
    public ReferenceController(ObjectMapper json){this.json=json;}
    @GetMapping("/api/v1/reference-sources")
    public Map<String,Object> sources()throws IOException{
        try(var input=new ClassPathResource("reference-sources.json").getInputStream();
            var apis=new ClassPathResource("external-api-catalog.json").getInputStream()){
            return Map.of("sources",json.readTree(input),"apis",json.readTree(apis),"retrievalStatus","not_connected",
                "database","local PostgreSQL with pgvector","corpusStatus","not_ingested");
        }
    }
}
