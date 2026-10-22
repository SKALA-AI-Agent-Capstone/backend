package org.skala.capstone.violation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.skala.capstone.review.ReviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Agent 결과와 문구 행을 같은 트랜잭션에 보존한다. */
@Service
public class ResultWriter {
    private static final Set<String> SEVERITIES=Set.of("high","medium","low");
    private final ReviewRepository store;
    private final ViolationRepository repository;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public ResultWriter(ReviewRepository store,ViolationRepository repository,ObjectMapper json,TransactionTemplate tx) {
        this.store=store;this.repository=repository;this.json=json;this.tx=tx;
    }
    public void saveResult(String tenant,String id,int no,JsonNode response){
        ObjectNode result=response.path("result") instanceof ObjectNode o?o.deepCopy():json.createObjectNode();
        record Row(String id,String excerpt,String severity,List<String> rewrites){}
        List<Row> rows=new ArrayList<>();
        for(JsonNode f:result.path("findings")){
            String phrase=f.path("phrase").asText("");
            if(phrase.isBlank() || !(f instanceof ObjectNode finding))continue;
            String violationId=UUID.randomUUID().toString();
            finding.put("violationId",violationId);
            List<String> rewrites=new ArrayList<>();
            for(JsonNode r:f.path("rewrites"))if(!r.asText("").isBlank())rewrites.add(r.asText());
            rows.add(new Row(violationId,phrase,severity(f.path("severity").asText("")),rewrites));
        }
        tx.executeWithoutResult(status->{
            boolean[] latest={false};
            store.mutate(tenant,id,d->{
                ObjectNode v=ReviewRepository.version(d,no);
                v.set("result",result);v.put("status","done");
                v.set("steps",json.valueToTree(List.of(100,100,100,100,100)));
                latest[0]=ReviewRepository.latest(d).path("no").asInt()==no;
            });
            if(!latest[0])return;
            repository.deleteForReview(id);
            for(Row row:rows){
                String suggestion=row.rewrites().isEmpty()?null:row.rewrites().getLast();
                repository.create(row.id(),id,row.excerpt(),row.severity(),suggestion,row.rewrites().size());
                for(int i=0;i<row.rewrites().size();i++)
                    repository.createAutomaticRewrite(row.id(),i+1,row.rewrites().get(i));
            }
        });
    }

    /** Agent severity가 허용값이 아니면 낮추지 않고 medium으로 둔다. */
    static String severity(String raw){
        String value=raw.toLowerCase(Locale.ROOT).trim();
        return SEVERITIES.contains(value)?value:"medium";
    }

}
