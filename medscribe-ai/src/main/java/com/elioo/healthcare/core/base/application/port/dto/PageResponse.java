package com.elioo.healthcare.core.base.application.port.dto;

import com.elioo.healthcare.core.util.CommonFunctions;
import lombok.*;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageResponse<T> extends ApiResponse<List<T>> {
    private long totalElements;
    private int totalPages;
    private int page;
    private int size;

    public PageResponse(String userMessage, long totalElements, int totalPages, int statusCode, int page, int size, List<T> data) {
        setUserMessage(userMessage);
        setStatusCode(statusCode);
        setData(data);
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.page = page;
        this.size = size;
    }

    @Override
    public String toString() {
        return CommonFunctions.buildGsonBuilder(this);
    }
}
