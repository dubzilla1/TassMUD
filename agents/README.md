# TassMUD Player Agents

AI-powered autonomous players for TassMUD that explore, fight, and interact with real players.

## Quick Start

1. **Set up environment**:
   ```powershell
   cd agents
   python -m venv venv
   .\venv\Scripts\Activate.ps1
   pip install -r requirements.txt
   ```

2. **Configure credentials**:
   ```powershell
   cp .env.example .env
   # Edit .env and add your GITHUB_TOKEN
   ```

3. **Make sure TassMUD is running**:
   ```powershell
   cd ..
   .\target\scripts\restart-mud.ps1
   ```

4. **Run a single agent**:
   ```powershell
   python player_agent.py gareth
   ```

5. **Run multiple agents**:
   ```powershell
   python run_agents.py gareth luna brynn
   # Or run all available agents:
   python run_agents.py --all
   ```

## Available Personas

| Name       | Class   | Personality                          |
|------------|---------|--------------------------------------|
| gareth     | Fighter | Brave, honorable, direct             |
| luna       | Wizard  | Curious, intellectual, eccentric     |
| thorne     | Rogue   | Witty, sarcastic, cunning            |
| brynn      | Cleric  | Kind, nurturing, helpful             |
| kira       | Ranger  | Quiet, observant, nature-lover       |
| magnus     | Fighter | Jovial, boisterous, friendly         |
| seraphina  | Wizard  | Mysterious, elegant, cryptic         |
| brock      | Fighter | Gruff, tough, secretly kind          |

## Configuration

Edit `.env` to configure:

```bash
# Required: GitHub personal access token with models:inference scope
GITHUB_TOKEN=ghp_your_token_here

# MUD server connection
MUD_HOST=localhost
MUD_PORT=4003
```

## How It Works

1. **Connection**: Each agent connects to TassMUD via TCP socket
2. **Login**: Creates a new character (or logs into existing) with the persona's name
3. **AI Loop**: The agent continuously:
   - Observes game output and parses state (HP, room, combat, etc.)
   - Uses GPT-4.1-mini to decide what to do next
   - Executes commands via tools (move, attack, say, chat, etc.)
   - Responds naturally when players talk to them

## Architecture

```
agents/
├── player_agent.py    # Main agent class with AI decision loop
├── run_agents.py      # Multi-agent launcher
├── mud_connection.py  # Async MUD connection handler
├── tools.py          # Tool functions the AI can call
├── personas.py       # Character personality definitions
├── requirements.txt  # Python dependencies
└── .env.example      # Environment template
```

## Features

- **Autonomous Exploration**: Agents move between rooms, discover areas
- **Combat**: Fight monsters, loot corpses, manage HP
- **Social Interaction**: Respond to chat/say/whisper naturally
- **Distinct Personalities**: Each agent has unique speaking style and behavior
- **Turing Test Ready**: Designed to be indistinguishable from human players

## Adding New Personas

Edit `personas.py` and add a new entry to the `PERSONAS` dict:

```python
"myagent": Persona(
    name="MyAgent",
    character_class="Fighter",  # Fighter, Wizard, Cleric, Rogue, Ranger
    personality="Describe their personality...",
    speaking_style="How they talk...",
    combat_aggression=0.5,  # 0.0-1.0
    social_tendency=0.5,    # 0.0-1.0
    exploration_drive=0.5,  # 0.0-1.0
    helpfulness=0.5,        # 0.0-1.0
    backstory="Their background story...",
),
```

## Troubleshooting

**Connection refused**: Make sure TassMUD is running on port 4003

**Token errors**: Verify GITHUB_TOKEN is set and has models:inference scope

**Import errors**: Make sure you activated the venv and installed requirements

**Agent not responding**: Check the console output for AI errors
