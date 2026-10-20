package org.skala.capstone;

import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/v1/users")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AuthTokens tokens;
    private final JwtDecoder decoder;
    public AuthController(JdbcTemplate jdbc, PasswordEncoder passwords, AuthTokens tokens, JwtDecoder decoder) {
        this.jdbc=jdbc; this.passwords=passwords; this.tokens=tokens; this.decoder=decoder;
    }
    static String value(Map<String,String> data, String key, int min, int max) {
        String value=data.getOrDefault(key, "").trim();
        if(value.length()<min || value.length()>max)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_"+key.toUpperCase());
        return value;
    }
    @PostMapping("/signup") @Transactional
    public ResponseEntity<Map<String,Object>> signup(@RequestBody Map<String,String> input,
                                                      @CookieValue(name=AuthTokens.COOKIE,required=false) String previous) {
        String email=value(input,"email",3,200).toLowerCase(Locale.ROOT);
        if(!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_EMAIL");
        String password=value(input,"password",10,72);
        String name=value(input,"name",1,80), company=value(input,"tenantName",1,120);
        if(jdbc.queryForObject("SELECT count(*) FROM app.users WHERE email=?",Integer.class,email)>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"EMAIL_EXISTS");
        UUID tenant=UUID.randomUUID(), user=UUID.randomUUID();
        jdbc.update("INSERT INTO app.tenants VALUES (?,?)",tenant,company);
        jdbc.update("INSERT INTO app.users (id,tenant_id,email,name,password_hash,role) VALUES (?,?,?,?,?,?)",user,tenant,email,name,passwords.encode(password),"admin");
        tokens.revoke(previous,null);
        return issued(tokens.open(user.toString()));
    }
    @PostMapping("/login")
    public ResponseEntity<Map<String,Object>> login(@RequestBody Map<String,String> input,
                                                     @CookieValue(name=AuthTokens.COOKIE,required=false) String previous) {
        String email=value(input,"email",3,200).toLowerCase(Locale.ROOT);
        var users=jdbc.queryForList("SELECT id,password_hash FROM app.users WHERE email=?",email);
        if(users.size()!=1 || !passwords.matches(input.getOrDefault("password",""),users.getFirst().get("password_hash").toString()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"INVALID_LOGIN");
        // 같은 브라우저의 이전 세션은 쿠키가 덮어써지면 쓸 수 없으므로 함께 정리한다.
        tokens.revoke(previous,null);
        return issued(tokens.open(users.getFirst().get("id").toString()));
    }
    @PostMapping("/refresh")
    public ResponseEntity<Map<String,Object>> refresh(@CookieValue(name=AuthTokens.COOKIE,required=false) String refreshToken) {
        try { return issued(tokens.rotate(refreshToken)); }
        catch (ResponseStatusException error) {
            var response=ResponseEntity.status(error.getStatusCode());
            // ROTATED이면 다른 탭이 받은 새 쿠키가 이미 있으므로 지우지 않는다.
            if(!"REFRESH_TOKEN_ROTATED".equals(error.getReason()))
                response.header(HttpHeaders.SET_COOKIE,tokens.clearedCookie().toString());
            return response.body(Map.of("message",error.getReason()));
        }
    }
    private ResponseEntity<Map<String,Object>> issued(AuthTokens.Issued issued) {
        var body=new LinkedHashMap<>(identity(issued.userId()));
        body.put("accessToken",issued.accessToken()); body.put("expiresIn",issued.accessExpiresIn());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,issued.cookie().toString()).body(body);
    }
    private Map<String,Object> identity(String id) {
        var row=jdbc.queryForMap("SELECT u.*,t.name AS tenant_name FROM app.users u JOIN app.tenants t ON t.id=u.tenant_id WHERE u.id=CAST(? AS uuid)",id);
        return Map.of("user",Map.of("id",id,"tenantId",row.get("tenant_id").toString(),"name",row.get("name"),"email",row.get("email"),"role",row.get("role")),
            "tenant",Map.of("id",row.get("tenant_id").toString(),"name",row.get("tenant_name")));
    }
    @GetMapping("/me") public Map<String,Object> me(@AuthenticationPrincipal Jwt jwt) {return identity(jwt.getSubject());}
    /** Access Token이 만료돼도 로그아웃할 수 있도록 인증 없이 받고, 쿠키와 Bearer의 세션을 모두 폐기한다. */
    @DeleteMapping("/logout")
    public ResponseEntity<Map<String,Boolean>> logout(@CookieValue(name=AuthTokens.COOKIE,required=false) String refreshToken,
                                                      @RequestHeader(name=HttpHeaders.AUTHORIZATION,required=false) String authorization) {
        String session=null;
        if(authorization!=null && authorization.startsWith("Bearer ")) {
            try { session=decoder.decode(authorization.substring(7)).getClaimAsString("sid"); }
            catch (JwtException ignored) { }
        }
        tokens.revoke(refreshToken,session);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,tokens.clearedCookie().toString()).body(Map.of("ok",true));
    }
    @PostMapping("/team") @Transactional
    public Map<String,Boolean> team(@AuthenticationPrincipal Jwt jwt,@RequestBody Map<String,String> input) {
        if(!"admin".equals(jwt.getClaimAsString("role"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED");
        String role=value(input,"role",1,20);
        if(!Set.of("author","reviewer").contains(role)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_ROLE");
        String email=value(input,"email",3,200).toLowerCase(Locale.ROOT);
        jdbc.update("INSERT INTO app.users (id,tenant_id,email,name,password_hash,role) VALUES (?,CAST(? AS uuid),?,?,?,?)",UUID.randomUUID(),jwt.getClaimAsString("tenantId"),email,
            value(input,"name",1,80),passwords.encode(value(input,"password",10,72)),role);
        return Map.of("ok",true);
    }
}
