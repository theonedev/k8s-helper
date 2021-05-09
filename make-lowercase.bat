@echo off
goto :end_remarks
*************************************************************************************
*
*
*    authored:Sam Wofford
*    Returns lowercase of a string
*    12:13 PM 11/13/02
**************************************************************************************
:end_remarks
setlocal
set errorlevel=-1
if {%1}=={} echo NO ARG GIVEN&call :Help &goto :endit
if {%1}=={/?} call :Help &goto :endit
call :set_LCASE_array a b c d e f g h i j k l m n o p q r s t u v w x y z

:start
set input=%1
set input=%input:"=%
set totparams=0
call :COUNT_PARAMS %input%
call :MAKE_LOWERCASE %input%
set errorlevel=
echo %convertedstring%
endlocal
goto :eof
:endit
echo %errorlevel%
endlocal
goto :eof

:MAKE_LOWERCASE
:nextstring
if {%1}=={} goto :eof
set string=%1
set /a params+=1
set STRINGCONVERTED=
set pos=0
:NEXT_CHAR
set onechar=%%string^:^~%pos%,1%%
for /f "tokens=1,2 delims==" %%a in ('set onechar') do for /f %%c in ('echo %%b') do call :checkit %%c
if not defined STRINGCONVERTED goto :NEXT_CHAR
shift /1
if %params% LSS %totparams% set convertedstring=%convertedstring% &:add one space,but not at end
goto :nextstring
goto :eof

:Help
echo USAGE:%~n0 string OR %~n0 "with spaces"
echo function returns the lowercase of the string or -1 (error)
echo strings with embedded spaces needs to be in quotes Ex. "lower case"
echo in a batch NTscript "for /f %%%%A in ('lcase STRING') do set var=%%%%A"
set errorlevel=
goto :eof

:checkit
set LCFOUND=
if /i {%1}=={echo} set STRINGCONVERTED=Y&goto :eof
set char=%1
for /f "tokens=2 delims=_=" %%A in ('set LCASE_') do call :findit %%A %char%
:skipit
if defined LCFOUND (set convertedstring=%convertedstring%%ucletter%) else (set convertedstring=%convertedstring%%char%)
set /a pos+=1
goto :eof

:set_LCASE_array
:setit
if {%1}=={} goto :eof
set LCASE_%1_=%1
SHIFT /1
goto :setit

:findit
if defined LCFOUND goto :eof
set ucletter=%1
set lcchar=%2
if /i {%ucletter%}=={%lcchar%} set LCFOUND=yes
goto :eof

:COUNT_PARAMS
:COUNTPARAMS
if {%1}=={} goto :eof
set /a totparams+=1
shift /1
goto :COUNTPARAMS 
