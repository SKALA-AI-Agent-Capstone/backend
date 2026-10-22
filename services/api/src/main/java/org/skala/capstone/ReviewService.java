package org.skala.capstone;

import org.skala.capstone.billing.CreditLedgerService;
import org.skala.capstone.common.AgentClient;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewService implements AutoCloseable {
    final ReviewStore store;
    final StringRedisTemplate cache;
    final ObjectMapper json;
    final AgentClient agent;
    final ViolationService violations;
    final CreditLedgerService credits;
    final TransactionTemplate tx;
    final ReviewEvents events;
    final boolean queueEnabled;
    final ExecutorService jobs=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),
        Thread.ofPlatform().name("review-job-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    public ReviewService(ReviewStore store,StringRedisTemplate cache,ObjectMapper json,AgentClient agent,ViolationService violations,
        CreditLedgerService credits,TransactionTemplate tx,ReviewEvents events,@Value("${capstone.review.queue-enabled:false}")boolean queueEnabled){
        this.store=store;this.cache=cache;this.json=json;this.agent=agent;this.violations=violations;this.credits=credits;
        this.tx=tx;this.events=events;this.queueEnabled=queueEnabled;
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
    public ObjectNode submit(Jwt jwt,String metadata,List<MultipartFile> files,List<MultipartFile> evidence){
        if(files==null || files.isEmpty() || files.size()>5 || evidence.size()>10)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"AD_FILES_REQUIRED_MAX_5");
        ObjectNode meta=store.parse(metadata);
        if(meta.path("product").path("name").asText("").isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"PRODUCT_NAME_REQUIRED");
        // 재검토(같은 검토에 새 버전 올리기)는 2026-10-22에 없앴다. 검토마다 버전은 1 하나뿐이다.
        String tenant=jwt.getClaimAsString("tenantId"),id=UUID.randomUUID().toString();
        int no=1;
        ObjectNode doc=json.createObjectNode();String now=Instant.now().toString();
        doc.put("id",id).put("tenantId",tenant).put("authorId",jwt.getSubject()).put("createdAt",now).put("updatedAt",now);
        doc.put("authorName",store.jdbc().queryForObject("SELECT name FROM app.users WHERE id=CAST(? AS uuid)",String.class,jwt.getSubject()));
        doc.put("productName",meta.path("product").path("name").asText());doc.putNull("campaign");doc.putArray("versions");
        store.jdbc().update("INSERT INTO app.reviews(id,tenant_id,author_id,document) VALUES (CAST(? AS uuid),CAST(? AS uuid),CAST(? AS uuid),CAST(? AS jsonb))",id,tenant,jwt.getSubject(),doc.toString());
        List<MultipartFile> all=new ArrayList<>(files);all.addAll(evidence);
        if(all.stream().mapToLong(MultipartFile::getSize).sum()>75L*1024*1024)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"TOTAL_LIMIT_75_MIB");
        // This transaction also owns the review/version insert. Invalid uploads roll back the reservation.
        credits.reserve(tenant,jwt.getSubject(),id,no);
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
                store.jdbc().update("INSERT INTO app.review_assets(id,review_id,version_no,name,mime_type,role,doc_type,size_bytes,sha256,bytes) VALUES (CAST(? AS uuid),CAST(? AS uuid),?,?,?,?,?,?,?,?)",assetId,id,no,name,contentType,role,docType,raw.length,hash(raw),raw);
                cache.opsForValue().set(assetKey(assetId),Base64.getEncoder().encodeToString(raw),Duration.ofMinutes(30));cached.add(assetId);
                ObjectNode asset=json.createObjectNode().put("id",assetId).put("name",name).put("size",raw.length).put("mime",contentType).put("role",role).put("docType",docType)
                    .put("kind",contentType.equals("application/pdf")?"detail_page":"image");
                assets.add(asset);if(i<files.size())ads.add(asset.deepCopy());else docs.add(asset.deepCopy());
            }
            ObjectNode input=json.createObjectNode();input.set("product",meta.path("product"));input.set("media",meta.path("media").isArray()?meta.path("media"):json.createArrayNode());
            input.put("copy","");input.set("files",ads);input.set("evidence",docs);
            ObjectNode version=json.createObjectNode().put("no",no).put("authorId",jwt.getSubject()).put("authorName",doc.path("authorName").asText()).put("createdAt",Instant.now().toString()).put("status","queued");
            version.set("input",input);version.set("steps",json.valueToTree(List.of(0,0,0,0,0)));version.putNull("result");version.putNull("error");version.putNull("originalsDiscardedAt");
            String runToken=UUID.randomUUID().toString()+UUID.randomUUID();
            store.jdbc().update("INSERT INTO app.review_runs VALUES (CAST(? AS uuid),?,?)",id,no,hash(runToken.getBytes(StandardCharsets.UTF_8)));
            final int versionNo=no;
            store.mutate(tenant,id,d->{d.withArray("versions").add(version);d.put("productName",input.path("product").path("name").asText());});
            ObjectNode request=json.createObjectNode().put("reviewId",id).put("tenantId",tenant).put("versionNo",no).put("runToken",runToken);
            request.set("assets",assets);request.set("product",input.path("product"));
            if(queueEnabled){
                // The job and credit reservation commit together. The dispatcher publishes it later.
                store.jdbc().update("INSERT INTO app.review_jobs(review_id,version_no,request) VALUES (CAST(? AS uuid),?,CAST(? AS jsonb))",
                    id,no,request.toString());
            }else{
                // Local tests and standalone development retain the bounded in-process executor.
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCommit(){
                        try{jobs.submit(()->execute(request));}catch(RejectedExecutionException e){Thread.ofVirtual().start(()->{fail(tenant,id,versionNo,"BUSY_REUPLOAD_REQUIRED");purge(id,versionNo);});}
                    }});
            }
            return store.get(tenant,id);
        }catch(Exception e){for(String asset:cached)cache.delete(assetKey(asset));if(e instanceof ResponseStatusException status)throw status;
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"UPLOAD_STORAGE_FAILED");}
    }
    void execute(ObjectNode request){
        String tenant=request.path("tenantId").asText(),id=request.path("reviewId").asText();int no=request.path("versionNo").asInt();
        try{
            runQueued(request);
        }catch(Exception e){fail(tenant,id,no,"ANALYSIS_FAILED_REUPLOAD_REQUIRED");}
        finally{purge(id,no);}
    }
    void runQueued(JsonNode request)throws Exception{
        String tenant=request.path("tenantId").asText(),id=request.path("reviewId").asText();int no=request.path("versionNo").asInt();
        store.mutate(tenant,id,d->ReviewStore.version(d,no).put("status","analyzing"));
        events.publish(id,no,"started");
        JsonNode response=callAgent("/internal/runs",request);
        tx.executeWithoutResult(status->{
            violations.saveResult(tenant,id,no,response);
            JsonNode pipeline=response.path("result").path("pipelineStatus");
            // HTTP 200 alone is not a completed analysis: missing models and partial results are free.
            credits.settle(id,no,"complete".equals(pipeline.path("text").asText())
                && "complete".equals(pipeline.path("visual").asText()));
        });
        // 결과가 저장되면 바로 리포트다. 승인 대기가 없으므로 completed 하나로 스트림을 닫는다.
        events.publish(id,no,"completed");
        try{purge(id,no);}catch(Exception ignored){}
    }
    boolean completed(String tenant,String id,int no){
        try{
            if(!"done".equals(ReviewStore.version(store.get(tenant,id),no).path("status").asText()))return false;
            String status=store.jdbc().queryForObject(
                "SELECT status FROM app.review_credit_charges WHERE review_id=CAST(? AS uuid) AND version_no=?",
                String.class,id,no);
            // A retained partial result is also terminal, with its reservation returned to the user.
            return "charged".equals(status) || "released".equals(status);
        }catch(Exception ignored){return false;}
    }
    boolean terminalFailure(String tenant,String id,int no){
        try{
            if(!"failed".equals(ReviewStore.version(store.get(tenant,id),no).path("status").asText()))return false;
            String status=store.jdbc().queryForObject(
                "SELECT status FROM app.review_credit_charges WHERE review_id=CAST(? AS uuid) AND version_no=?",
                String.class,id,no);
            return "released".equals(status);
        }catch(Exception ignored){return false;}
    }
    /** The queue will run the job again; the browser keeps its stream and shows the retry. */
    void retryScheduled(String id,int no,int attempt,int delaySeconds){
        events.publish(id,no,"retry_scheduled",json.createObjectNode().put("attempt",attempt).put("delaySeconds",delaySeconds));
    }
    JsonNode callAgent(String path,JsonNode body)throws Exception{return agent.post(path,body);}
    void fail(String tenant,String id,int no,String error){
        try{store.mutate(tenant,id,d->{var v=ReviewStore.version(d,no);v.put("status","failed").put("error",error);});}catch(Exception ignored){}
        try{credits.release(id,no);}catch(Exception ignored){}
        events.publish(id,no,"failed",json.createObjectNode().put("error",error));
    }
    void failQueued(String tenant,String id,int no){
        // Both operations are idempotent, so a redelivery can finish either one after a crash.
        credits.release(id,no);
        store.mutate(tenant,id,d->{var v=ReviewStore.version(d,no);
            v.put("status","failed").put("error","ANALYSIS_FAILED_REUPLOAD_REQUIRED");});
        purge(id,no);
        events.publish(id,no,"failed",json.createObjectNode().put("error","ANALYSIS_FAILED_REUPLOAD_REQUIRED"));
    }
    /** Redis는 분석 중 빠른 조회용 핫 캐시일 뿐이다. 원본은 app.review_assets.bytes에 영구 보존되므로
     * 이 호출은 캐시 메모리만 비우고 원본을 지우지 않는다. */
    void purge(String id,int no){for(var row:store.jdbc().queryForList("SELECT id FROM app.review_assets WHERE review_id=CAST(? AS uuid) AND version_no=?",id,no))cache.delete(assetKey(row.get("id").toString()));}
    String readCachedAsset(String id) { return cache.opsForValue().get(assetKey(id)); }
    /** 사용자 화면(위반 검토, 리포트)에서 보는 원본. 같은 기업 소유인지 확인한 뒤 영구 저장소에서 읽는다. */
    public record PersistedAsset(byte[] bytes,String mimeType){}
    PersistedAsset readPersistedAsset(String tenant,String reviewId,String assetId){
        var rows=store.jdbc().queryForList(
            "SELECT a.bytes,a.mime_type FROM app.review_assets a JOIN app.reviews r ON r.id=a.review_id "+
            "WHERE a.id=CAST(? AS uuid) AND a.review_id=CAST(? AS uuid) AND r.tenant_id=CAST(? AS uuid)",assetId,reviewId,tenant);
        if(rows.isEmpty() || rows.getFirst().get("bytes")==null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ASSET_NOT_FOUND");
        return new PersistedAsset((byte[])rows.getFirst().get("bytes"),rows.getFirst().get("mime_type").toString());
    }
    void authorizeRun(String id,int no,String runToken){
        var rows=store.jdbc().queryForList("SELECT token_hash FROM app.review_runs WHERE review_id=CAST(? AS uuid) AND version_no=?",id,no);
        String digest=hash(Objects.toString(runToken,"").getBytes(StandardCharsets.UTF_8));
        if(rows.size()!=1 || !MessageDigest.isEqual(rows.getFirst().get("token_hash").toString().getBytes(StandardCharsets.UTF_8),digest.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"RUN_SCOPE_MISMATCH");
    }
    @Override public void close(){jobs.shutdown();}
}
