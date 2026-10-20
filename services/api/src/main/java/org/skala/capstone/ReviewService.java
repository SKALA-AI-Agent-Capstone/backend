package org.skala.capstone;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewService implements AutoCloseable {
    final ReviewStore store;
    final StringRedisTemplate cache;
    final ObjectMapper json;
    final String agentUrl,internalToken;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final ExecutorService jobs=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),
        Thread.ofPlatform().name("review-job-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    public ReviewService(ReviewStore store,StringRedisTemplate cache,ObjectMapper json,
        @Value("${capstone.agent-base-url}")String agentUrl,@Value("${capstone.internal-token}")String internalToken){
        this.store=store;this.cache=cache;this.json=json;this.agentUrl=agentUrl;this.internalToken=internalToken;
    }
    public static String hash(byte[] raw){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}catch(Exception e){throw new IllegalStateException(e);}}
    static String assetKey(String id){return "review:asset:"+id;}
    String mime(byte[] bytes){
        if(bytes.length>5 && new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))return "application/pdf";
        if(bytes.length>8 && bytes[0]==(byte)137 && bytes[1]==80 && bytes[2]==78 && bytes[3]==71)return "image/png";
        if(bytes.length>3 && bytes[0]==(byte)255 && bytes[1]==(byte)216 && bytes[2]==(byte)255)return "image/jpeg";
        if(bytes.length>12 && new String(bytes,0,4,StandardCharsets.US_ASCII).equals("RIFF") && new String(bytes,8,4,StandardCharsets.US_ASCII).equals("WEBP"))return "image/webp";
        throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"PDF_OR_IMAGE_REQUIRED");
    }
    @Transactional
    public ObjectNode submit(Jwt jwt,String metadata,List<MultipartFile> files,List<MultipartFile> evidence,String parent){
        if(files==null || files.isEmpty() || files.size()>5 || evidence.size()>10)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"AD_FILES_REQUIRED_MAX_5");
        ObjectNode meta=store.parse(metadata);
        if(meta.path("product").path("name").asText("").isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"PRODUCT_NAME_REQUIRED");
        String tenant=jwt.getClaimAsString("tenantId"),id=parent==null?UUID.randomUUID().toString():parent;
        ObjectNode doc;
        int no;
        if(parent==null){
            doc=json.createObjectNode();String now=Instant.now().toString();
            doc.put("id",id).put("tenantId",tenant).put("authorId",jwt.getSubject()).put("createdAt",now).put("updatedAt",now);
            doc.put("authorName",store.jdbc().queryForObject("SELECT name FROM app.users WHERE id=CAST(? AS uuid)",String.class,jwt.getSubject()));
            doc.put("productName",meta.path("product").path("name").asText());doc.putNull("campaign");doc.putArray("versions");no=1;
            store.jdbc().update("INSERT INTO app.reviews(id,tenant_id,author_id,document) VALUES (CAST(? AS uuid),CAST(? AS uuid),CAST(? AS uuid),CAST(? AS jsonb))",id,tenant,jwt.getSubject(),doc.toString());
        }else{
            doc=store.get(tenant,id);
            if(!doc.path("authorId").asText().equals(jwt.getSubject()) && !"admin".equals(jwt.getClaimAsString("role")))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"AUTHOR_REQUIRED");
            if(Set.of("queued","analyzing").contains(ReviewStore.latest(doc).path("status").asText()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"REVIEW_RUNNING");
            no=ReviewStore.latest(doc).path("no").asInt()+1;
        }
        List<MultipartFile> all=new ArrayList<>(files);all.addAll(evidence);
        if(all.stream().mapToLong(MultipartFile::getSize).sum()>75L*1024*1024)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"TOTAL_LIMIT_75_MIB");
        ArrayNode ads=json.createArrayNode(),docs=json.createArrayNode(),assets=json.createArrayNode();
        List<String> cached=new ArrayList<>();
        try{
            for(int i=0;i<all.size();i++){
                MultipartFile file=all.get(i);
                if(file.isEmpty() || file.getSize()>25L*1024*1024)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"FILE_LIMIT_25_MIB");
                byte[] raw=file.getBytes();String contentType=mime(raw),assetId=UUID.randomUUID().toString();
                String name=Objects.toString(file.getOriginalFilename(),"upload").replaceAll("[\\r\\n\\\\/]","_");
                if(name.length()>200)name=name.substring(name.length()-200);
                String role=i<files.size()?"advertisement":"evidence";
                String docType=i<files.size()?"":meta.path("evidence").path(i-files.size()).path("docType").asText("other");
                store.jdbc().update("INSERT INTO app.review_assets VALUES (CAST(? AS uuid),CAST(? AS uuid),?,?,?,?,?,?,?)",assetId,id,no,name,contentType,role,docType,raw.length,hash(raw));
                cache.opsForValue().set(assetKey(assetId),Base64.getEncoder().encodeToString(raw),Duration.ofMinutes(30));cached.add(assetId);
                ObjectNode asset=json.createObjectNode().put("id",assetId).put("name",name).put("size",raw.length).put("mime",contentType).put("role",role).put("docType",docType)
                    .put("kind",contentType.equals("application/pdf")?"detail_page":"image");
                assets.add(asset);if(i<files.size())ads.add(asset.deepCopy());else docs.add(asset.deepCopy());
            }
            ObjectNode input=json.createObjectNode();input.set("product",meta.path("product"));input.set("media",meta.path("media").isArray()?meta.path("media"):json.createArrayNode());
            input.put("copy","");input.set("files",ads);input.set("evidence",docs);
            ObjectNode version=json.createObjectNode().put("no",no).put("authorId",jwt.getSubject()).put("authorName",doc.path("authorName").asText()).put("createdAt",Instant.now().toString()).put("status","queued");
            version.set("input",input);version.set("steps",json.valueToTree(List.of(0,0,0,0,0)));version.putNull("result");version.putNull("error");version.putNull("originalsDiscardedAt");
            version.set("approval",json.createObjectNode().put("state","pending").set("history",json.createArrayNode()));
            String runToken=UUID.randomUUID().toString()+UUID.randomUUID();
            store.jdbc().update("INSERT INTO app.review_runs VALUES (CAST(? AS uuid),?,?)",id,no,hash(runToken.getBytes(StandardCharsets.UTF_8)));
            final int versionNo=no;
            store.mutate(tenant,id,d->{d.withArray("versions").add(version);d.put("productName",input.path("product").path("name").asText());});
            ObjectNode request=json.createObjectNode().put("reviewId",id).put("tenantId",tenant).put("versionNo",no).put("runToken",runToken);
            request.set("assets",assets);request.set("product",input.path("product"));
            // Start only after the transaction is visible to Agent asset reads.
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCommit(){
                    try{jobs.submit(()->execute(request));}catch(RejectedExecutionException e){fail(tenant,id,versionNo,"BUSY_REUPLOAD_REQUIRED");purge(id,versionNo);}
                }});
            return store.get(tenant,id);
        }catch(Exception e){for(String asset:cached)cache.delete(assetKey(asset));if(e instanceof ResponseStatusException status)throw status;
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"UPLOAD_STORAGE_FAILED");}
    }
    void execute(ObjectNode request){
        String tenant=request.path("tenantId").asText(),id=request.path("reviewId").asText();int no=request.path("versionNo").asInt();
        try{
            store.mutate(tenant,id,d->ReviewStore.version(d,no).put("status","analyzing"));
            JsonNode response=callAgent("/internal/runs",request);
            store.mutate(tenant,id,d->{ObjectNode v=ReviewStore.version(d,no);v.set("result",response.path("result"));v.put("status","done");
                v.set("steps",json.valueToTree(List.of(100,100,100,100,100)));v.put("originalsDiscardedAt",Instant.now().toString());
                for(JsonNode f:v.path("input").path("files"))((ObjectNode)f).put("discarded",true);});
        }catch(Exception e){fail(tenant,id,no,"ANALYSIS_FAILED_REUPLOAD_REQUIRED");}
        finally{purge(id,no);}
    }
    JsonNode callAgent(String path,JsonNode body)throws Exception{
        var request=HttpRequest.newBuilder(URI.create(agentUrl+path)).timeout(Duration.ofSeconds(300)).header("Authorization","Bearer "+internalToken)
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IllegalStateException("AGENT_UNAVAILABLE");return json.readTree(response.body());
    }
    void fail(String tenant,String id,int no,String error){try{store.mutate(tenant,id,d->{var v=ReviewStore.version(d,no);v.put("status","failed").put("error",error);});}catch(Exception ignored){}}
    void purge(String id,int no){for(var row:store.jdbc().queryForList("SELECT id FROM app.review_assets WHERE review_id=CAST(? AS uuid) AND version_no=?",id,no))cache.delete(assetKey(row.get("id").toString()));}
    String readCachedAsset(String id) { return cache.opsForValue().get(assetKey(id)); }
    void authorizeRun(String id,int no,String runToken){
        var rows=store.jdbc().queryForList("SELECT token_hash FROM app.review_runs WHERE review_id=CAST(? AS uuid) AND version_no=?",id,no);
        String digest=hash(Objects.toString(runToken,"").getBytes(StandardCharsets.UTF_8));
        if(rows.size()!=1 || !MessageDigest.isEqual(rows.getFirst().get("token_hash").toString().getBytes(StandardCharsets.UTF_8),digest.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"RUN_SCOPE_MISMATCH");
    }
    @Override public void close(){jobs.shutdown();}
}
