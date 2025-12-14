"""Multi-Agent Launcher for TassMUD

Runs multiple AI agents simultaneously to populate the game world.
Each agent has a distinct persona and acts autonomously.
"""

from __future__ import annotations
import asyncio
import os
import sys
import signal
from typing import Optional, List
from dotenv import load_dotenv

from player_agent import TassMUDAgent
from personas import PERSONAS, get_persona, Persona


# Track all running agents for cleanup
running_agents: List[TassMUDAgent] = []
shutdown_event = asyncio.Event()


async def run_single_agent(persona: Persona, host: str, port: int, stagger_delay: float = 0):
    """Run a single agent with optional startup delay."""
    if stagger_delay > 0:
        print(f"[LAUNCHER] {persona.name} will start in {stagger_delay:.1f}s...")
        await asyncio.sleep(stagger_delay)
    
    agent = TassMUDAgent(
        persona=persona,
        host=host,
        port=port,
    )
    running_agents.append(agent)
    
    try:
        if await agent.start():
            # Run until shutdown
            while not shutdown_event.is_set():
                try:
                    # Run loop iteration with timeout to check shutdown
                    await asyncio.wait_for(
                        agent.run_loop(),
                        timeout=1.0
                    )
                except asyncio.TimeoutError:
                    continue
                except asyncio.CancelledError:
                    break
    except asyncio.CancelledError:
        pass
    except Exception as e:
        print(f"[LAUNCHER] {persona.name} error: {e}")
    finally:
        await agent.stop()
        if agent in running_agents:
            running_agents.remove(agent)


async def run_multiple_agents(
    persona_names: List[str],
    host: str = "localhost",
    port: int = 4003,
    stagger_seconds: float = 3.0,
):
    """
    Run multiple agents with staggered startup.
    
    Args:
        persona_names: List of persona names to run
        host: MUD server host
        port: MUD server port
        stagger_seconds: Delay between each agent starting
    """
    print(f"[LAUNCHER] Starting {len(persona_names)} agents...")
    print(f"[LAUNCHER] Connecting to {host}:{port}")
    print(f"[LAUNCHER] Agents: {', '.join(persona_names)}")
    print()
    
    # Create tasks for each agent
    tasks = []
    for i, name in enumerate(persona_names):
        persona = get_persona(name)
        if not persona:
            print(f"[LAUNCHER] Unknown persona '{name}', skipping")
            continue
        
        delay = i * stagger_seconds
        task = asyncio.create_task(
            run_single_agent(persona, host, port, delay)
        )
        tasks.append(task)
    
    if not tasks:
        print("[LAUNCHER] No valid personas to run!")
        return
    
    # Wait for all agents (or shutdown)
    try:
        await asyncio.gather(*tasks)
    except asyncio.CancelledError:
        print("[LAUNCHER] Shutting down agents...")
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)


async def shutdown():
    """Signal all agents to shut down."""
    print("\n[LAUNCHER] Shutdown requested...")
    shutdown_event.set()
    
    # Stop all running agents
    for agent in list(running_agents):
        try:
            await agent.stop()
        except Exception:
            pass


def signal_handler():
    """Handle Ctrl+C gracefully."""
    asyncio.create_task(shutdown())


def main():
    """Main entry point."""
    load_dotenv()
    
    # Get connection settings
    host = os.getenv("MUD_HOST", "localhost")
    port = int(os.getenv("MUD_PORT", "4003"))
    
    # Parse command line arguments
    args = sys.argv[1:]
    
    if not args:
        # Default: run a few agents
        personas = ["gareth", "luna", "brynn"]
        print("[LAUNCHER] No personas specified, using defaults")
    elif args[0] == "--all":
        # Run all personas
        personas = list(PERSONAS.keys())
    elif args[0] == "--help":
        print("""
TassMUD Multi-Agent Launcher

Usage:
    python run_agents.py [persona1] [persona2] ...
    python run_agents.py --all
    python run_agents.py --help

Available Personas:
""")
        for name, persona in PERSONAS.items():
            print(f"    {name:12} - {persona.character_class:8} - {persona.personality[:50]}...")
        print()
        return
    else:
        personas = args
    
    print("""
╔═══════════════════════════════════════════════════════╗
║          TassMUD Multi-Agent Launcher                  ║
╠═══════════════════════════════════════════════════════╣
║  Running AI agents to populate your MUD world!        ║
║  Press Ctrl+C to stop all agents                      ║
╚═══════════════════════════════════════════════════════╝
""")
    
    # Set up signal handling
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    
    try:
        # On Windows, signal handling is limited
        if sys.platform != "win32":
            loop.add_signal_handler(signal.SIGINT, signal_handler)
            loop.add_signal_handler(signal.SIGTERM, signal_handler)
        
        loop.run_until_complete(
            run_multiple_agents(personas, host, port)
        )
    except KeyboardInterrupt:
        print("\n[LAUNCHER] Interrupted")
        loop.run_until_complete(shutdown())
    finally:
        loop.close()


if __name__ == "__main__":
    main()
