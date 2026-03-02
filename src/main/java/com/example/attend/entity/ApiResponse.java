package com.example.attend.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
@AllArgsConstructor
public class ApiResponse<T> {
    private Boolean success;
    private String code;
    private String message;
    private T data;

    public static ApiResponse<Void> success() {
        return ApiResponse.<Void>builder()
                .success(true)
                .code("SUCCESS")
                .message("요청 성공")
                .data(null)
                .build();
    }

    public static <T> ApiResponse<T> fails(T m) {
        return ApiResponse.<T>builder()
                .success(true)
                .code("SUCCESS")
                .message("요청 성공")
                .data(m)
                .build();
    }

    public static ApiResponse<Void> fail(String m) {
        return ApiResponse.<Void>builder()
                .success(false)
                .code("FAIL")
                .message(m)
                .build();
    }
}
