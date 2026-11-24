#!/usr/bin/env bash
# 把自带数据库（data 文件夹）里的数据搬到 MySQL（macOS / Linux）。macOS 也可以直接双击 migrate-to-mysql.command
# 步骤见 README“换成 MySQL”：先建好空的 MySQL 数据库，在 config.env 里填好 DB_URL、DB_USERNAME、DB_PASSWORD

cd "$(dirname "$0")" || exit 1

echo "把 data 文件夹里的数据（商品、订单、会员等）搬到 MySQL。"
echo "开始前请确认："
echo "  1. 商城窗口已经全部关掉"
echo "  2. 已按 README 创建好空的 MySQL 数据库，并在 config.env 里填好 DB_URL、DB_USERNAME、DB_PASSWORD"
echo "原来的 data 文件夹只读取、不修改；出错时 MySQL 里不会留下数据。"
echo
read -r -p "确认开始迁移？输入 y 回车：" answer
case "$answer" in
  y|Y) ;;
  *) echo "已取消。"; exit 1 ;;
esac

echo
echo "下面会显示程序启动的信息，迁移完成后自动停止，不会打开浏览器。"
export MIGRATE_FROM_H2=./data/shop
export NO_BROWSER=1
bash ./start.sh
