"""
Tool Definitions for TassMUD Player Agent

These tools allow the AI agent to interact with the MUD game.
Each tool wraps a game command or query.
"""

from __future__ import annotations
from typing import TYPE_CHECKING, List, Dict, Optional
from typing_extensions import Annotated
import asyncio

if TYPE_CHECKING:
    from mud_connection import MUDConnection


# Global reference to the MUD connection (set by the agent)
_mud_connection: "MUDConnection | None" = None


def set_mud_connection(conn: "MUDConnection"):
    """Set the MUD connection for tools to use."""
    global _mud_connection
    _mud_connection = conn


def get_mud_connection() -> "MUDConnection":
    """Get the MUD connection, raising if not set."""
    if _mud_connection is None:
        raise RuntimeError("MUD connection not initialized")
    return _mud_connection


# =============================================================================
# MOVEMENT TOOLS
# =============================================================================

async def move(
    direction: Annotated[str, "Direction to move: north, south, east, west, up, or down"]
) -> str:
    """Move in a direction. Use this to navigate between rooms."""
    conn = get_mud_connection()
    
    # Normalize direction
    dir_map = {
        'north': 'north', 'n': 'north',
        'south': 'south', 's': 'south',
        'east': 'east', 'e': 'east',
        'west': 'west', 'w': 'west',
        'up': 'up', 'u': 'up',
        'down': 'down', 'd': 'down',
    }
    
    normalized = dir_map.get(direction.lower().strip())
    if not normalized:
        return f"Invalid direction: {direction}. Use north, south, east, west, up, or down."
    
    result = await conn.send(normalized)
    return result


async def look(
    target: Annotated[str, "Optional: what to look at (leave empty to look at the room)"] = ""
) -> str:
    """Look around the current room or examine something specific."""
    conn = get_mud_connection()
    
    if target:
        result = await conn.send(f"look {target}")
    else:
        result = await conn.send("look")
    
    return result


async def recall() -> str:
    """Teleport back to the starting area (Mead-Gaard Inn). Use when lost or need to get back quickly."""
    conn = get_mud_connection()
    result = await conn.send("recall")
    return result


# =============================================================================
# COMBAT TOOLS
# =============================================================================

async def attack(
    target: Annotated[str, "Name of the creature or player to attack"]
) -> str:
    """Attack a target to initiate combat. Use 'kill' command."""
    conn = get_mud_connection()
    result = await conn.send(f"kill {target}")
    return result


async def flee() -> str:
    """Attempt to flee from combat. Use when HP is low or fight is too dangerous."""
    conn = get_mud_connection()
    result = await conn.send("flee")
    return result


async def kick() -> str:
    """Use the kick combat skill to interrupt an enemy. Must be in combat."""
    conn = get_mud_connection()
    result = await conn.send("kick")
    return result


async def check_combat() -> str:
    """Check current combat status."""
    conn = get_mud_connection()
    result = await conn.send("combat")
    return result


# =============================================================================
# RESTING TOOLS
# =============================================================================

async def sit() -> str:
    """Sit down to rest. Regenerates HP/MP/MV faster than standing."""
    conn = get_mud_connection()
    result = await conn.send("sit")
    return result


async def sleep() -> str:
    """Go to sleep. Regenerates HP/MP/MV fastest, but you can't see what's happening."""
    conn = get_mud_connection()
    result = await conn.send("sleep")
    return result


async def stand() -> str:
    """Stand up from sitting or sleeping."""
    conn = get_mud_connection()
    result = await conn.send("stand")
    return result


async def rest_until_healed(
    hp_threshold: Annotated[int, "HP percentage to heal to (default 80)"] = 80
) -> str:
    """Sit and rest until HP reaches the threshold. Returns status when done."""
    conn = get_mud_connection()
    
    await conn.send("sit")
    
    max_wait = 60  # Max 60 seconds of resting
    wait_time = 0
    
    while wait_time < max_wait:
        hp_pct = conn.get_hp_percent()
        if hp_pct >= hp_threshold:
            break
        
        # Check status every 5 seconds
        await asyncio.sleep(5)
        await conn.send("score")  # This updates game state
        wait_time += 5
    
    await conn.send("stand")
    
    hp_pct = conn.get_hp_percent()
    return f"Rested for {wait_time} seconds. HP is now at {hp_pct:.0f}%."


# =============================================================================
# ITEM TOOLS
# =============================================================================

async def get_item(
    item: Annotated[str, "Name of the item to pick up"],
    container: Annotated[str, "Optional: container to get from (e.g., 'corpse')"] = ""
) -> str:
    """Pick up an item from the room or from a container."""
    conn = get_mud_connection()
    
    if container:
        result = await conn.send(f"get {item} {container}")
    else:
        result = await conn.send(f"get {item}")
    
    return result


