package com.orqix.identity.cli;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import com.orqix.identity.Caller;

/** A person signed in through the command line tool (S148): the {@link Caller} resolved from their access token. */
public final class CliAuthentication extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;
    private final transient Caller caller;

    public CliAuthentication(Caller caller) {
        super(List.of());
        this.caller = caller;
        setAuthenticated(true);
    }

    public Caller caller() {
        return caller;
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return caller.subjectId().toString();
    }
}
