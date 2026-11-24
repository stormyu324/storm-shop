#!/usr/bin/env bash
# macOS：在“访达”里双击这个文件即可备份商城数据（商城运行中也可以备份）
cd "$(dirname "$0")" || exit 1
bash ./backup.sh
status=$?
echo
read -r -p "按回车键关闭窗口……" _
exit $status
