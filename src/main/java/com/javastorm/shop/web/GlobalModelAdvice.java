package com.javastorm.shop.web;

import com.javastorm.shop.config.LocaleConfig;
import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.Cart;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice
public class GlobalModelAdvice {

    private final Cart cart;
    private final MessageSource messages;

    public GlobalModelAdvice(Cart cart, MessageSource messages) {
        this.cart = cart;
        this.messages = messages;
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

    @ExceptionHandler(BusinessException.class)
    public ModelAndView businessError(BusinessException e) {
        return errorPage(message(e));
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
