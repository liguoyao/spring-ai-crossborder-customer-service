package com.omnimerchant.agent.a2a;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/** Separate signing key and audience from both admin and widget identities. */
@Component
public class A2aIdentity {
    public record Scope(long tenant, String conversation, String agent, String caller) {
    }

    private final A2aConfiguration.Properties properties;

    public A2aIdentity(A2aConfiguration.Properties properties) {
        this.properties = properties;
    }

    private byte[] key() {
        var bytes = properties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("A2A_SERVICE_SECRET must contain at least 32 UTF-8 bytes");
        }
        return bytes;
    }

    public String issue(long tenant, String conversation, String agent) {
        return Jwts.builder().issuer("omnimerchant-a2a").subject("supervisor").audience().add(agent).and()
            .claim("tenant", tenant).claim("conversation", conversation).claim("scope", "task:read task:write")
            .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 90_000))
            .id(UUID.randomUUID().toString()).signWith(Keys.hmacShaKeyFor(key())).compact();
    }

    public Scope verify(String authorization, String agent) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new SecurityException("Service token required");
        }
        try {
            var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(key())).requireIssuer("omnimerchant-a2a")
                .requireSubject("supervisor").requireAudience(agent).build()
                .parseSignedClaims(authorization.substring(7)).getPayload();
            var tenant = ((Number)claims.get("tenant")).longValue();
            var conversation = claims.get("conversation", String.class);
            if (tenant <= 0 || conversation == null || conversation.isBlank() || !"task:read task:write".equals(
                claims.get("scope"))) {
                throw new SecurityException("Invalid service scope");
            }
            return new Scope(tenant, conversation, agent, claims.getSubject());
        } catch (Exception e) {
            throw new SecurityException("Invalid A2A service token");
        }
    }

    /** Encrypt persisted task snapshots: order facts and identity must not appear in replay summaries. */
    public String encrypt(String text) {
        return crypt(text, true);
    }

    public String decrypt(String text) {
        return crypt(text, false);
    }

    private String crypt(String text, boolean encrypt) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256").digest(key());
            var aes = new SecretKeySpec(digest, "AES");
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            if (encrypt) {
                var iv = new byte[12];
                new SecureRandom().nextBytes(iv);
                cipher.init(Cipher.ENCRYPT_MODE, aes, new GCMParameterSpec(128, iv));
                var body = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
                var packed = java.nio.ByteBuffer.allocate(iv.length + body.length).put(iv).put(body).array();
                return Base64.getEncoder().encodeToString(packed);
            }
            var packed = Base64.getDecoder().decode(text);
            cipher.init(Cipher.DECRYPT_MODE, aes, new GCMParameterSpec(128, Arrays.copyOf(packed, 12)));
            return new String(cipher.doFinal(Arrays.copyOfRange(packed, 12, packed.length)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("A2A snapshot encryption failed", e);
        }
    }
}
