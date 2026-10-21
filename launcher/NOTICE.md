# IDCLauncher — not covered by the MIT license

`IDCLauncher.exe` and `idc-launcher.png` in this folder are the splash launcher of the
IDC Studios desktop apps. They are © Iván Castaño · IDC Studios, all rights reserved, and
are **not** covered by this repository's MIT license.

You may redistribute them unmodified, as part of Pentaho Live (the Windows installer copies
them to `{app}\bin`). Their source code is not public. `preparar_instalador.ps1` (repository
root) uses the compiled files from this folder; the maintainer rebuilds them for every version with
`preparar_instalador.ps1 -FuenteLanzador <IDC Studios templates folder>`, because both carry the
version number.

`idc-launcher.ini` is ordinary configuration and is MIT like the rest of the repository.
