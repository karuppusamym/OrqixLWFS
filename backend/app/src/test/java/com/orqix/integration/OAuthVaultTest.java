package com.orqix.integration;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.json.Json;
import com.orqix.shared.problem.ApiException;
import tools.jackson.databind.json.JsonMapper;

class OAuthVaultTest {
    @TempDir Path directory;
    private final Json json=new Json(JsonMapper.builder().build());
    private OAuthVault vault() {
        return new OAuthVault(new OrqixProperties(null,null,null,null,new OrqixProperties.Secrets(directory),null,null,null,null),json);
    }
    private void ring(String active,boolean old) throws Exception {
        var ring=json.object().put("active",active); var keys=ring.putObject("keys");
        if(old) keys.put("first",Base64.getEncoder().encodeToString(new byte[32]));
        byte[] next=new byte[32]; java.util.Arrays.fill(next,(byte)7);
        keys.put("second",Base64.getEncoder().encodeToString(next));
        Files.writeString(directory.resolve("oauth-token-keyring"),json.write(ring));
    }
    @Test void authenticatesTheWorkspaceAndPurposeAndUsesRandomNonces() throws Exception {
        assertThat(new DelegatedOAuth.Finish("private-state","private-code").toString())
                .doesNotContain("private-state","private-code");
        ring("first",true); var vault=vault();
        var first=vault.seal("workspace-one:grant","private-refresh-token");
        var second=vault.seal("workspace-one:grant","private-refresh-token");
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext()).doesNotContain("private-refresh-token");
        assertThat(vault.open("workspace-one:grant",first.keyId(),first.ciphertext())).isEqualTo("private-refresh-token");
        assertThatThrownBy(()->vault.open("workspace-two:grant",first.keyId(),first.ciphertext())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->vault.open("workspace-one:state",first.keyId(),first.ciphertext())).isInstanceOf(ApiException.class);
        byte[] changed=Base64.getDecoder().decode(first.ciphertext()); changed[15]^=1;
        assertThatThrownBy(()->vault.open("workspace-one:grant",first.keyId(),Base64.getEncoder().encodeToString(changed)))
                .isInstanceOf(ApiException.class);
    }
    @Test void supportsKeyRotationAndFailsClosedWhenAnOldKeyIsRemoved() throws Exception {
        ring("first",true); var vault=vault(); var old=vault.seal("context","token");
        ring("second",true);
        assertThat(vault.open("context",old.keyId(),old.ciphertext())).isEqualTo("token");
        assertThat(vault.seal("context","token").keyId()).isEqualTo("second");
        ring("second",false);
        assertThatThrownBy(()->vault.open("context",old.keyId(),old.ciphertext())).isInstanceOf(ApiException.class);
        Files.delete(directory.resolve("oauth-token-keyring"));
        assertThatThrownBy(()->vault.seal("context","token")).isInstanceOf(ApiException.class);
    }
}
