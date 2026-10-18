ALTER TABLE connection_binding DROP CONSTRAINT connection_binding_auth_type_check;
ALTER TABLE connection_binding ADD CONSTRAINT connection_binding_auth_type_check
    CHECK (auth_type IN ('none','bearerToken','apiKeyHeader','oauth2ClientCredentials'));
ALTER TABLE connection_binding DROP CONSTRAINT connection_binding_auth_shape;
ALTER TABLE connection_binding ADD CONSTRAINT connection_binding_auth_shape CHECK (
    (auth_type = 'none' AND secret_ref IS NULL AND auth_header_name IS NULL)
 OR (auth_type IN ('bearerToken','oauth2ClientCredentials') AND secret_ref IS NOT NULL AND auth_header_name IS NULL)
 OR (auth_type = 'apiKeyHeader' AND secret_ref IS NOT NULL AND auth_header_name IS NOT NULL));
