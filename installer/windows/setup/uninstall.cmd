@echo off
rem Go cai dat, GIU LAI du lieu. Muon xoa ca du lieu: chay uninstall.ps1 -RemoveData trong PowerShell quyen Administrator.
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile -ExecutionPolicy Bypass -NoExit -File \"%~dp0uninstall.ps1\"'"
