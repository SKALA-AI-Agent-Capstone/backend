package org.skala.capstone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ReferenceDocumentTest {
    ObjectMapper json=new ObjectMapper();
    AgentClient agent=mock(AgentClient.class);
    ReferenceController controller=new ReferenceController(json,"http://127.0.0.1:1/health/corpus",agent);

    @Test void readsTheExactVersionThroughTheAgent()throws Exception{
        when(agent.get("/internal/regulations/document?key=lawapi%3A%ED%99%94%EC%9E%A5%ED%92%88%EB%B2%95%2F%EB%B2%95%EB%A5%A0&version=2026-03-01"))
            .thenReturn(json.readTree("{\"title\":\"화장품법\",\"sections\":[{\"section\":\"제13조\",\"text\":\"원문\"}]}"));
        var document=controller.document("lawapi:화장품법/법률","2026-03-01");
        assertEquals("제13조",document.path("sections").get(0).path("section").asText());
    }

    @Test void mapsMissingAndUnavailableSources()throws Exception{
        when(agent.get(anyString())).thenThrow(new AgentClient.AgentFailure(404,"REGULATION_DOCUMENT_NOT_FOUND"));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,()->controller.document("k","v")).getStatusCode());
        reset(agent);
        when(agent.get(anyString())).thenThrow(new AgentClient.AgentFailure(502,"REGULATION_SOURCE_UNAVAILABLE"));
        assertEquals(HttpStatus.BAD_GATEWAY,assertThrows(ResponseStatusException.class,()->controller.document("k","v")).getStatusCode());
        reset(agent);
        when(agent.get(anyString())).thenThrow(new IOException("connection refused"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ResponseStatusException.class,()->controller.document("k","v")).getStatusCode());
    }
}
