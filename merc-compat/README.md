# TassMUD MERC Compatibility Fork (scaffold)

This folder contains scaffolding to create a local fork of the current TassMUD codebase for introducing MERC MUD compatibility.

Usage
- Run the included PowerShell script to copy the repository into a new folder named `tassmud-merc` (or provide a custom destination):

```powershell
# default
.\merc-compat\create_fork.ps1

# custom destination
.\merc-compat\create_fork.ps1 -Destination my-merc-fork
```

What the script does
- Copies the repository to the destination folder using `robocopy`.
- Excludes common build/output and editor folders: `target`, `logs`, `.git`, `.idea`, `.vs`, `node_modules`.

Next steps after forking
- We'll open the fork at `tassmud-merc` and start adding MERC-style imports and adapters.

If you want me to proceed and run the script now, say so and I'll create the fork automatically.
