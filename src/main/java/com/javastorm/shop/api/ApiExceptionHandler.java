package com.javastorm.shop.api;

import com.javastorm.shop.service.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/** 接口的错误统一返回 JSON（{@link ApiError}），业务错误按请求的语言显示。 */
@RestControllerAdvice(basePackages = "com.javastorm.shop.api")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final MessageSource messages;

    public ApiExceptionHandler(MessageSource messages) {
        this.messages = messages;
    }

    /** 业务规则不允许（库存不足、SKU 重复等）：422。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> business(BusinessException e) {
        String msg = e.getCode() == null ? e.getMessage()
                : messages.getMessage(e.getCode(), e.getArgs(), e.getMessage(), LocaleContextHolder.getLocale());
        return ResponseEntity.unprocessableEntity().body(ApiError.of(msg));
    }

    @ExceptionHandler(ApiValidationException.class)
    public ResponseEntity<ApiError> validation(ApiValidationException e) {
        return ResponseEntity.badRequest().body(new ApiError(e.getMessage(), e.getFields()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(new ApiError("提交的内容有误，请检查", fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ApiError> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(ApiError.of("请求格式不正确：" + e.getMessage()));
    }

    /** 同一条数据刚被别的操作改过（例如编辑商品时有新订单扣了库存）：409。 */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("数据刚被其他操作修改过，请刷新后重试"));
    }

    @ExceptionHandler(ApiExceptionHandler.NotFoundException.class)
    public ResponseEntity<ApiError> notFound(ApiExceptionHandler.NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(e.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(ApiError.of(e.getReason() == null ? "请求失败" : e.getReason()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of("上传的文件太大（单个图片最大 10MB，单次合计最大 50MB）"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("接口出错", e);
        return ResponseEntity.internalServerError().body(ApiError.of("服务器出错了，请稍后再试"));
    }

    /** 找不到资源：404。 */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
