@echo off
rem Loadout launcher shim.
rem
rem Every usage message and error in the app tells people to run "loadout ...",
rem so that has to be a real command rather than a documentation shorthand.
setlocal

set "HERE=%~dp0"

rem Two layouts: a built distribution has the jar beside this script, a source
rem checkout has it under launcher\build\libs. Prefer the former so an installed
rem copy never accidentally runs a stale development build.
set "JAR=%HERE%loadout.jar"
if not exist "%JAR%" set "JAR=%HERE%..\launcher\build\libs\launcher-0.1.0.jar"

if not exist "%JAR%" (
	echo Loadout is not built yet. Run this from the project root:>&2
	echo     gradlew build>&2
	exit /b 1
)

rem JAVA_HOME wins when it is set, because the java on PATH is often whatever
rem some other installer put there.
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"

"%JAVA%" -jar "%JAR%" %*
set "CODE=%ERRORLEVEL%"

if %CODE% equ 9009 (
	echo Java was not found on PATH. Loadout needs Java 21 or newer.>&2
	echo Install it from https://adoptium.net and reopen this terminal.>&2
)

endlocal & exit /b %CODE%
