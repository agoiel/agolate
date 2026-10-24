@echo off
cd /d "%~dp0"

if exist ".venv\installed.ok" goto run

echo First run: creating environment and installing libraries.
echo This takes a few minutes, please wait...
echo.

if exist ".venv\Scripts\python.exe" goto install
py -3 -m venv .venv
if exist ".venv\Scripts\python.exe" goto install
python -m venv .venv
if exist ".venv\Scripts\python.exe" goto install

echo.
echo ERROR: Python not found.
echo Install Python 3.10 or newer from https://www.python.org/downloads/
echo During installation check the box "Add python.exe to PATH".
pause
exit /b 1

:install
".venv\Scripts\python.exe" -m pip install --upgrade pip
".venv\Scripts\python.exe" -m pip install -r requirements.txt
if errorlevel 1 goto fail
echo ok> ".venv\installed.ok"

:run
start "" ".venv\Scripts\pythonw.exe" main.py
exit /b 0

:fail
echo.
echo ERROR: failed to install libraries. Copy the messages above and send them.
pause
exit /b 1
