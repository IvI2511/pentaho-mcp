-- Lista lo que macOS ve dentro de la ventana 1 de un proceso: rol, nombre, posición y tamaño de cada
-- elemento (árbol de accesibilidad). Sirve para saber si un área que sale en blanco existe y dónde está.
-- Uso: osascript tools/ci/ventana.applescript <pid>
on run argv
	set pid to (item 1 of argv) as integer
	tell application "System Events"
		tell (first process whose unix id is pid)
			set w to window 1
			set salida to "ventana | " & (name of w) & " | " & my par(position of w) & " | " & my par(size of w) & linefeed
			set L to entire contents of w
			repeat with i from 1 to count of L
				set e to item i of L
				set fila to ""
				try
					set fila to (role of e) as text
				on error m
					set fila to "(rol: " & m & ")"
				end try
				try
					set fila to fila & " | " & ((name of e) as text)
				on error
					set fila to fila & " | -"
				end try
				try
					set fila to fila & " | " & my par(position of e) & " | " & my par(size of e)
				end try
				set salida to salida & fila & linefeed
			end repeat
			return salida
		end tell
	end tell
end run

on par(v)
	return ((item 1 of v) as text) & "," & ((item 2 of v) as text)
end par
