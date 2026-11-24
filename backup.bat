@echo off
chcp 65001 >nul
rem Storm 商城一键备份（Windows）：双击本文件即可，商城运行中也可以备份
rem 备份保存在 backups 文件夹，默认保留最近 30 份；可在 config.env 里用 BACKUP_DIR、BACKUP_KEEP 修改
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"
title Storm 商城备份

rem ---------- 检查 Java 21 或更高版本（和 start.bat 相同） ----------
set "JAVA_EXE=java"
rem 不用括号块，避免 JAVA_HOME 里的 "(x86)" 之类的括号破坏语句
if defined JAVA_HOME if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME="
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
"%JAVA_EXE%" -version >nul 2>&1
if errorlevel 1 goto no_java
set "JV="
for /f "tokens=3" %%v in ('call "%JAVA_EXE%" -version 2^>^&1 ^| findstr /i /c:" version "') do if not defined JV set "JV=%%~v"
set "JMAJOR=0"
for /f "delims=." %%m in ("%JV%") do set "JMAJOR=%%m"
set /a JMAJOR=%JMAJOR% 2>nul
if %JMAJOR% LSS 21 goto old_java
"%JAVA_EXE%" tools\Backup.java
echo.
pause
exit /b 0

:no_java
echo.
echo 没有找到 Java，请先安装 Java 21 或更高版本：
echo   到 https://adoptium.net 下载 Temurin 21（Windows .msi），安装时勾选“Set JAVA_HOME variable”
echo   安装后关闭本窗口，重新双击 backup.bat。
echo.
pause
exit /b 1

:old_java
echo.
echo 当前 Java 版本是 %JV%，本项目需要 Java 21 或更高版本。
echo   到 https://adoptium.net 下载 Temurin 21（Windows .msi），安装时勾选“Set JAVA_HOME variable”
echo   如果已经安装过，请检查环境变量 JAVA_HOME 是否指向了旧版本。
echo.
pause
exit /b 1
