package com.elioo.healthcare.core.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Slf4j
public class TracerUtil {

//    private final Tracer tracer;
//    private final TransactionRepositoryService transactionRepositoryService;

//    public TracerUtil(Tracer tracer) {
//        this.tracer = tracer;
//    }

    public String getCurrentTraceId() {
        /*Span span =  tracer.currentSpan();
        if (span == null) {
            log.info("Sleuth Span is Null !");
            return "";
        }
        return span.context().traceId();*/
        return String.valueOf(UUID.randomUUID());
    }

    /*public String getTraceId(String refId) throws ExceptionHandlerUtil {
        String traceID =  transactionRepositoryService.getByOid(refId).getTraceId();
        if (traceID == null || traceID.isEmpty())
            throw new ExceptionHandlerUtil(HttpStatus.NOT_FOUND, Messages.NO_TRACE_ID_FOUND_WITH_TRANSACTION_ID);
        return traceID;
    }*/

    public String getReqId() {
        return String.valueOf(UUID.randomUUID());
    }

    /*public String getReqId(String refId) throws ExceptionHandlerUtil {
        String reqId =  transactionRepositoryService.getByOid(refId).getRequestId();
        if (reqId == null || reqId.isEmpty())
            throw new ExceptionHandlerUtil(HttpStatus.NOT_FOUND, Messages.NO_TRACE_ID_FOUND_WITH_TRANSACTION_ID);
        return  reqId;
    }*/
}
