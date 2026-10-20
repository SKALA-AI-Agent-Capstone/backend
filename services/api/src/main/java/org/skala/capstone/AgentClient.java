package org.skala.capstone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Agent 내부 HTTP 호출. 테스트에서 이 빈만 바꿔 Agent 없이 저장 경로를 검증한다. */
@Component
public class AgentClient {
    /** Agent가 200이 아닌 응답을 준 경우. detail은 FastAPI HTTPException의 detail 문자열이다. */
    public static class AgentFailure extends IllegalStateException {
        public final int status; public final String detail;
        AgentFailure(int status,String detail){super("AGENT_UNAVAILABLE");this.status=status;this.detail=detail;}
    }
    final ObjectMapper json;
    final String agentUrl,internalToken;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public AgentClient(ObjectMapper json,@Value("${capstone.agent-base-url}")String agentUrl,@Value("${capstone.internal-token}")String internalToken){
        this.json=json;this.agentUrl=agentUrl;this.internalToken=internalToken;
    }
    public JsonNode post(String path,JsonNode body)throws Exception{
        var request=HttpRequest.newBuilder(URI.create(agentUrl+path)).timeout(Duration.ofSeconds(300)).header("Authorization","Bearer "+internalToken)
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200){
            String detail="";
            try{detail=json.readTree(response.body()).path("detail").asText("");}catch(Exception ignored){}
            throw new AgentFailure(response.statusCode(),detail);
        }
        return json.readTree(response.body());
    }
}
