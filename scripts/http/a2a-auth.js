// IntelliJ HTTP Client pre-request script. No secrets or full tokens are logged.
// Uses the documented crypto.hmac API; no Node.js modules or server token endpoint.
const secret = request.environment.get("a2aServiceSecret");
if (!secret || secret.startsWith("REPLACE_")) {
    throw new Error("Copy http-client.private.env.json.example to http-client.private.env.json and configure a2aServiceSecret.");
}
const secretBytes = encodeURIComponent(secret).replace(/%[0-9A-F]{2}/gi, "x").length;
if (secretBytes < 32) throw new Error("a2aServiceSecret must contain at least 32 UTF-8 bytes.");
const match = request.url.tryGetSubstituted().match(/\/api\/a2a\/(order-logistics|policy|after-sales)(?:\/|$)/);
if (!match) throw new Error("Expected an approved A2A agent URL.");
const conversation = client.global.get("protocolConversation");
const tenant = Number(request.environment.get("tenantId"));
if (!conversation || !Number.isSafeInteger(tenant) || tenant <= 0) {
    throw new Error("Run request 01 to create a conversation, and select environment a2a-local.");
}
const now = Date.now();
const nonce = now + "-" + Math.random().toString(16).slice(2);
const algorithm = secretBytes >= 64 ? "HS512" : secretBytes >= 48 ? "HS384" : "HS256";
const base64url = value => btoa(JSON.stringify(value)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const header = base64url({alg: algorithm, typ: "JWT"});
const payload = base64url({
    iss: "omnimerchant-a2a", sub: "supervisor", aud: [match[1]],
    tenant, conversation, scope: "task:read task:write",
    iat: Math.floor(now / 1000), exp: Math.floor(now / 1000) + 90, jti: nonce
});
const mac = algorithm === "HS512" ? crypto.hmac.sha512() : algorithm === "HS384" ? crypto.hmac.sha384() : crypto.hmac.sha256();
const signature = mac.withTextSecret(secret).updateWithText(header + "." + payload).digest().toBase64(true).replace(/=+$/, "");
request.variables.set("serviceToken", header + "." + payload + "." + signature);
request.variables.set("deadlineEpochMs", now + 70000);
request.variables.set("rpcId", "rpc-" + nonce);
request.variables.set("messageId", "message-" + nonce);
