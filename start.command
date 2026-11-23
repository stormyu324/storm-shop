#!/usr/bin/env bash
# macOS：在“访达”里双击这个文件即可启动商城（会打开一个“终端”窗口，关掉窗口即停止）
cd "$(dirname "$0")" || exit 1
bash ./start.sh
status=$?
if [ $status -ne 0 ]; then
  echo
  read -r -p "启动失败，按回车键关闭窗口……" _
fi
exit $status
