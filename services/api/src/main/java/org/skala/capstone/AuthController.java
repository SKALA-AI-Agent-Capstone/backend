package org.skala.capstone;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/v1/users")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    public AuthController(JdbcTemplate jdbc, PasswordEncoder passwords, JwtEncoder encoder) {
        this.jdbc=jdbc; this.passwords=passwords; this.encoder=encoder;
    }
    static String value(Map<String,String> data, String key, int min, int max) {
        String value=data.getOrDefault(key, "").trim();
        if(value.length()<min || value.length()>max)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_"+key.toUpperCase());
        return value;
    }
    @PostMapping("/signup") @Transactional
    public Map<String,Object> signup(@RequestBody Map<String,String> input) {
        String email=value(input,"email",3,200).toLowerCase(Locale.ROOT);
        if(!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_EMAIL");
        String password=value(input,"password",10,72);
        String name=value(input,"name",1,80), company=value(input,"tenantName",1,120);
        if(jdbc.queryForObject("SELECT count(*) FROM app.users WHERE email=?",Integer.class,email)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"EMAIL_EXISTS");
        UUID tenant=UUID.randomUUID(), user=UUID.randomUUID();
        jdbc.update("INSERT INTO app.tenants VALUES (?,?)",tenant,company);
        jdbc.update("INSERT INTO app.users VALUES (?,?,?,?,?,?)",user,tenant,email,name,passwords.encode(password),"admin");
        return token(user.toString());
    }
    @PostMapping("/login")
    public Map<String,Object> login(@RequestBody Map<String,String> input) {
        String email=value(input,"email",3,200).toLowerCase(Locale.ROOT);
        var users=jdbc.queryForList("SELECT id,password_hash FROM app.users WHERE email=?",email);
        if(users.size()!=1 || !passwords.matches(input.getOrDefault("password",""),users.getFirst().get("password_hash").toString()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"INVALID_LOGIN");
        return token(users.getFirst().get("id").toString());
    }
    private Map<String,Object> token(String userId) {
        Map<String,Object> identity=identity(userId);
        @SuppressWarnings("unchecked") var user=(Map<String,Object>)identity.get("user");
        UUID jti=UUID.randomUUID(); Instant now=Instant.now(), expiry=now.plusSeconds(7200);
        jdbc.update("INSERT INTO app.sessions VALUES (?,CAST(? AS uuid),?)",jti,userId,java.sql.Timestamp.from(expiry));
        var claims=JwtClaimsSet.builder().issuer("beauty-capstone").subject(userId).id(jti.toString())
            .issuedAt(now).expiresAt(expiry).claim("tenantId",user.get("tenantId")).claim("role",user.get("role")).build();
        String encoded=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
        var result=new LinkedHashMap<>(identity); result.put("accessToken",encoded); return result;
    }
    private Map<String,Object> identity(String id) {
        var row=jdbc.queryForMap("SELECT u.*,t.name AS tenant_name FROM app.users u JOIN app.tenants t ON t.id=u.tenant_id WHERE u.id=CAST(? AS uuid)",id);
        return Map.of("user",Map.of("id",id,"tenantId",row.get("tenant_id").toString(),"name",row.get("name"),"email",row.get("email"),"role",row.get("role")),
            "tenant",Map.of("id",row.get("tenant_id").toString(),"name",row.get("tenant_name")));
    }
    @GetMapping("/me") public Map<String,Object> me(@AuthenticationPrincipal Jwt jwt) {return identity(jwt.getSubject());}
    @DeleteMapping("/logout") public Map<String,Boolean> logout(@AuthenticationPrincipal Jwt jwt) {
        jdbc.update("DELETE FROM app.sessions WHERE id=CAST(? AS uuid)",jwt.getId()); return Map.of("ok",true);
    }
    @PostMapping("/team") @Transactional
    public Map<String,Boolean> team(@AuthenticationPrincipal Jwt jwt,@RequestBody Map<String,String> input) {
        if(!"admin".equals(jwt.getClaimAsString("role"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED");
        String role=value(input,"role",1,20);
        if(!Set.of("author","reviewer").contains(role)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_ROLE");
        String email=value(input,"email",3,200).toLowerCase(Locale.ROOT);
        jdbc.update("INSERT INTO app.users VALUES (?,CAST(? AS uuid),?,?,?,?)",UUID.randomUUID(),jwt.getClaimAsString("tenantId"),email,
            value(input,"name",1,80),passwords.encode(value(input,"password",10,72)),role);
        return Map.of("ok",true);
    }
}
