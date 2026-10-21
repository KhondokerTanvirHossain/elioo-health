package com.elioo.baymax.web.application.port.in;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.web.domain.VerifyFailure;
import org.springframework.http.HttpStatus;

/**
 * A refused code. Status, reason and message are identical for every {@link VerifyFailure}, so the JSON API under
 * {@code /api/v1/baymax} cannot be used to learn which. The HTML sign-in page reads {@link #failure()} to say what
 * happened and what to do, and the browser's sign-in JSON ({@code POST /app/api/sign-in/verify}, Wave 2 plan B4a)
 * names it as a {@code CODE_*} reason exactly as finely as that page's copy and no finer: WRONG and NO_CODE are one
 * name there, as they are one sentence on the page ({@code SignInApiHandlerTest.everyJsonCodeRefusalIsTheHtmlPagesRefusal}).
 * That reverses this exception's earlier "never which" for the browser JSON only; the page already tells the visitor.
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
