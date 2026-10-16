@echo off
chcp 65001 >nul
set "CURRENT_DIR=%CD%"
set "BOARD_DIR=%CURRENT_DIR%\Swarm_Board"

echo ===================================================
echo Đang khởi động Swarm cho dự án tại: %CURRENT_DIR%
echo ===================================================

:: Tự động tạo bảng điều phối nếu chưa có
if not exist "%BOARD_DIR%" mkdir "%BOARD_DIR%"
if not exist "%BOARD_DIR%\TASKS.md" (
    echo # BẢNG ĐIỀU PHỐI > "%BOARD_DIR%\TASKS.md"
)

:: Tạo nhánh worktree tự động nếu chưa tồn tại
if not exist "%CURRENT_DIR%_Coder1" (
    git worktree add "%CURRENT_DIR%_Coder1" -b branch-coder1 2>nul
)
if not exist "%CURRENT_DIR%_Reviewer" (
    git worktree add "%CURRENT_DIR%_Reviewer" -b branch-reviewer 2>nul
)

:: Mở Windows Terminal chia 3 khung tự động trỏ vào dự án hiện tại
wt -w 0 new-tab --title "1. ARCHITECT" -d "%CURRENT_DIR%" cmd /k "chcp 65001 >nul && agy" ^
   ; split-pane -V --title "2. CODER 1" -d "%CURRENT_DIR%_Coder1" cmd /k "chcp 65001 >nul && agy" ^
   ; split-pane -H --title "3. REVIEWER" -d "%CURRENT_DIR%_Reviewer" cmd /k "chcp 65001 >nul && agy"