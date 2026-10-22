package org.skala.capstone.review;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.skala.capstone.auth.UserRepository;
import org.skala.capstone.billing.CreditLedgerService;
import org.skala.capstone.review.queue.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewService {
    private final ReviewRepository store;
    private final ReviewAssetService assetsService;
    private final RunTokenRepository tokens;
    private final UserRepository users;
    private final JobRepository jobs;
    private final ReviewRunner runner;
    private final CreditLedgerService credits;
    private final ObjectMapper json;
    private final boolean queueEnabled;

    public ReviewService(ReviewRepository store,ReviewAssetService assetsService,RunTokenRepository tokens,
            UserRepository users,JobRepository jobs,ReviewRunner runner,CreditLedgerService credits,ObjectMapper json,
            @Value("${capstone.review.queue-enabled:false}")boolean queueEnabled) {
        this.store=store;this.assetsService=assetsService;this.tokens=tokens;this.users=users;this.jobs=jobs;
        this.runner=runner;this.credits=credits;this.json=json;this.queueEnabled=queueEnabled;
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
        doc.put("authorName",users.name(jwt.getSubject()));
        doc.put("productName",meta.path("product").path("name").asText());doc.putNull("campaign");doc.putArray("versions");
        store.create(id,tenant,jwt.getSubject(),doc);
        List<MultipartFile> all=new ArrayList<>(files);all.addAll(evidence);
        if(all.stream().mapToLong(MultipartFile::getSize).sum()>75L*1024*1024)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"TOTAL_LIMIT_75_MIB");
        // This transaction also owns the review/version insert. Invalid uploads roll back the reservation.
        credits.reserve(tenant,jwt.getSubject(),id,no);
        ArrayNode ads=json.createArrayNode(),docs=json.createArrayNode(),assets=json.createArrayNode();
        try{
            for(int i=0;i<all.size();i++){
                MultipartFile file=all.get(i);
                if(file.isEmpty() || file.getSize()>25L*1024*1024)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"FILE_LIMIT_25_MIB");
                byte[] raw=file.getBytes();String contentType=assetsService.mime(raw),assetId=UUID.randomUUID().toString();
                String name=Objects.toString(file.getOriginalFilename(),"upload").replaceAll("[\\r\\n\\\\/]","_");
                if(name.length()>200)name=name.substring(name.length()-200);
                String role=i<files.size()?"advertisement":"evidence";
                String docType=i<files.size()?"":meta.path("evidence").path(i-files.size()).path("docType").asText("other");
                assetsService.store(assetId,id,no,name,contentType,role,docType,raw);
                ObjectNode asset=json.createObjectNode().put("id",assetId).put("name",name).put("size",raw.length).put("mime",contentType).put("role",role).put("docType",docType)
                    .put("kind",contentType.equals("application/pdf")?"detail_page":"image");
                assets.add(asset);if(i<files.size())ads.add(asset.deepCopy());else docs.add(asset.deepCopy());
            }
            ObjectNode input=json.createObjectNode();input.set("product",meta.path("product"));input.set("media",meta.path("media").isArray()?meta.path("media"):json.createArrayNode());
            input.put("copy","");input.set("files",ads);input.set("evidence",docs);
            ObjectNode version=json.createObjectNode().put("no",no).put("authorId",jwt.getSubject()).put("authorName",doc.path("authorName").asText()).put("createdAt",Instant.now().toString()).put("status","queued");
            version.set("input",input);version.set("steps",json.valueToTree(List.of(0,0,0,0,0)));version.putNull("result");version.putNull("error");version.putNull("originalsDiscardedAt");
            String runToken=UUID.randomUUID().toString()+UUID.randomUUID();
            tokens.create(id,no,ReviewAssetService.hash(runToken.getBytes(StandardCharsets.UTF_8)));
            store.mutate(tenant,id,d->{d.withArray("versions").add(version);d.put("productName",input.path("product").path("name").asText());});
            ObjectNode request=json.createObjectNode().put("reviewId",id).put("tenantId",tenant).put("versionNo",no).put("runToken",runToken);
            request.set("assets",assets);request.set("product",input.path("product"));
            if(queueEnabled){
                // The job and credit reservation commit together. The dispatcher publishes it later.
                jobs.create(id,no,request.toString());
            }else{
                // Local tests and standalone development retain the bounded in-process executor.
                runner.afterCommit(request);
            }
            return store.get(tenant,id);
        }catch(Exception e){if(e instanceof ResponseStatusException status)throw status;
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"UPLOAD_STORAGE_FAILED");}
    }
    public Map<String,Boolean> delete(Jwt jwt,String id){
        String tenant=jwt.getClaimAsString("tenantId");var doc=store.get(tenant,id);
        if(!doc.path("authorId").asText().equals(jwt.getSubject())&&!"admin".equals(jwt.getClaimAsString("role")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"DELETE_FORBIDDEN");
        if(Set.of("queued","analyzing").contains(ReviewRepository.latest(doc).path("status").asText()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"WAIT_FOR_ANALYSIS_BEFORE_DELETE");
        try{
            runner.callAgent("/internal/runs/delete",store.mapper().createObjectNode().put("reviewId",id).put("tenantId",tenant)
                .put("versionNo",ReviewRepository.latest(doc).path("no").asInt()).put("state","rejected"));
        }catch(Exception error){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"CHECKPOINT_DELETE_PENDING_RETRY");}
        for(JsonNode v:doc.path("versions"))assetsService.purge(id,v.path("no").asInt());
        store.delete(tenant,id);
        return Map.of("ok",true);
    }
    public Map<String,Integer> deleteAll(Jwt jwt){
        int count=0;for(var doc:store.list(jwt.getClaimAsString("tenantId")))
            if("admin".equals(jwt.getClaimAsString("role"))||doc.path("authorId").asText().equals(jwt.getSubject())){delete(jwt,doc.path("id").asText());count++;}
        return Map.of("deleted",count);
    }
}
