package org.skala.capstone;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewStore {
    final JdbcTemplate jdbc;
    final ObjectMapper json;
    public ReviewStore(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public JdbcTemplate jdbc() { return jdbc; }
    public ObjectMapper mapper() { return json; }
    public ObjectNode parse(String raw){try{return (ObjectNode)json.readTree(raw);}catch(Exception e){throw new IllegalStateException(e);}}
    public ObjectNode get(String tenant,String id){
        var rows=jdbc.queryForList("SELECT document::text FROM app.reviews WHERE id=CAST(? AS uuid) AND tenant_id=CAST(? AS uuid)",id,tenant);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"REVIEW_NOT_FOUND");
        return parse(rows.getFirst().get("document").toString());
    }
    public List<ObjectNode> list(String tenant){
        return jdbc.query("SELECT document::text FROM app.reviews WHERE tenant_id=CAST(? AS uuid) ORDER BY updated_at DESC LIMIT 200",
            (rs,n)->parse(rs.getString(1)),tenant);
    }
    @Transactional
    public ObjectNode mutate(String tenant,String id,Consumer<ObjectNode> action){
        var rows=jdbc.queryForList("SELECT document::text FROM app.reviews WHERE id=CAST(? AS uuid) AND tenant_id=CAST(? AS uuid) FOR UPDATE",id,tenant);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"REVIEW_NOT_FOUND");
        ObjectNode doc=parse(rows.getFirst().get("document").toString()); action.accept(doc);
        doc.put("updatedAt",java.time.Instant.now().toString());
        jdbc.update("UPDATE app.reviews SET document=CAST(? AS jsonb),updated_at=now() WHERE id=CAST(? AS uuid)",doc.toString(),id);
        return doc;
    }
    static ObjectNode version(ObjectNode doc,int no){
        for(JsonNode version:doc.withArray("versions"))if(version.path("no").asInt()==no)return (ObjectNode)version;
        throw new ResponseStatusException(HttpStatus.NOT_FOUND,"VERSION_NOT_FOUND");
    }
    static ObjectNode latest(ObjectNode doc){ArrayNode a=doc.withArray("versions");return (ObjectNode)a.get(a.size()-1);}
}
