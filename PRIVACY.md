# Privacy policy — Pentaho Live (unofficial)

_Last updated: 2026-10-20_

Pentaho Live is made of a Claude Code plugin (a skill and an MCP connection) and a plugin that runs
inside Pentaho Data Integration (Spoon) on your own computer. This policy covers both.

## What we collect

Nothing. The project has no servers, no accounts, no analytics and no telemetry. The author never
receives any of your data.

## What happens to your data

- **Everything stays on your computer.** The MCP connection goes only to `http://127.0.0.1:8765/mcp`
  (or the address you set in the `mcp_url` option), which is served by the Spoon plugin on the same
  machine. The Spoon plugin listens on the loopback interface only and rejects requests from web pages
  of other origins.
- **What Claude sees.** When you ask Claude to work on a transformation, the tools return to Claude what
  is needed for the task: the steps and their configuration, field names, validation messages, logs
  and, when you ask for a preview or a query, sample rows from your own files or databases. That content
  becomes part of your conversation with Claude and is handled under Anthropic's terms and privacy
  policy, like anything else you share in Claude.
- **Passwords.** Database passwords are masked before any XML or connection detail is returned to
  Claude. If you choose to give Claude a password to create a connection, Spoon stores it in the
  transformation the same way it does when you type it in Spoon's own dialog; the recommended way is to
  type passwords yourself in Spoon.
- **Files.** The plugin writes files only when you ask Claude to save a transformation or job, to the
  path you choose. Running a transformation writes whatever that transformation's own output steps write.
- **Nothing is stored** by the plugins beyond what Spoon itself keeps (your open documents, recent files).

## Children

Not directed at people under 18.

## Contact

Questions or issues: https://github.com/IvI2511/pentaho-mcp/issues
