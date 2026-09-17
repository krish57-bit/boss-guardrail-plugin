# BOSS Agent Guardrail

A third-party plugin for [BOSS Console](https://github.com/risa-labs-inc/BossConsole). It adds a
`guardrail_run` MCP tool: agents send shell commands to it, it checks each command against risk
rules, and it asks you in a BOSS dialog before running anything risky. If you deny the command,
don't answer in time, or the agent gives up, the command does not run.

Not audited, not published by Risa Labs, and not in the Toolbox store.

## What it does

| Piece | What BOSS API it uses |
| :--- | :--- |
| `GuardrailDynamicPlugin`, the entry class | `DynamicPlugin`, loaded from `META-INF/boss-plugin/plugin.json` |
| `guardrail_run`, `guardrail_check`, `guardrail_audit_log` tools (agents see `mcp__boss__guardrail_*`) | `PluginContext.registerMcpToolProvider` |
| Approval prompt: **Allow once** / **Allow for session** / **Deny** | `PluginContext.genericDialogProvider.showThreeButtonDialog` |
| Optional strict mode, which turns off BOSS's own shell tools | `PluginContext.mcpToolRegistry.setToolEnabled` |
| Agent Guardrail panel (audit log, rules, dry-run tester) | `PluginContext.panelRegistry` |

How a `guardrail_run` call is handled:

1. The command is checked against the rules. If nothing matches, it runs straight away (unless
   `requireApprovalForAll` is on).
2. If a rule matches, BOSS shows a dialog with the command and the rules it matched. Only one
   dialog is shown at a time; other calls wait their turn.
3. **Allow once** runs the command once. **Allow for session** also lets the same command
   (ignoring extra spaces) run again without asking, until the plugin is disabled or BOSS restarts.
4. **Deny**, closing the dialog, no answer within the timeout (45s by default), the agent
   cancelling, a dialog error, or a BOSS build with no dialog provider all block the command.
5. Allowed commands run with `/bin/sh -c` (`cmd.exe /c` on Windows) in the open project folder.
   Output is capped, and the command is killed when it hits its time limit or the call is cancelled.

The tool call carries no agent identity, so every agent shares one "session".

## What this does not protect

This is best-effort, and you should read this section before relying on it.

- **Only commands sent through `guardrail_run` are checked.** The BOSS plugin API has no hook
  that lets a plugin intercept other commands. Commands an agent runs through its own shell tool
  (for example Claude Code's Bash tool), through BOSS's `run_command` / `run_in_sidebar` /
  `run_in_panel` / `send_input` / `terminal_exec`, or through other plugins' tools
  (`k8s_exec`, `docker_*`, `codebase_write`, ...) never reach this plugin.
- **Strict mode only closes the BOSS MCP shell tools.** It turns them off with the same
  kill-switch as Toolbox → MCP. It does nothing about the agent's own shell tool, which you have
  to restrict in the agent's own settings. BOSS saves the kill-switch state, so if BOSS exits
  without disabling the plugin, those tools stay off until you turn them back on in
  Toolbox → MCP.
- **The rules are pattern matching and can be bypassed.** They cover common destructive
  commands and common ways of hiding them (`eval`, a command name in a variable,
  `sh -c "$(...)"`, decode-and-pipe-to-shell, quote-split names, `find -delete`, `truncate`,
  inline `python -c` / `node -e` scripts). A determined agent can still write something they
  miss. Use `requireApprovalForAll` if that matters to you.
- **Keep BOSS's own controls on.** This plugin is not a substitute for BOSS's per-tool MCP
  policy and kill-switch.
- **BOSS ends every MCP tool call after 60 seconds.** That limit covers both the approval wait
  and the command's run time, so this tool is not for long-running processes. Background
  children (`cmd &`) are stopped when the command finishes.
- **A timed-out dialog can't be closed from a plugin.** When a request times out or is
  cancelled, the plugin shows a "command not run" notice in its place, so a late click can't
  look like approval.

## Settings

`~/.boss/plugins/config/guardrail-settings.json` (all fields optional):

```json
{
  "strictMode": false,
  "requireApprovalForAll": false,
  "approvalTimeoutSeconds": 45,
  "maxCommandSeconds": 55
}
```

If this file exists but can't be read, the plugin asks before every command.

### Custom rules

`~/.boss/plugins/config/guardrail-rules.json` (see
[`example-guardrail-rules.json`](src/main/resources/example-guardrail-rules.json)). Patterns are
case-insensitive Java regexes.

```json
[
  {
    "id": "CUSTOM_AWS_DELETE",
    "name": "AWS Resource Deletion",
    "category": "REMOTE_EXECUTION_PIPE",
    "riskLevel": "CRITICAL_APPROVAL_REQUIRED",
    "pattern": "\\baws\\s+.*delete-(stack|bucket|cluster|function)\\b",
    "explanation": "Deletes cloud infrastructure via the AWS CLI.",
    "enabled": true
  }
]
```

Both files are read when the plugin loads. After editing them, disable and re-enable the
plugin to apply the changes.

## Build and install

You need JDK 17+. The build downloads the pinned `boss-plugin-api` release (currently 1.0.93).
That API is `compileOnly`: the plugin jar never bundles it.

```bash
./gradlew check            # tests + verifyPluginJar
./gradlew buildPluginJar   # build/libs/boss-guardrail-plugin-<version>.jar
```

To use a local API jar instead, pass `-PbossPluginApiJar=/path/to/boss-plugin-api-1.0.93.jar`.

To install the plugin, copy the jar into `~/.boss/plugins/`, restart BOSS, and enable
**Agent Guardrail** in the Plugin Manager.

To try it, ask an agent to call `mcp__boss__guardrail_run` with `rm -rf build/`. A BOSS dialog
should appear, and nothing is deleted unless you allow it.

`./gradlew run` opens a standalone demo window with simulated commands. It doesn't need BOSS.

## Tests

- `HostIntegrationTest` registers the plugin with a `PluginContext` built only from the public
  API, then sends agent calls as `McpToolRegistry.invoke("guardrail_run", "<json>")` through a
  registry that parses arguments and applies the 60s limit the way BOSS does. It checks that:
  - Deny, a closed dialog, a broken dialog, no dialog provider, the approval timeout, BOSS's
    timeout and caller cancellation never run the command, including when Allow is clicked
    after the caller gave up.
  - Allow once runs the command exactly once and asks again next time.
  - Allow for session is scoped to the exact command.
  - Concurrent calls each get their own dialog.
- `StrictModeTest` checks which tools get turned off and back on, that tools you turned off stay
  off, and that the plugin doesn't turn a tool off again after you turn it back on.
- `PluginLoadTest` loads the built jar like BossConsole's `DynamicPluginLoader`. It reads the
  manifest, loads `mainClass` from the jar, requires it to implement `Plugin`, creates it with
  the no-arg constructor, and validates it against the manifest and the store's publishing
  checks.
- `ShellCommandRunnerTest` uses real processes to cover exit codes, timeouts, killing the
  process on cancellation, output truncation and background children.
- `ShellPolicyEngineTest` and `BypassRulesTest` cover the rules, including the bypass examples
  raised in review and cases that shouldn't be flagged.

These tests do not start BOSS itself. The host side is reproduced from the public API and
BossConsole's `McpToolRegistryCore.invoke`, not run.

## License

No license file has been added yet.
