package com.javastorm.shop.web;

import com.javastorm.shop.payment.PaymentFlow;
import com.javastorm.shop.payment.StripePaymentService;
import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.Cart;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Stripe 付款完成 / 取消后的返回页，以及 Stripe 的 webhook 通知。 */
@Controller
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    /** 会话里记住正在付款的订单号，顾客从付款页点“返回”时用来取消。 */
    public static final String PENDING_ORDER = "pendingPaymentOrderNo";

    private final PaymentFlow flow;
    private final StripePaymentService stripe;
    private final Cart cart;
    private final MessageSource messages;

    public PaymentController(PaymentFlow flow, StripePaymentService stripe, Cart cart, MessageSource messages) {
        this.flow = flow;
        this.stripe = stripe;
        this.cart = cart;
        this.messages = messages;
    }

    @GetMapping("/checkout/paid")
    public String paid(@RequestParam("session_id") String sessionId, HttpSession session, Model model) {
        var result = flow.confirmReturn(sessionId);
        if (result.isEmpty()) {
            return "redirect:/";
        }
        session.removeAttribute(PENDING_ORDER);
        model.addAttribute("order", result.get().order());
        model.addAttribute("paid", result.get().paid());
        model.addAttribute("processing", !result.get().paid());
        return "store/order-success";
    }

    @GetMapping("/checkout/cancel")
    public String cancel(HttpSession session, Model model, RedirectAttributes flash) {
        Object orderNo = session.getAttribute(PENDING_ORDER);
        if (orderNo == null) {
            return "redirect:/cart";
        }
        session.removeAttribute(PENDING_ORDER);
        if (flow.cancelByCustomer(orderNo.toString(), cart)) {
            // 返回时其实已经付款成功
            return flow.confirmReturnByOrder(orderNo.toString())
                    .map(order -> {
                        model.addAttribute("order", order);
                        model.addAttribute("paid", true);
                        return "store/order-success";
                    })
                    .orElse("redirect:/");
        }
        flash.addFlashAttribute("message", messages.getMessage("payment.cancelled", null, LocaleContextHolder.getLocale()));
        return "redirect:/cart";
    }

    @PostMapping("/stripe/webhook")
    @ResponseBody
    public ResponseEntity<String> webhook(@RequestBody String payload,
                                          @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        if (!stripe.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        try {
            flow.handleWebhook(stripe.parseWebhook(payload, signature));
            return ResponseEntity.ok("ok");
        } catch (BusinessException e) {
            log.warn("拒绝 Stripe webhook：{}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