async def drop_item(
    item: Annotated[str, "Name of the item to drop"]
) -> str:
    """Drop an item from your inventory."""
    conn = get_mud_connection()
    result = await conn.send(f"drop {item}")
    return result


async def inventory() -> str:
    """Check what items you are carrying."""
    conn = get_mud_connection()
    result = await conn.send("inventory")
    return result


async def equip(
    item: Annotated[str, "Name of the item to equip"]
) -> str:
    """Equip an item from your inventory (armor, weapon, etc.)."""
    conn = get_mud_connection()
    result = await conn.send(f"equip {item}")
    return result


async def loot_corpse() -> str:
    """Loot all items from a corpse in the room."""
    conn = get_mud_connection()
    result = await conn.send("get all corpse")
    return result


async def sacrifice(
    item: Annotated[str, "Name of the item to sacrifice (e.g., 'corpse')"]
) -> str:
    """Sacrifice an item on the ground for 1 XP. Corpses must be empty (looted) first."""
    conn = get_mud_connection()
    result = await conn.send(f"sacrifice {item}")
    return result


# =============================================================================
# INFORMATION TOOLS
# =============================================================================

async def score() -> str:
    """Check your character's stats, HP, MP, MV, level, etc."""
    conn = get_mud_connection()
    result = await conn.send("score")
    return result


async def who() -> str:
    """See who is currently online in the game."""
    conn = get_mud_connection()
    result = await conn.send("who")
    return result


async def skills() -> str:
    """List your known skills and proficiency."""
    conn = get_mud_connection()
    result = await conn.send("skills")
    return result


async def spells() -> str:
    """List your known spells and proficiency."""
    conn = get_mud_connection()
    result = await conn.send("spells")
    return result


async def help_command(
    topic: Annotated[str, "Help topic to look up"]
) -> str:
    """Look up help on a command or topic."""
    conn = get_mud_connection()
    result = await conn.send(f"help {topic}")
    return result


async def get_current_status() -> str:
    """Get a summary of your current status: HP, MP, MV, room, combat state."""
    conn = get_mud_connection()
    
    # Get fresh data
    await conn.send("score")
    await asyncio.sleep(0.2)
    await conn.send("look")
    
    gs = conn.game_state
    status = f"""Current Status:
- HP: {gs.hp_cur}/{gs.hp_max} ({conn.get_hp_percent():.0f}%)
- MP: {gs.mp_cur}/{gs.mp_max}
- MV: {gs.mv_cur}/{gs.mv_max}
- In Combat: {gs.in_combat}
- Exits: {', '.join(gs.exits) if gs.exits else 'unknown'}
"""
    return status


# =============================================================================
# COMMUNICATION TOOLS
# =============================================================================

async def say(
    message: Annotated[str, "Message to say to others in the room"]
) -> str:
    """Say something to others in the same room."""
    conn = get_mud_connection()
    result = await conn.send(f"say {message}")
    return result


async def chat(
    message: Annotated[str, "Message to send on the global chat channel"]
) -> str:
    """Send a message on the global chat channel. Everyone online will see it."""
    conn = get_mud_connection()
    result = await conn.send(f"chat {message}")
    return result


async def yell(
    message: Annotated[str, "Message to yell loudly"]
) -> str:
    """Yell something loudly. Can be heard in adjacent rooms."""
    conn = get_mud_connection()
    result = await conn.send(f"yell {message}")
    return result


async def whisper(
    player: Annotated[str, "Name of the player to whisper to"],
    message: Annotated[str, "Private message to send"]
) -> str:
    """Send a private whisper to another player."""
    conn = get_mud_connection()
    result = await conn.send(f"whisper {player} {message}")
    return result


# =============================================================================
# RAW COMMAND TOOL
# =============================================================================

async def send_command(
    command: Annotated[str, "The raw MUD command to send"]
) -> str:
    """Send any raw command to the MUD. Use this for commands not covered by other tools."""
    conn = get_mud_connection()
    result = await conn.send(command)
    return result


# =============================================================================
# TOOL LIST FOR AGENT
# =============================================================================

def get_gameplay_tools() -> list:
    """Get the list of gameplay tools for the agent."""
    return [
        # Movement
        move,
        look,
        recall,
        # Combat
        attack,
        flee,
        kick,
        check_combat,
        # Resting
        sit,
        sleep,
        stand,
        rest_until_healed,
        # Items
        get_item,
        drop_item,
        inventory,
        equip,
        loot_corpse,
        sacrifice,
        # Information
        score,
        who,
        skills,
        spells,
        help_command,
        get_current_status,
        # Communication
        say,
        chat,
        yell,
        whisper,
        # Raw
        send_command,
    ]


