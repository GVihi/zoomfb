@echo off
rem Builds zoomfb.jar on Windows. Needs a JDK 24 or newer on PATH (javac and jar).
setlocal
cd /d "%~dp0"

if exist build rmdir /s /q build
mkdir build\classes

javac --release 25 -d build\classes src\dev\zoomfb\Agent.java src\dev\zoomfb\Config.java src\dev\zoomfb\Hooks.java src\dev\zoomfb\Hud.java src\dev\zoomfb\Input.java src\dev\zoomfb\Keys.java src\dev\zoomfb\Patcher.java
if errorlevel 1 exit /b 1

jar --create --file zoomfb.jar --manifest manifest.mf -C build\classes .
if errorlevel 1 exit /b 1

echo built %cd%\zoomfb.jar
