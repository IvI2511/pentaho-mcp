-- Lista lo que macOS ve dentro de la ventana 1 de un proceso: rol, nombre, posición y tamaño de cada
-- elemento (árbol de accesibilidad). Sirve para saber si un área que sale en blanco existe y dónde está.
-- Uso: osascript tools/ci/ventana.applescript <pid>
on run argv
	set pid to (item 1 of argv) as integer
	tell application "System Events"
		tell (first process whose unix id is pid)
			set salida to ""
			repeat with e in (entire contents of window 1)
				set fila to ""
				try
					set fila to (role of e) as text
				end try
				try
					set fila to fila & " | " & ((name of e) as text)
				on error
					set fila to fila & " | -"
				end try
				try
					set p to position of e
					set t to size of e
					set fila to fila & " | " & (item 1 of p) & "," & (item 2 of p) & " | " & (item 1 of t) & "x" & (item 2 of t)
				end try
				set salida to salida & fila & linefeed
			end repeat
			return salida
		end tell
	end tell
end run
