@echo off
chcp 65001 >nul
rem Storm 商城一键启动（Windows）：双击本文件即可
rem 配置：把 config.example.env 复制为 config.env，在里面填写密码、API 密钥等
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"
title Storm 商城

rem ---------- 1. 读取 config.env（已在环境里设置的变量优先） ----------
if not exist config.env goto config_done
echo 读取配置：config.env
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("config.env") do (
    if not defined %%a set "%%a=%%~b"
)
:config_done

rem ---------- 2. 检查 Java 21 或更高版本 ----------
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
echo 使用 Java %JMAJOR%

rem ---------- 3. 检查端口 ----------
if not defined PORT set "PORT=8080"
set "URL=http://localhost:%PORT%/"
netstat -ano | findstr /r /c:":%PORT% .*LISTENING" >nul
if not errorlevel 1 goto port_busy

rem ---------- 4. 启动成功后自动打开浏览器 ----------
if "%NO_BROWSER%"=="1" goto run
start "" /min powershell -NoProfile -WindowStyle Hidden -Command "for($i=0;$i -lt 600;$i++){Start-Sleep 1; try{Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 '%URL%' | Out-Null; Start-Process '%URL%'; break}catch{}}"

:run
rem ---------- 5. 启动 ----------
echo 正在启动……第一次运行需要下载依赖，可能要几分钟，请耐心等待。
echo 启动成功后会自动打开浏览器：%URL%   管理后台：%URL%admin
echo 关闭这个窗口即可停止商城。
echo.
call mvnw.cmd spring-boot:run
echo.
echo 商城已停止。
pause
exit /b 0

:no_java
echo.
echo 没有找到 Java，请先安装 Java 21 或更高版本：
echo   到 https://adoptium.net 下载 Temurin 21（Windows .msi），安装时勾选“Set JAVA_HOME variable”
echo   安装后关闭本窗口，重新双击 start.bat。
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

:port_busy
echo.
echo 端口 %PORT% 已被占用：可能商城已经在运行（浏览器打开 %URL% 看看），
echo 或者被别的程序占用。可以在 config.env 里设置 PORT=8081 换一个端口。
echo.
pause
exit /b 1
