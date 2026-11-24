#!/usr/bin/env bash
# macOS：在“访达”里双击这个文件，把 data 文件夹里的数据搬到 MySQL
cd "$(dirname "$0")" || exit 1
bash ./migrate-to-mysql.sh
status=$?
echo
read -r -p "按回车键关闭窗口……" _
exit $status
