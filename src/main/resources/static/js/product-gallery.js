// 商品详情页：点击缩略图切换大图
(function () {
    'use strict';
    var thumbs = document.querySelectorAll('.gallery .thumbs img');
    var main = document.getElementById('mainImage');
    if (!main) return;
    thumbs.forEach(function (img) {
        img.addEventListener('click', function () {
            main.src = img.src;
            thumbs.forEach(function (i) { i.classList.remove('active'); });
            img.classList.add('active');
        });
    });
})();
