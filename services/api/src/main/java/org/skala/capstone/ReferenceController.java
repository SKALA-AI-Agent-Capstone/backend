package org.skala.capstone;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReferenceController {
    private final ObjectMapper json;
    private final String corpusUrl;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public ReferenceController(ObjectMapper json,@Value("${capstone.rag-corpus-url}") String corpusUrl){this.json=json;this.corpusUrl=corpusUrl;}
    @GetMapping("/api/v1/reference-sources")
    public Map<String,Object> sources()throws IOException{
        try(var input=new ClassPathResource("reference-sources.json").getInputStream();
            var apis=new ClassPathResource("external-api-catalog.json").getInputStream()){
            Map<String,Object> body=new LinkedHashMap<>();
            body.put("sources",json.readTree(input));body.put("apis",json.readTree(apis));
            body.put("database","local PostgreSQL with pgvector");
            JsonNode corpus=corpus();
            // indexed, not_indexed or unavailable. The registry alone never claims a source is searchable.
            body.put("retrievalStatus",corpus.path("status").asText("unavailable"));
            body.put("corpus",corpus.path("documents"));
            return body;
        }
    }
    private JsonNode corpus(){
        try{
            var response=http.send(HttpRequest.newBuilder(URI.create(corpusUrl)).timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            JsonNode node=json.readTree(response.body());
            return node.has("status")?node:json.createObjectNode().put("status","unavailable");
        }catch(IOException|InterruptedException|IllegalArgumentException e){
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            return json.createObjectNode().put("status","unavailable");
        }
    }
}
