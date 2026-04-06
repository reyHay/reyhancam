' Launches the camera client silently — no console window
Dim fso, dir, shell
Set fso   = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
shell.Run "javaw -jar """ & dir & "\target\camera-client-1.0-jar-with-dependencies.jar""", 0, False
Set shell = Nothing
Set fso   = Nothing
