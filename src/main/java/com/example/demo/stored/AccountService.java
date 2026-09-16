package com.example.demo.stored;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static com.example.demo.stored.Contracts.*;

@Service
public class AccountService {
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;
    private static final long SESSION_SECONDS = 3600;

    public AccountService(JdbcTemplate db, PasswordEncoder encoder, PlatformTransactionManager tx) {
        this.db = db; this.encoder = encoder;
        this.transactions = new TransactionTemplate(tx);
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }
    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String email(String email) { return email.strip().toLowerCase(Locale.ROOT); }
    private void passwordLength(String value) {
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        if (length < 12 || length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be 12 to 72 UTF-8 bytes");
    }
    public Session login(Login request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        Session session = transactions.execute(tx -> {
            var rows = db.queryForList("SELECT * FROM app_users WHERE email=? FOR UPDATE", email(request.email()));
            if (rows.isEmpty()) {
                encoder.matches(request.password(), dummyHash);
                return null;
            }
            var row = rows.get(0);
            UUID id = (UUID) row.get("id");
            var lock = db.queryForObject("SELECT COUNT(*) FROM app_users WHERE id=? AND locked_until > CURRENT_TIMESTAMP", Long.class, id);
            if (!Boolean.TRUE.equals(row.get("active")) || lock > 0) return null;
            if (!encoder.matches(request.password(), (String)row.get("password_hash"))) {
                int failures = ((Number)row.get("failed_logins")).intValue()+1;
                db.update("UPDATE app_users SET failed_logins=?, locked_until=? WHERE id=?",
                    failures >= 5 ? 0 : failures,
                    failures >= 5 ? Timestamp.from(Instant.now().plusSeconds(900)) : null, id);
                return null; // Commit failed-login counters before reporting 401.
            }
            db.update("UPDATE app_users SET failed_logins=0, locked_until=NULL WHERE id=?", id);
            db.update("DELETE FROM auth_sessions WHERE expires_at <= CURRENT_TIMESTAMP OR user_id=?", id);
            byte[] bytes = new byte[32]; random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            db.update("INSERT INTO auth_sessions(token_hash,user_id,expires_at) VALUES (?,?,?)",
                hash(token), id, Timestamp.from(Instant.now().plusSeconds(SESSION_SECONDS)));
            User user = new User(id, (UUID)row.get("company_id"), (String)row.get("email"), (String)row.get("role"));
            return new Session(token, "Bearer", SESSION_SECONDS, user);
        });
        if (session == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        return session;
    }
    public User authenticate(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
            throw new BadCredentialsException("Invalid session");
        var users = db.query("""
            SELECT u.id,u.company_id,u.email,u.role FROM auth_sessions s
            JOIN app_users u ON u.id=s.user_id
            WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP AND u.active=TRUE
            """, (r,n)->new User(r.getObject("id",UUID.class), r.getObject("company_id",UUID.class),
                r.getString("email"),r.getString("role")), hash(token));
        if (users.size()!=1) throw new BadCredentialsException("Invalid session");
        return users.get(0);
    }
    public void logout(String token) { db.update("DELETE FROM auth_sessions WHERE token_hash=?", hash(token)); }
    public User createUser(UUID company, NewUser request) {
        passwordLength(request.password());
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO app_users(id,company_id,email,password_hash,role) VALUES (?,?,?,?,?)",
            id, company, email(request.email()), encoder.encode(request.password()), request.role().name());
        return new User(id,company,email(request.email()),request.role().name());
    }
    public UUID bootstrap(String code, String name, String email, String password) {
        if (code == null || !code.matches("[a-zA-Z0-9_-]{1,80}") || name == null || name.isBlank() || name.length()>200
            || email == null || !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || email.length()>254)
            throw new IllegalArgumentException("Valid bootstrap company code, name, email and password are required");
        passwordLength(password);
        return transactions.execute(tx -> {
            UUID company=UUID.randomUUID();
            db.update("INSERT INTO companies(id,code,name) VALUES (?,?,?)",company,code,name);
            createUser(company,new NewUser(email,password,Role.ADMIN));
            return company;
        });
    }
}

