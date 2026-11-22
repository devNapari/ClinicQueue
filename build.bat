@echo off
rem Builds ClinicQueue with the JDK only (Java 17+). Then run:  java -cp out\classes com.clinicqueue.Main
if exist out rmdir /s /q out
mkdir out\classes
javac -d out\classes src\main\java\com\clinicqueue\*.java
if errorlevel 1 exit /b 1
xcopy /E /I /Y src\main\resources\static out\classes\static >nul
echo Built. Start with: java -cp out\classes com.clinicqueue.Main
