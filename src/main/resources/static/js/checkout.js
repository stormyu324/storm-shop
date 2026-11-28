// 结账页：切换国家或应用优惠码时重新计算运费和合计
(function () {
    'use strict';
    var select = document.getElementById('country');
    if (!select) return;
    select.addEventListener('change', function () {
        fetch('/checkout/shipping?country=' + encodeURIComponent(select.value) + '&couponCode=' + encodeURIComponent(document.getElementById('couponCode').value), {headers: {'Accept': 'application/json'}})
            .then(function (res) { if (!res.ok) throw new Error('网络错误，请重试 / Network error'); return res.json(); })
            .then(function (d) {
                document.getElementById('couponFeedback').textContent = d.couponError || '';
                document.getElementById('couponDiscount').textContent = d.discount ? '优惠 / Discount ' + d.discount : '';
                document.getElementById('shippingFee').textContent = d.shipping;
                document.getElementById('grandTotal').textContent = d.total;
                document.getElementById('freeHint').textContent = d.freeHint || '';
                var notice = document.getElementById('shipNotice');
                notice.textContent = d.notice || '';
                notice.style.display = d.deliverable ? 'none' : '';
                document.getElementById('submitOrder').disabled = !d.deliverable;
            }).catch(function (e) { document.getElementById('couponFeedback').textContent = e.message; });
    });
    var apply = document.getElementById('applyCoupon');
    if (apply) {
        apply.addEventListener('click', function () { select.dispatchEvent(new Event('change')); });
    }
})();
