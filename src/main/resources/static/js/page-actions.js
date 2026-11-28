// 通用页面交互（不用内联脚本，配合内容安全策略 script-src 'self'）：
// - data-autosubmit：值变化后自动提交所在表单（排序下拉框、购物车数量）
// - data-submit-once：表单只提交一次，防止双击重复下单
// - data-history-back：返回上一页
(function () {
    'use strict';
    document.querySelectorAll('[data-autosubmit]').forEach(function (el) {
        el.addEventListener('change', function () {
            if (el.form) el.form.submit();
        });
    });
    document.querySelectorAll('form[data-submit-once]').forEach(function (form) {
        form.addEventListener('submit', function (e) {
            var button = document.getElementById(form.dataset.submitOnce);
            if (!button) return;
            if (button.dataset.sent) {
                e.preventDefault();
                return;
            }
            button.dataset.sent = '1';
            button.disabled = true;
        });
    });
    document.querySelectorAll('[data-history-back]').forEach(function (link) {
        link.addEventListener('click', function (e) {
            if (window.history.length > 1) {
                e.preventDefault();
                window.history.back();
            }
        });
    });
})();
