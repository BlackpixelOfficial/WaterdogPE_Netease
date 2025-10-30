@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

echo [INFO] 开始打包WaterDogPE项目...
echo.

:: 设置Java环境（根据实际安装路径调整）
echo [INFO] 设置Java环境...
set JAVA_HOME=D:\Program Files\Microsoft\jdk-17.0.11.9-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%

:: 验证Java版本
echo [INFO] 当前Java版本:
java -version
echo.

:: 切换到项目目录并运行编译
echo [INFO] 切换到WaterDogPE目录执行编译...
if errorlevel 1 (
    echo [ERROR] 无法切换到项目目录
    pause
    exit /b 1
)

:: 清理之前的构建
echo [INFO] 清理之前的构建文件...
call .\mvnw clean
if errorlevel 1 (
    echo [WARNING] 清理过程出现警告，继续执行...
)

:: 执行Maven install任务，跳过测试
echo [INFO] 执行Maven install任务（跳过测试）...
call .\mvnw install -DskipTests
if errorlevel 1 (
    echo [ERROR] 编译失败
    echo [INFO] 尝试使用调试模式获取更多信息...
    call .\mvnw install -DskipTests -X
    pause
    exit /b 1
)
echo [SUCCESS] 编译完成
echo.

echo.
echo [SUCCESS] WaterDogPE打包流程完成，文件已保存到target目录
pause