// AI 导购：首页“帮我挑”和商品页“AI 推荐搭配”，调用 POST /api/recommendations
(function () {
    'use strict';

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c];
        });
    }

    function money(amount, currency) {
        var n = Number(amount).toLocaleString('en-US', {minimumFractionDigits: 2, maximumFractionDigits: 2});
        return (currency === 'USD' ? '$' : '¥') + n;
    }

    function card(item, box) {
        var p = item.product;
        var price = money(p.price, p.currency);
        if (p.priceFrom) {
            price = box.dataset.aiFrom.replace('{0}', price);
        }
        var img = p.mainImage || '/img/placeholder.svg';
        return '<a class="product-card" href="/products/' + encodeURIComponent(p.id) + '">'
            + '<div class="thumb"><img src="' + esc(img) + '" alt="' + esc(p.title) + '" loading="lazy"></div>'
            + '<div class="info"><div class="name">' + esc(p.title) + '</div>'
            + '<div><span class="price">' + esc(price) + '</span></div>'
            + '<p class="ai-reason">' + esc(item.reason) + '</p></div></a>';
    }

    function ask(box, body, button) {
        var result = box.querySelector('[data-ai-result]');
        result.hidden = false;
        result.innerHTML = '<p class="muted ai-thinking"><span class="ai-dot"></span>' + esc(box.dataset.aiThinking) + '</p>';
        if (button) button.disabled = true;
        fetch('/api/recommendations', {
            method: 'POST',
            headers: {'Content-Type': 'application/json', 'Accept': 'application/json'},
            body: JSON.stringify(body)
        }).then(function (res) {
            return res.json().then(function (data) { return {ok: res.ok, data: data}; },
                function () { return {ok: false, data: {}}; });
        }).then(function (r) {
            if (!r.ok) {
                result.innerHTML = '<p class="ai-error">' + esc(r.data.message || box.dataset.aiError) + '</p>';
                return;
            }
            var html = '<p class="ai-reply">' + esc(r.data.reply) + '</p>';
            if (r.data.items.length) {
                html += '<div class="product-grid ai-grid">' + r.data.items.map(function (i) { return card(i, box); }).join('') + '</div>';
            } else if (!r.data.reply) {
                html = '<p class="muted">' + esc(box.dataset.aiEmpty) + '</p>';
            }
            result.innerHTML = html;
        }).catch(function () {
            result.innerHTML = '<p class="ai-error">' + esc(box.dataset.aiError) + '</p>';
        }).finally(function () {
            if (button) button.disabled = false;
        });
    }

    // 首页：AI 导购
    document.querySelectorAll('.ai-shop').forEach(function (box) {
        var form = box.querySelector('[data-ai-form]');
        var input = form.querySelector('input[name=query]');
        var button = form.querySelector('button[type=submit]');
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            if (input.value.trim()) ask(box, {query: input.value.trim()}, button);
        });
        box.querySelectorAll('[data-ai-example]').forEach(function (chip) {
            chip.addEventListener('click', function () {
                input.value = chip.textContent.trim();
                ask(box, {query: input.value}, button);
            });
        });
    });

    // 商品页：AI 推荐搭配（点了才调用，避免每次浏览都计费）
    document.querySelectorAll('.ai-pair').forEach(function (box) {
        var button = box.querySelector('[data-ai-pair]');
        button.addEventListener('click', function () {
            ask(box, {productId: Number(box.dataset.productId)}, button);
        });
    });
})();
