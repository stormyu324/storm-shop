(() => {
 const el = id => document.getElementById(id);
 let csrf, challenge, editing = null, page = 0;
 const feedback = message => { el('member-feedback').textContent = message; };
 async function request(path, method = 'GET', data) {
  const res = await fetch('/api/member' + path, { method, credentials: 'same-origin',
   headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf || '' },
   body: data === undefined ? undefined : JSON.stringify(data) });
  const result = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(result.message || '请求失败 / Request failed');
  return result;
 }
 async function run(action) { try { feedback(''); await action(); } catch (e) { feedback(e.message); } }
 function line(parent, text) { const p = document.createElement('p'); p.textContent = text; parent.append(p); return p; }
 function button(parent, label, action) {
  const b = document.createElement('button'); b.type = 'button'; b.className = 'ghost'; b.textContent = label;
  b.addEventListener('click', () => run(action)); parent.append(b);
 }
 function fill(address) {
  editing = address.id || null;
  el('address-name').value = address.name || ''; el('address-phone').value = address.phone || '';
  el('address-country').value = address.country || 'CN'; el('address-text').value = address.address || '';
  el('address-preferred').checked = Boolean(address.preferred);
 }
 async function addresses() {
  const list = await request('/addresses');
  el('member-addresses').replaceChildren();
  list.forEach(a => {
   const section = document.createElement('article'); section.className = 'card';
   line(section, a.name + ' · ' + a.phone + (a.preferred ? ' · 默认 / Default' : ''));
   line(section, a.country + ' ' + a.address);
   button(section, '编辑 / Edit', () => fill(a));
   button(section, '设为默认 / Set default', async () => {
    await request('/addresses/' + a.id, 'PUT', { ...a, preferred: true }); await addresses();
   });
   button(section, '停用 / Disable', async () => {
    await request('/addresses/' + a.id, 'PUT', { ...a, active: false }); if (editing === a.id) fill({}); await addresses();
   });
   el('member-addresses').append(section);
  });
 }
 async function orders() {
  const data = await request('/orders?page=' + page);
  el('member-orders').replaceChildren();
  if (!data.items.length) line(el('member-orders'), '暂无订单 / No orders yet');
  data.items.forEach(o => {
   const section = document.createElement('article'); section.className = 'card';
   line(section, o.orderNo + ' · ' + o.statusLabel + ' · ' + o.currency + ' ' + o.total);
   o.items.forEach(i => line(section, i.productName + ' × ' + i.quantity));
   button(section, '物流与售后 / Delivery and support', async () => {
    await request('/orders/' + o.id + '/access', 'POST'); location.href = '/orders/lookup?current=1';
   });
   el('member-orders').append(section);
  });
  el('orders-previous').disabled = page === 0; el('orders-next').disabled = page + 1 >= data.totalPages;
 }
 async function favorites() {
  const data = await request('/favorites'); el('member-favorites').replaceChildren();
  if (!data.length) line(el('member-favorites'), '暂无收藏 / No saved products');
  data.forEach(p => {
   const row = document.createElement('p');
   const link = document.createElement('a'); link.href = '/products/' + p.id; link.textContent = p.title;
   row.append(link); row.append(document.createTextNode(' · ' + p.currency + ' ' + p.price + ' '));
   button(row, '取消收藏 / Unsave', async () => { await request('/favorites/' + p.id, 'PUT', { active: false }); await favorites(); });
   el('member-favorites').append(row);
  });
 }
 async function load() {
  const session = await request('/session'); csrf = session.csrf;
  el('member-content').hidden = !session.authenticated; el('member-login').hidden = session.authenticated;
  if (session.authenticated) {
   el('member-verify').hidden = true; el('member-identity').textContent = session.email;
   await Promise.all([addresses(), orders(), favorites()]);
  }
 }
 el('member-login').addEventListener('submit', e => { e.preventDefault(); run(async () => {
  const data = await request('/code', 'POST', { email: el('member-email').value.trim() });
  challenge = data.challenge; el('member-verify').hidden = false;
  feedback('验证码已发送，10 分钟内有效 / A code has been sent. Expires in 10 minutes.');
 }); });
 el('member-verify').addEventListener('submit', e => { e.preventDefault(); run(async () => {
  await request('/verify', 'POST', { challenge, code: el('member-code').value.trim() }); await load();
 }); });
 el('address-form').addEventListener('submit', e => { e.preventDefault(); run(async () => {
  await request('/addresses' + (editing ? '/' + editing : ''), editing ? 'PUT' : 'POST', {
   name: el('address-name').value, phone: el('address-phone').value, country: el('address-country').value,
   address: el('address-text').value, preferred: el('address-preferred').checked, active: true });
  fill({}); await addresses();
 }); });
 el('address-new').addEventListener('click', () => fill({}));
 el('member-logout').addEventListener('click', () => run(async () => { await request('/session', 'DELETE'); location.reload(); }));
 el('orders-previous').addEventListener('click', () => run(async () => { page--; await orders(); }));
 el('orders-next').addEventListener('click', () => run(async () => { page++; await orders(); }));
 fetch('/api/countries').then(r => r.json()).then(list => {
  list.forEach(c => { const o = document.createElement('option'); o.value = c.code; o.textContent = c.name; el('address-country').append(o); });
  fill({});
 }).catch(e => feedback(e.message));
 load().catch(e => feedback(e.message));
})();
