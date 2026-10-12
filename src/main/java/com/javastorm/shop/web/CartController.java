package com.javastorm.shop.web;

import com.javastorm.shop.config.LocaleConfig;
import com.javastorm.shop.domain.CustomerOrder;
import com.javastorm.shop.domain.Product;
import com.javastorm.shop.repository.ProductRepository;
import com.javastorm.shop.service.BusinessException;
import com.javastorm.shop.service.Cart;
import com.javastorm.shop.service.CartView;
import com.javastorm.shop.service.OrderService;
import com.javastorm.shop.web.form.CheckoutForm;
import jakarta.validation.Valid;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class CartController {

    private final Cart cart;
    private final OrderService orderService;
    private final ProductRepository products;
    private final MessageSource messages;
    private final GlobalModelAdvice advice;

    public CartController(Cart cart, OrderService orderService, ProductRepository products,
                          MessageSource messages, GlobalModelAdvice advice) {
        this.cart = cart;
        this.orderService = orderService;
        this.products = products;
        this.messages = messages;
        this.advice = advice;
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
                             RedirectAttributes flash) {
        CartView view = view();
        if (view.isEmpty()) {
            return "redirect:/cart";
        }
        if (binding.hasErrors()) {
            model.addAttribute("cart", view);
            return "store/checkout";
        }
        try {
            CustomerOrder order = orderService.placeOrder(cart, form, LocaleConfig.currentCurrency());
            // 通过 flash 传递，避免任何人凭订单号查看他人的收货信息
            flash.addFlashAttribute("order", order);
            return "redirect:/order-success";
        } catch (BusinessException e) {
            model.addAttribute("cart", view());
            model.addAttribute("error", advice.message(e));
            return "store/checkout";
        }
    }

    @GetMapping("/order-success")
    public String orderPlaced(Model model) {
        if (!model.containsAttribute("order")) {
            return "redirect:/";
        }
        return "store/order-success";
    }

    private CartView view() {
        return orderService.view(cart, LocaleConfig.currentCurrency());
    }
}
