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
import com.javastorm.shop.service.Countries;
import com.javastorm.shop.service.OrderPlacer;
import com.javastorm.shop.service.Pricing;
import com.javastorm.shop.service.SiteUrls;
import com.javastorm.shop.service.OrderService;
import com.javastorm.shop.web.form.CheckoutForm;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class CartController {

    static final String SHIP_COUNTRY = "shipCountry";

    private final Cart cart;
    private final OrderService orderService;
    private final ProductRepository products;
    private final MessageSource messages;
    private final GlobalModelAdvice advice;
    private final StripePaymentService stripe;
    private final PaymentFlow paymentFlow;
    private final SiteUrls siteUrls;
    private final OrderPlacer orderPlacer;
    private final HttpSession session;
    private final Pricing pricing;

    public CartController(Cart cart, OrderService orderService, ProductRepository products,
                          MessageSource messages, GlobalModelAdvice advice, StripePaymentService stripe,
                          PaymentFlow paymentFlow, SiteUrls siteUrls, OrderPlacer orderPlacer,
                          HttpSession session, Pricing pricing) {
        this.siteUrls = siteUrls;
        this.orderPlacer = orderPlacer;
        this.session = session;
        this.pricing = pricing;
        this.cart = cart;
        this.orderService = orderService;
        this.products = products;
        this.messages = messages;
        this.advice = advice;
        this.stripe = stripe;
        this.paymentFlow = paymentFlow;
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
        CheckoutForm form = new CheckoutForm();
        form.setCountry(shipCountry());
        model.addAttribute("cart", view);
        model.addAttribute("form", form);
        model.addAttribute("countries", Countries.list(LocaleContextHolder.getLocale()));
        return "store/checkout";
    }

    /** 结账页切换国家时重新计算运费（页面上用 JavaScript 调用）。 */
    @GetMapping("/checkout/shipping")
    @ResponseBody
    public Map<String, Object> shippingFor(@RequestParam String country) {
        if (Countries.isValid(country)) {
            session.setAttribute(SHIP_COUNTRY, country.toUpperCase(Locale.ROOT));
        }
        CartView view = view();
        Locale locale = LocaleContextHolder.getLocale();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deliverable", view.deliverable());
        body.put("shipping", !view.deliverable() ? "—" : view.shipping().signum() == 0
                ? messages.getMessage("cart.shippingFree", null, locale)
                : pricing.format(view.shipping(), view.currency()));
        body.put("total", pricing.format(view.total(), view.currency()));
        body.put("freeHint", view.freeRemaining() == null ? null
                : messages.getMessage("cart.freeShippingHint",
                new Object[]{pricing.format(view.freeRemaining(), view.currency())}, locale));
        body.put("notice", view.deliverable() ? null
                : messages.getMessage("error.shipping.unavailable",
                new Object[]{Countries.name(view.country(), locale)}, locale));
        return body;
    }

    @PostMapping("/checkout")
    public String placeOrder(@Valid @ModelAttribute("form") CheckoutForm form, BindingResult binding, Model model,
                             RedirectAttributes flash, HttpSession httpSession) {
        if (!binding.hasFieldErrors("country") && Countries.isValid(form.getCountry())) {
            httpSession.setAttribute(SHIP_COUNTRY, form.getCountry());
        } else if (!binding.hasFieldErrors("country")) {
            binding.rejectValue("country", "invalid",
                    messages.getMessage("checkout.country.required", null, LocaleContextHolder.getLocale()));
        }
        CartView view = view();
        if (view.isEmpty()) {
            return "redirect:/cart";
        }
        model.addAttribute("countries", Countries.list(LocaleContextHolder.getLocale()));
        if (binding.hasErrors()) {
            model.addAttribute("cart", view);
            return "store/checkout";
        }
        try {
            Currency currency = LocaleConfig.currentCurrency();
            boolean online = stripe.isEnabledFor(currency);
            CustomerOrder order = orderPlacer.place(cart, form, currency, online);
            if (online) {
                String url = paymentFlow.startPayment(order, cart, siteUrls.baseUrl(), LocaleConfig.isEnglish());
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

    /** 运费按哪个国家算：顾客选过的，否则按前台默认（英文前台美国，中文前台中国）。 */
    private String shipCountry() {
        Object chosen = session.getAttribute(SHIP_COUNTRY);
        if (chosen != null) {
            return chosen.toString();
        }
        return LocaleConfig.isEnglish() ? "US" : "CN";
    }

    private CartView view() {
        return orderService.view(cart, LocaleConfig.currentCurrency(), shipCountry());
    }
}
