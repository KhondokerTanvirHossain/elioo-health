package com.elioo.baymax.web.domain;

/** A web request for a code: the HMAC the code step keeps in its cookie, and what the request came to. */
public record CodeRequest(String phoneHash, CodeIssue issue) {

    public boolean sent() {
        return issue == CodeIssue.SENT;
    }
}