# =============================================================================
# OPENAI FUNCTION CALLING SUPPORT
# =============================================================================

def get_tool_definitions() -> List[Dict]:
    """Get OpenAI-compatible tool definitions for function calling."""
    return [
        {
            "type": "function",
            "function": {
                "name": "move",
                "description": "Move in a direction to navigate between rooms.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "direction": {
                            "type": "string",
                            "description": "Direction to move: north, south, east, west, up, or down",
                            "enum": ["north", "south", "east", "west", "up", "down"]
                        }
                    },
                    "required": ["direction"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "look",
                "description": "Look around the current room or examine something specific.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "target": {
                            "type": "string",
                            "description": "What to look at (leave empty to look at the room)"
                        }
                    },
                    "required": []
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "recall",
                "description": "Teleport back to the starting area. Use when lost.",
                "parameters": {"type": "object", "properties": {}, "required": []}
            }
        },
        {
            "type": "function",
            "function": {
                "name": "attack",
                "description": "Attack a target to initiate combat.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "target": {
                            "type": "string",
                            "description": "Name of the creature or player to attack"
                        }
                    },
                    "required": ["target"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "flee",
                "description": "Attempt to flee from combat. Use when HP is low.",
                "parameters": {"type": "object", "properties": {}, "required": []}
            }
        },
        {
            "type": "function",
            "function": {
                "name": "kick",
                "description": "Use the kick combat skill. Must be in combat.",
                "parameters": {"type": "object", "properties": {}, "required": []}
            }
        },
        {
            "type": "function", 
            "function": {
                "name": "sit",
                "description": "Sit down to rest and regenerate HP/MP faster.",
                "parameters": {"type": "object", "properties": {}, "required": []}
            }
        },
        {
            "type": "function",
            "function": {
                "name": "stand",
                "description": "Stand up from sitting or sleeping.",
                "parameters": {"type": "object", "properties": {}, "required": []}
            }
        },
        {
            "type": "function",
            "function": {
                "name": "get_item",
                "description": "Pick up an item from the room or container.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "item": {
                            "type": "string",
                            "description": "Name of item to pick up (use 'all' for everything)"
                        },
                        "container": {
                            "type": "string",
                            "description": "Container to get from (e.g., 'corpse')"
                        }
                    },
                    "required": ["item"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "inventory",
                "description": "Check what items you are carrying.",
                "parameters": {"type": "object", "properties": {}, "required": []}
            }
        },
        {
            "type": "function",
            "function": {
                "name": "equip",
                "description": "Equip an item from your inventory.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "item": {"type": "string", "description": "Name of item to equip"}
                    },
                    "required": ["item"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "sacrifice",
                "description": "Sacrifice an item on the ground for 1 XP. Corpses must be looted first. Use after combat to clean up corpses.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "item": {"type": "string", "description": "Name of item to sacrifice (e.g., 'corpse')"}
                    },
                    "required": ["item"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "score",
                "description": "Check your character stats, HP, MP, MV, level.",
                "parameters": {"type": "object", "properties": {}, "required": []}
            }
        },
        {
            "type": "function",
            "function": {
                "name": "who",
                "description": "See who is currently online.",
                "parameters": {"type": "object", "properties": {}, "required": []}
            }
        },
        {
            "type": "function",
            "function": {
                "name": "say",
                "description": "Say something to others in the same room.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "message": {"type": "string", "description": "Message to say"}
                    },
                    "required": ["message"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "chat",
                "description": "Send a message on the global chat channel. Everyone online sees it.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "message": {"type": "string", "description": "Message to chat"}
                    },
                    "required": ["message"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "whisper",
                "description": "Send a private whisper to another player.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "player": {"type": "string", "description": "Player name"},
                        "message": {"type": "string", "description": "Private message"}
                    },
                    "required": ["player", "message"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "send_command",
                "description": "Send any raw MUD command not covered by other tools.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "command": {"type": "string", "description": "The raw command to send"}
                    },
                    "required": ["command"]
                }
            }
        },
    ]


async def execute_tool(name: str, arguments: dict) -> str:
    """Execute a tool by name with the given arguments."""
    tool_map = {
        "move": move,
        "look": look,
        "recall": recall,
        "attack": attack,
        "flee": flee,
        "kick": kick,
        "sit": sit,
        "stand": stand,
        "get_item": get_item,
        "inventory": inventory,
        "equip": equip,
        "sacrifice": sacrifice,
        "score": score,
        "who": who,
        "say": say,
        "chat": chat,
        "whisper": whisper,
        "send_command": send_command,
    }
    
    func = tool_map.get(name)
    if not func:
        return f"Unknown tool: {name}"
    
    try:
        result = await func(**arguments)
        return result
    except Exception as e:
        return f"Error executing {name}: {e}"
