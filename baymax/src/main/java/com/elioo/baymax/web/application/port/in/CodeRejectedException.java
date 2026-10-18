package com.elioo.baymax.web.application.port.in;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.domain.VerifyFailure;
import org.springframework.http.HttpStatus;

/**
 * A refused code. Status, reason and message are identical for every {@link VerifyFailure}, so the API cannot be
 * used to learn which; the web page reads {@link #failure()} to say what happened and what to do.
 */
public class CodeRejectedException extends BaymaxException {

    private final VerifyFailure failure;

    public CodeRejectedException(VerifyFailure failure) {
        super(HttpStatus.UNAUTHORIZED, "invalid_code", "the code is wrong, expired or used");
        this.failure = failure;
    }

    public VerifyFailure failure() {
        return failure;
    }
}
