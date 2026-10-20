# Pentaho Live (unofficial)

Build ETLs in **Pentaho Data Integration 9.4 (Spoon)** by asking Claude. With Spoon open, Claude adds
steps, connects them, configures them, validates the transformation, previews real rows and runs it,
and you watch every node appear on the canvas. Every change can be undone with Ctrl+Z in Spoon.

This is an independent community project. It is not affiliated with, endorsed by, or supported by
Hitachi Vantara. Pentaho is a trademark of Hitachi Vantara.

## What's inside

- **Skill `pentaho-etl`**: how to write `.ktr` transformations and `.kjb` jobs that actually load,
  verified against a real PDI 9.4.0.0-343 install: the exact step ids for all 216 steps and 77 job
  entries with their default XML, an annotated guide to the ~45 most used steps, nine runnable examples
  and the errors Pan/Kitchen give (including the silent ones). It works on its own: without Spoon,
  Claude writes the file and checks it with Pan.
- **MCP connection `pentaho`**: 57 tools (create/open/save, add/configure/connect steps and job
  entries, list output fields, validate, preview rows, run, database connections, parameters, and
  reports: HTML, PDF, Markdown and PowerPoint with lineage, data dictionary and run metrics).

## Requirement: the Spoon-side plugin

The MCP tools talk to a small plugin that runs **inside Spoon** on the same computer. Install it once:
download `pentaho-mcp.zip` from the
[latest release](https://github.com/IvI2511/pentaho-mcp/releases/latest), unzip it into
`data-integration/plugins/` and restart Spoon. Or ask Claude: *"install IvI2511/pentaho-mcp"* and it
follows [INSTALL.md](https://github.com/IvI2511/pentaho-mcp/blob/main/INSTALL.md). If you don't have
PDI yet, the release also has PDI 9.4 CE with the plugin already in place.

Live editing therefore needs Claude Code (or the desktop app) on the computer where Spoon runs. In
claude.ai chat only the skill is useful.

## What it runs, sends and stores

- The MCP connection only goes to `http://127.0.0.1:8765/mcp` (your own computer; change it with the
  `mcp_url` option). Nothing is sent to any other server, and the plugin collects no data.
- The Spoon-side plugin listens on the loopback interface only and rejects browser requests from other
  origins. It edits the documents you have open, writes files only where you ask Claude to save, and
  runs transformations only when asked. Database passwords are never returned to Claude.
- The skill may ask Claude to run Pan or Kitchen (PDI's command-line runners) on your computer to
  verify a file. Claude Code asks for permission before running commands, as usual.
- Source code of everything, including the Java plugin, is in the
  [repository](https://github.com/IvI2511/pentaho-mcp).

## Example prompts

- *"Build a transformation that reads ventas.csv, adds the city from clientes.csv, sums the amount per
  city and writes a CSV."*
- *"Why does this .ktr fail in Pan?"*
- *"Add a Filter rows after the CSV input that keeps rows with saldo > 1000 and preview the result."*

## Privacy

No data collection of any kind. Details: [privacy policy](https://github.com/IvI2511/pentaho-mcp/blob/main/PRIVACY.md).

## License

MIT. See [LICENSE](LICENSE).
