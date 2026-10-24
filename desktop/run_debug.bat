@echo off
cd /d "%~dp0"
rem Start with a console window: if the program does not open, the error is shown here.

if exist ".venv\installed.ok" goto run
echo Run run.bat first.
pause
exit /b 1

:run
".venv\Scripts\python.exe" main.py
pause
