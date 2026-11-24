@echo off
rem Chay install.ps1 voi quyen Administrator. Cua so se giu nguyen de doc ket qua.
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile -ExecutionPolicy Bypass -NoExit -File \"%~dp0install.ps1\"'"
