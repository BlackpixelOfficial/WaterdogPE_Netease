package dev.waterdog.waterdogpe.network.netease.protocol;

import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.List;
import java.util.Map;

import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.bedrock.util.JsonUtils;
import org.jose4j.json.JsonUtil;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwa.AlgorithmConstraints.ConstraintType;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.lang.JoseException;


public class NeteaseEncryptionUtils {
    // netease的publickey
    private static final ECPublicKey NETEASE_PUBLIC_KEY;
    private static final AlgorithmConstraints ALGORITHM_CONSTRAINTS;

    static {
        ALGORITHM_CONSTRAINTS = new AlgorithmConstraints(ConstraintType.PERMIT, new String[] { "ES384" });
        try {
            NETEASE_PUBLIC_KEY = EncryptionUtils.parseKey("MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEEsmU+IF/XeAF3yiqJ7Ko36btx6JtdB26wV9Eyw4AYR/nmesznkfXxwQ4B0NkSnGIZccbb2f3nFUYughKSoAcNHx+lQm8F9h9RwhrNgeN907z06LUA2AqWcwqasxyaU0E");
        } catch (InvalidKeySpecException | NoSuchAlgorithmException var2) {
            throw new AssertionError("Unable to initialize required encryption", var2);
        }
    }

    public static ChainValidationResult validatePayload(CertificateChainPayload chainPayload)
            throws JoseException, NoSuchAlgorithmException, InvalidKeySpecException, InvalidJwtException {
        List<String> chain = chainPayload.getChain();
        if (chain != null && !chain.isEmpty()) {
            return validateChain(chain);
        } else {
            throw new IllegalStateException("Certificate chain is empty");
        }
    }

    public static ChainValidationResult validateChain(List<String> chain)
            throws JoseException, NoSuchAlgorithmException, InvalidKeySpecException {
        switch (chain.size()) {
            case 1:
                JsonWebSignature identity = new JsonWebSignature();
                identity.setCompactSerialization((String) chain.get(0));
                return new ChainValidationResult(false, identity.getUnverifiedPayload());
            case 3:
                ECPublicKey currentKey = null;
                Map<String, Object> parsedPayload = null;

                for (int i = 0; i < 3; ++i) {
                    JsonWebSignature signature = new JsonWebSignature();
                    signature.setCompactSerialization((String) chain.get(i));
                    ECPublicKey expectedKey = EncryptionUtils.parseKey(signature.getHeader("x5u"));
                    if (currentKey == null) {
                        currentKey = expectedKey;
                    } else if (!currentKey.equals(expectedKey)) {
                        throw new IllegalStateException("Received broken chain");
                    }

                    signature.setAlgorithmConstraints(ALGORITHM_CONSTRAINTS);
                    signature.setKey(currentKey);
                    if (!signature.verifySignature()) {
                        throw new IllegalStateException("Chain signature doesn't match content");
                    }

                    if (i == 1 && !currentKey.equals(NETEASE_PUBLIC_KEY)) {
                        throw new IllegalStateException("The chain isn't signed by Netease!");
                    }

                    parsedPayload = JsonUtil.parseJson(signature.getUnverifiedPayload());
                    String identityPublicKey = (String) JsonUtils.childAsType(parsedPayload, "identityPublicKey",
                            String.class);
                    currentKey = EncryptionUtils.parseKey(identityPublicKey);
                }

                return new ChainValidationResult(true, parsedPayload);
            default:
                throw new IllegalStateException("Unexpected login chain length");
        }
    }

}
