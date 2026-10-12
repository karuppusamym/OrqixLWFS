package com.orqix.api;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.support.IntegrationTestBase;
import com.orqix.support.Stub;
import tools.jackson.databind.node.ObjectNode;

class DocumentSourcesApiTest extends IntegrationTestBase {
    @Autowired OrqixProperties properties;
    @Autowired JdbcClient jdbc;

    @Test void explicitPermissionAndResourceAudienceAreRequiredAndRevokedImmediately() throws Exception {
        String key = "docs-" + UUID.randomUUID().toString().substring(0,8);
        String secretRef = "docs-source-stub";
        Path secret = properties.secrets().dir().resolve(secretRef); Files.createDirectories(secret.getParent()); Files.writeString(secret,"graph-stub-only");
        String grantId = null;
        try {
            var definition = (ObjectNode) json.parse(Files.readString(Path.of("../../examples/connections/microsoft-documents-v4.json")));
            definition.put("key",key).put("version",1);
            for (var op : definition.path("operations")) {
                var props = (ObjectNode) op.path("requestSchema").path("properties");
                if (props.has("driveId")) ((ObjectNode) props.get("driveId")).set("enum",json.parse("[\"drive\"]"));
                if (props.has("folderId")) ((ObjectNode) props.get("folderId")).set("enum",json.parse("[\"folder\"]"));
            }
            assertThat(api("wade.admin").post(Map.of("definition",definition),"/workspaces/{ws}/connections/{key}/versions",PURCHASING,key).status()).isEqualTo(201);
            assertThat(api("wade.admin").put(Map.of("baseUrl",Stub.baseUrl()+"/graph","enabled",true,"auth",Map.of("type","bearerToken","secretRef",secretRef)),null,"/workspaces/{ws}/connections/{key}/binding",PURCHASING,key).status()).isEqualTo(200);
            UUID id = UUID.randomUUID();
            var source = new java.util.LinkedHashMap<String,Object>();
            source.put("label","Approved policy folder");source.put("connectionKey",key);source.put("definitionVersion",1);source.put("browseOperation","browseFolder");source.put("versionsOperation","listVersions");source.put("driveId","drive");source.put("folderId","folder");source.put("audience",List.of(Map.of("type","idpGroup","value","orqix-purchasing-requesters")));source.put("enabled",true);source.put("expectedRevision",0);
            assertThat(api("wade.admin").put(source,null,"/workspaces/{ws}/document-sources/grants/{id}",PURCHASING,id).status()).isEqualTo(200);
            assertThat(api("rhea.requester").get("/workspaces/{ws}/document-sources",PURCHASING).status()).isEqualTo(403);
            var grant = api("wade.admin").post(Map.of("permission","document.browse","principal",Map.of("type","idpGroup","value","orqix-purchasing-requesters")),"/workspaces/{ws}/access/grants",PURCHASING);
            assertThat(grant.status()).isEqualTo(201); grantId=grant.body().path("id").asString();
            var listed = api("rhea.requester").get("/workspaces/{ws}/document-sources",PURCHASING);
            assertThat(listed.status()).isEqualTo(200); assertThat(listed.body().toString()).contains(id.toString()).doesNotContain("secretRef","baseUrl","audience","graph-stub-only");
            var files = api("rhea.requester").get("/workspaces/{ws}/document-sources/{id}/files",PURCHASING,id);
            assertThat(files.status()).isEqualTo(200); assertThat(files.body().toString()).contains("policy.txt").doesNotContain("secret-not-returned");
            var versions = api("rhea.requester").get("/workspaces/{ws}/document-sources/{id}/files/{item}/versions",PURCHASING,id,"item");
            assertThat(versions.status()).isEqualTo(200); assertThat(versions.body().path("versionCount").asInt()).isEqualTo(2);
            assertThat(api("rhea.requester").get("/workspaces/{ws}/document-sources/{id}/files/{item}/versions",PURCHASING,id,"private").status()).isEqualTo(404);
            assertThat(api("rhea.requester").get("/workspaces/{ws}/document-sources/{id}/files",SANDBOX,id).status()).isEqualTo(403);
            source.put("expectedRevision",1); source.put("enabled",false);
            assertThat(api("wade.admin").put(source,null,"/workspaces/{ws}/document-sources/grants/{id}",PURCHASING,id).status()).isEqualTo(200);
            assertThat(api("rhea.requester").get("/workspaces/{ws}/document-sources/{id}/files",PURCHASING,id).status()).isEqualTo(404);
            assertThat(api("wade.admin").delete("/workspaces/{ws}/access/grants/{id}",PURCHASING,grantId).status()).isEqualTo(204); grantId=null;
            assertThat(api("rhea.requester").get("/workspaces/{ws}/document-sources",PURCHASING).status()).isEqualTo(403);
        } finally {
            if(grantId!=null) api("wade.admin").delete("/workspaces/{ws}/access/grants/{id}",PURCHASING,grantId);
            Files.deleteIfExists(secret);
        }
    }
}
