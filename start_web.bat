@echo off
chcp 65001 >nul
cd /d "%~dp0"
title LED Matrix 360 - Web Digital Clone Server

echo =====================================================================
echo           LED MATRIX 360 - MÁY CHỦ WEB DIGITAL CLONE
echo =====================================================================
echo.

set SERVE_DIR=app
if exist index.html set SERVE_DIR=.

echo [*] Đang khởi động Web Server tại http://localhost:8080 ...
echo [*] Trình duyệt sẽ tự động mở trang web trong giây lát.
echo.
echo [LƯU Ý QUAN TRỌNG ĐỂ KẾT NỐI BLUETOOTH]:
echo - Web Bluetooth API BẮT BUỘC phải chạy qua http://localhost hoặc https://.
echo - Mở trực tiếp file:/// sẽ bị trình duyệt chặn (lỗi "globally disabled").
echo - Khuyến nghị sử dụng Google Chrome hoặc Microsoft Edge.
echo.
echo (Nhấn Ctrl+C trong cửa sổ này để tắt máy chủ khi kết thúc)
echo ---------------------------------------------------------------------

start http://localhost:8080

where python >nul 2>nul
if %errorlevel% equ 0 (
    python -m http.server 8080 --directory %SERVE_DIR%
) else (
    echo [i] Không tìm thấy Python trong PATH, chuyển sang dùng PowerShell HTTP Listener...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$port=8080; $dir='%SERVE_DIR%'; $listener = New-Object System.Net.HttpListener; $listener.Prefixes.Add('http://localhost:' + $port + '/'); $listener.Start(); Write-Host 'Đang lắng nghe tại http://localhost:' + $port + '...'; while($listener.IsListening){ $ctx = $listener.GetContext(); $req = $ctx.Request; $res = $ctx.Response; $relPath = $req.Url.LocalPath.TrimStart('/'); if([string]::IsNullOrEmpty($relPath)){ $relPath = 'index.html' }; $fullPath = Join-Path (Resolve-Path $dir) $relPath; if(Test-Path $fullPath -PathType Leaf){ $bytes = [System.IO.File]::ReadAllBytes($fullPath); $res.ContentType = if($fullPath.EndsWith('.html')){'text/html; charset=utf-8'} elseif($fullPath.EndsWith('.js')){'application/javascript'} elseif($fullPath.EndsWith('.css')){'text/css'} else {'application/octet-stream'}; $res.ContentLength64 = $bytes.Length; $res.OutputStream.Write($bytes, 0, $bytes.Length) } else { $res.StatusCode = 404 }; $res.OutputStream.Close() }"
)
pause
