package org.skala.capstone;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ReferenceController {
    private final ObjectMapper json;
    private final String corpusUrl;
    private final AgentClient agent;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public ReferenceController(ObjectMapper json,@Value("${capstone.rag-corpus-url}") String corpusUrl,AgentClient agent){
        this.json=json;this.corpusUrl=corpusUrl;this.agent=agent;
    }
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
    /** 근거 조항이 속한 문서의 적재 원문 전체. Agent가 rag-mcp Resource(regulation://corpus, regulation://documents)로 읽는다.
     *  근거에 남은 document_key와 버전 그대로 찾으며 다른 버전으로 바꿔 보여 주지 않는다. */
    @GetMapping("/api/v1/reference-documents")
    public JsonNode document(@RequestParam String key,@RequestParam String version){
        try{
            return agent.get("/internal/regulations/document?key="+URLEncoder.encode(key,StandardCharsets.UTF_8)
                +"&version="+URLEncoder.encode(version,StandardCharsets.UTF_8));
        }catch(AgentClient.AgentFailure failure){
            if(failure.status==404)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"REGULATION_DOCUMENT_NOT_FOUND");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"REGULATION_SOURCE_UNAVAILABLE");
        }catch(Exception error){
            if(error instanceof InterruptedException)Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AGENT_UNAVAILABLE");
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
