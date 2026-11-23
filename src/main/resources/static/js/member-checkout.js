(async () => {
 const button = document.getElementById('save-favorite');
 try {
  const session = await fetch('/api/member/session').then(r => r.json());
  if (button) button.addEventListener('click', async () => {
   if (!session.authenticated) { location.href = '/account'; return; }
   const result = await fetch('/api/member/favorites/' + button.dataset.productId, {
    method: 'PUT', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': session.csrf },
    body: JSON.stringify({ active: true }) });
   document.getElementById('favorite-feedback').textContent = result.ok ? '已收藏 / Saved' : '操作失败 / Failed';
  });
  const select = document.getElementById('saved-address');
  if (!session.authenticated || !select) return;
  const addresses = await fetch('/api/member/addresses').then(r => r.json());
  document.getElementById('saved-addresses').hidden = false;
  const email = document.getElementById('email'); email.value = session.email; email.readOnly = true;
  addresses.forEach(a => { const o = document.createElement('option'); o.value = a.id; o.textContent = a.name + ' · ' + a.address; select.append(o); });
  function fill() {
   const a = addresses.find(a => String(a.id) === select.value); if (!a) return;
   document.getElementById('customerName').value = a.name; document.getElementById('phone').value = a.phone;
   document.getElementById('country').value = a.country; document.getElementById('address').value = a.address;
   document.getElementById('country').dispatchEvent(new Event('change'));
  }
  select.addEventListener('change', fill);
  const preferred = addresses.find(a => a.preferred);
  if (preferred && !document.getElementById('address').value) { select.value = preferred.id; fill(); }
 } catch (e) {
  if (button) document.getElementById('favorite-feedback').textContent = '会员服务暂不可用 / Account unavailable';
 }
})();
