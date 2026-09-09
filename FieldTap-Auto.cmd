@echo off
rem FieldTap plug-and-go launcher for Windows.
rem Double-click, then plug the phone(s) in. Each phone is captured until it is
rem unplugged; a report opens in the browser when a session ends. Ctrl-C stops.
rem Extra arguments are passed to `fieldtap auto` (e.g. --profile all --traffic ping,download).
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
    echo [FieldTap] creating the Python environment...
    py -3 -m venv .venv || python -m venv .venv || goto :nopython
    ".venv\Scripts\python.exe" -m pip install --quiet --upgrade pip
    ".venv\Scripts\python.exe" -m pip install --quiet -e ".[all]" || goto :pipfail
)
".venv\Scripts\python.exe" -m fieldtap.cli setup
echo.
".venv\Scripts\python.exe" -m fieldtap.cli auto --open-report --gps auto %*
goto :end
:nopython
echo [FieldTap] Python 3.9+ is required: https://www.python.org/downloads/windows/
goto :end
:pipfail
echo [FieldTap] pip install failed; check the network connection and try again.
:end
endlocal
pause
