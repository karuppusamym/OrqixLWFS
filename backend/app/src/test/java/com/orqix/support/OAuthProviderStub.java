package com.orqix.support;

import com.github.tomakehurst.wiremock.WireMockServer;

/** Isolated OAuth provider transport; the procurement stub's global fault transformer must not rewrite token replies. */
public final class OAuthProviderStub {
    private static WireMockServer server;
    private OAuthProviderStub() {}
    public static synchronized WireMockServer server() {
        if(server==null) { server=new WireMockServer(0); server.start(); }
        return server;
    }
    public static int port() { return server().port(); }
}
