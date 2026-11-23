package com.javastorm.shop.web;

import com.javastorm.shop.config.LocaleConfig;
import com.javastorm.shop.domain.Currency;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.payment.PaymentFlow;
import com.javastorm.shop.payment.StripePaymentService;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.Cart;
import com.javastorm.shop.service.CartView;
import com.javastorm.shop.service.OrderService;
import com.javastorm.shop.web.form.CheckoutForm;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Controller
public class CartController {

    private static final int MAX_ORDER_ATTEMPTS = 3;

    private final Cart cart;
    private final OrderService orderService;
    private final ProductRepository products;
    private final MessageSource messages;
    private final GlobalModelAdvice advice;
    private final StripePaymentService stripe;
    private final PaymentFlow paymentFlow;
    private final String publicBaseUrl;

    public CartController(Cart cart, OrderService orderService, ProductRepository products,
                          MessageSource messages, GlobalModelAdvice advice, StripePaymentService stripe,
                          PaymentFlow paymentFlow, @Value("${shop.public-base-url:}") String publicBaseUrl) {
        this.cart = cart;
        this.orderService = orderService;
        this.products = products;
        this.messages = messages;
        this.advice = advice;
        this.stripe = stripe;
        this.paymentFlow = paymentFlow;
        this.publicBaseUrl = publicBaseUrl;
    }

    @GetMapping("/cart")
    public String cart(Model model) {
        model.addAttribute("cart", view());
        return "store/cart";
    }

    @PostMapping("/cart/add")
    public String add(@RequestParam Long productId, @RequestParam(defaultValue = "1") int quantity,
                      @RequestParam(required = false) String buyNow, RedirectAttributes flash) {
        Product p = products.findById(productId).filter(Product::isOnSale)
                .orElseThrow(() -> new BusinessException("error.product.notFound", "商品不存在或已下架"));
        String title = p.title(LocaleConfig.isEnglish());
        if (p.getStock() <= 0) {
            throw new BusinessException("error.product.soldOut", "「" + p.getName() + "」已售罄", title);
        }
        cart.add(productId, Math.max(quantity, 1));
        if (buyNow != null) {
            return "redirect:/checkout";
        }
        flash.addFlashAttribute("message", messages.getMessage("cart.added", new Object[]{title},
                LocaleContextHolder.getLocale()));
        return "redirect:/products/" + productId;
    }

    @PostMapping("/cart/update")
    public String update(@RequestParam Long productId, @RequestParam int quantity) {
        cart.set(productId, quantity);
        return "redirect:/cart";
    }

    @PostMapping("/cart/remove")
    public String remove(@RequestParam Long productId) {
        cart.remove(productId);
        return "redirect:/cart";
    }

    @GetMapping("/checkout")
    public String checkout(Model model) {
        CartView view = view();
        if (view.isEmpty()) {
            return "redirect:/cart";
        }
        model.addAttribute("cart", view);
        model.addAttribute("form", new CheckoutForm());
        return "store/checkout";
    }

    @PostMapping("/checkout")
    public String placeOrder(@Valid @ModelAttribute("form") CheckoutForm form, BindingResult binding, Model model,
                             RedirectAttributes flash, HttpSession httpSession) {
        CartView view = view();
        if (view.isEmpty()) {
            return "redirect:/cart";
        }
        if (binding.hasErrors()) {
            model.addAttribute("cart", view);
            return "store/checkout";
        }
        try {
            Currency currency = LocaleConfig.currentCurrency();
            boolean online = stripe.isEnabledFor(currency);
            CustomerOrder order = placeOrderWithRetry(form, currency, online);
            if (online) {
                String url = paymentFlow.startPayment(order, cart, baseUrl(), LocaleConfig.isEnglish());
                httpSession.setAttribute(PaymentController.PENDING_ORDER, order.getOrderNo());
                return "redirect:" + url;
            }
            // 通过 flash 传递，避免任何人凭订单号查看他人的收货信息
            flash.addFlashAttribute("order", order);
            return "redirect:/order-success";
        } catch (BusinessException e) {
            model.addAttribute("cart", view());
            model.addAttribute("error", advice.message(e));
            return "store/checkout";
        }
    }

    /**
     * 多人同时购买同一商品时，后提交的订单会遇到版本冲突。此时用最新库存重新下单：
     * 库存还够就成功，不够就得到“库存不足”的正常提示。
     */
    private CustomerOrder placeOrderWithRetry(CheckoutForm form, Currency currency, boolean awaitingPayment) {
        for (int attempt = 1; ; attempt++) {
            try {
                return orderService.placeOrder(cart, form, currency, awaitingPayment);
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt >= MAX_ORDER_ATTEMPTS) {
                    throw new BusinessException("error.order.busy", "下单人数较多，请稍后再试");
                }
            }
        }
    }

    @GetMapping("/order-success")
    public String orderPlaced(Model model) {
        if (!model.containsAttribute("order")) {
            return "redirect:/";
        }
        return "store/order-success";
    }

    /** 拼 Stripe 返回地址用：优先用配置的对外网址（部署在反向代理后面时必需）。 */
    private String baseUrl() {
        if (StringUtils.hasText(publicBaseUrl)) {
            String u = publicBaseUrl.trim();
            return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }

    private CartView view() {
        return orderService.view(cart, LocaleConfig.currentCurrency());
    }
}
