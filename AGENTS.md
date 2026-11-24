# Agent coordination protocol

Order: product-analyst -> tax-domain-analyst -> software-architect -> sdd-specifier -> architecture-reviewer -> java-developer -> test-engineer.

For each TAX story, record: evidence consulted, spec ID, acceptance tests, changed files, executed commands, failures, open questions. The developer cannot self-approve a gate. Fiscal domain assertions require a verified normative source and reviewer approval. Never treat a synthetic fixture as fiscal authority.

The `.claude/agents` files are role prompts; Claude Code agent invocation/availability depends on the installed CLI version. Project slash commands work as documented in `.claude/commands/nomad` on compatible Claude Code versions.
