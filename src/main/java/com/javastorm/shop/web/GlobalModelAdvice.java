package com.javastorm.shop.web;

import com.javastorm.shop.config.LocaleConfig;
import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.payment.StripePaymentService;
import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.Cart;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;

/** 只作用于网页（web 包），接口（api 包）有自己的错误处理。 */
@ControllerAdvice(basePackages = "com.javastorm.shop.web")
public class GlobalModelAdvice {

    private final Cart cart;
    private final MessageSource messages;
    private final StripePaymentService stripe;

    public GlobalModelAdvice(Cart cart, MessageSource messages, StripePaymentService stripe) {
        this.cart = cart;
        this.messages = messages;
        this.stripe = stripe;
    }

    @ModelAttribute("cartCount")
    public int cartCount() {
        return cart.getTotalQuantity();
    }

    /** 前台是否为英文（美元）模式。 */
    @ModelAttribute("en")
    public boolean english() {
        return LocaleConfig.isEnglish();
    }

    @ModelAttribute("currency")
    public Currency currency() {
        return LocaleConfig.currentCurrency();
    }

    /** 当前币种是否走 Stripe 在线支付（结账页的提示和按钮文字用）。 */
    @ModelAttribute("onlinePayment")
    public boolean onlinePayment() {
        return stripe.isEnabledFor(LocaleConfig.currentCurrency());
    }

    @ExceptionHandler(BusinessException.class)
    public ModelAndView businessError(BusinessException e) {
        return errorPage(message(e));
    }

    /** 兜底：后台改订单状态、编辑商品时与下单等操作同时修改了同一条数据。 */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ModelAndView concurrentModification() {
        return errorPage(messages.getMessage("error.concurrent", null, "数据刚被其他操作修改过，请刷新页面后重试",
                LocaleContextHolder.getLocale()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ModelAndView uploadTooLarge() {
        return errorPage("上传的文件太大（单个图片最大 10MB，单次合计最大 50MB）");
    }

    /** 按当前语言取错误消息；没有 code 的后台错误直接用中文原文。 */
    public String message(BusinessException e) {
        if (e.getCode() == null) {
            return e.getMessage();
        }
        return messages.getMessage(e.getCode(), e.getArgs(), e.getMessage(), LocaleContextHolder.getLocale());
    }

    private ModelAndView errorPage(String message) {
        ModelAndView mv = new ModelAndView("error-page");
        mv.addObject("message", message);
        mv.addObject("cartCount", cart.getTotalQuantity());
        mv.addObject("en", LocaleConfig.isEnglish());
        mv.addObject("currency", LocaleConfig.currentCurrency());
        return mv;
    }
}
