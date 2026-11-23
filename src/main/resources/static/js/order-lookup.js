(() => {
    const el = id => document.getElementById(id);
    const en = document.body.dataset.english === 'true';
    const text = (zh, english) => en ? english : zh;
    const feedback = message => { el('feedback').textContent = message; };
    let csrf;
    let challenge;
    async function request(path, method = 'GET', body) {
        const response = await fetch('/api/customer/orders' + path, {
            method, credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf || '' },
            body: body === undefined ? undefined : JSON.stringify(body)
        });
        const data = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(data.message || text('请求失败，请稍后重试', 'Request failed. Please retry.'));
        return data;
    }
    const ready = request('/session').then(data => { csrf = data.csrf; });
    async function run(form, action) {
        const button = form.querySelector('button');
        button.disabled = true;
        try { await ready; await action(); } catch (error) { feedback(error.message); }
        finally { button.disabled = false; }
    }
    function item(list, content) {
        const li = document.createElement('li');
        li.textContent = content;
        list.append(li);
    }
    const states = {
        REQUESTED: ['待审核', 'Awaiting review'], PROCESSING: ['退款处理中', 'Refund processing'],
        MANUAL_REQUIRED: ['等待线下退款', 'Awaiting manual refund'], SUCCEEDED: ['退款完成', 'Refund completed'],
        REJECTED: ['已驳回', 'Rejected'], FAILED: ['处理异常，等待商家核查', 'Requires merchant review']
    };
    async function load() {
        const data = await request('/current');
        const o = data.order;
        el('result').hidden = false; el('verify').hidden = true; el('lookup').hidden = true;
        el('order-title').textContent = text('订单 ', 'Order ') + o.orderNo;
        el('order-status').textContent = en ? o.status : o.statusLabel;
        el('order-total').textContent = o.currency + ' ' + o.total + (Number(o.discountAmount) > 0 ? ' · 优惠 / Discount ' + o.discountAmount : '');
        el('order-address').textContent = o.customerName + ' · ' + o.address;
        el('order-items').replaceChildren();
        o.items.forEach(i => item(el('order-items'), i.productName + ' × ' + i.quantity + ' · ' + o.currency + ' ' + i.subtotal));
        el('shipment').textContent = o.trackingNumber ? o.carrier + ' · ' + o.trackingNumber : text('等待发货', 'Awaiting shipment');
        el('tracking').hidden = !o.trackingUrl;
        if (o.trackingUrl) el('tracking').href = o.trackingUrl;
        el('cases').replaceChildren();
        if (!data.cases.length) item(el('cases'), text('暂无售后申请', 'No after-sales requests.'));
        data.cases.forEach(c => item(el('cases'),
            (states[c.state] ? text(...states[c.state]) : c.state) + ' · ' + c.reason +
            (c.decisionNote ? ' · ' + c.decisionNote : '') + '\n' +
            c.history.map(h => h.createdAt.replace('T', ' ').slice(0, 16) + ' · ' +
              (states[h.state] ? text(...states[h.state]) : h.state) + (h.note ? ' · ' + h.note : '')).join('\n')));
        const active = data.cases.some(c => c.state !== 'REJECTED');
        el('after-sales').hidden = active || !['PLACED', 'PAID', 'SHIPPED', 'COMPLETED'].includes(o.status);
    }
    el('lookup').addEventListener('submit', event => {
        event.preventDefault();
        run(el('lookup'), async () => {
            const data = await request('/code', 'POST', { orderNo: el('number').value.trim(), email: el('email').value.trim() });
            challenge = data.challenge; el('verify').hidden = false;
            feedback(text('信息匹配时，验证码会发送至下单邮箱，10 分钟内有效。', 'If the details match, a code will arrive by email. It expires in 10 minutes.'));
        });
    });
    el('verify').addEventListener('submit', event => {
        event.preventDefault();
        run(el('verify'), async () => {
            await request('/verify', 'POST', { challenge, code: el('code').value.trim() });
            feedback(''); await load();
        });
    });
    el('after-sales').addEventListener('submit', event => {
        event.preventDefault();
        run(el('after-sales'), async () => {
            await request('/current/after-sales', 'POST', { reason: el('reason').value });
            feedback(text('申请已提交，等待商家审核。', 'Request submitted for merchant review.'));
            await load();
        });
    });
    el('refresh').addEventListener('click', () => load().catch(e => feedback(e.message)));
    el('exit').addEventListener('click', async () => {
        try { await request('/session', 'DELETE'); location.reload(); } catch (e) { feedback(e.message); }
    });
    ready.then(() => {
        if (new URLSearchParams(location.search).get('current') === '1') return load();
    }).catch(error => feedback(error.message));
})();
