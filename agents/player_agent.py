"""
TassMUD Player Agent

An AI agent that autonomously plays the MUD game, explores,
fights monsters, interacts with players, and passes the Turing test.
"""

from __future__ import annotations
import asyncio
import os
import sys
import random
import json
from typing import Optional, List
from datetime import datetime
from dotenv import load_dotenv

# OpenAI SDK for GitHub Models
from openai import AsyncOpenAI

# Local imports
from mud_connection import MUDConnection, GameState
from personas import Persona, get_persona, get_random_persona
from tools import set_mud_connection, get_tool_definitions, execute_tool


class TassMUDAgent:
    """
    An AI agent that plays TassMUD autonomously.
    
    Features:
    - Connects to the MUD via telnet
    - Uses AI to decide actions based on game state
    - Responds to chat messages naturally
    - Explores, fights, loots, and interacts
    """
    
    def __init__(
        self,
        persona: Persona,
        host: str = "localhost",
        port: int = 4003,
        model: str = "gpt-4.1-mini",
    ):
        self.persona = persona
        self.host = host
        self.port = port
        self.model = model
        
        # MUD connection
        self.mud = MUDConnection(
            host=host,
            port=port,
            character_name=persona.name,
            password=persona.password,
            character_class=persona.character_class,
            on_output=self._on_game_output,
            on_chat=self._on_chat_message,
        )
        
        # OpenAI client for GitHub Models
        self.client: Optional[AsyncOpenAI] = None
        self._chat_queue: asyncio.Queue = asyncio.Queue()
        self._conversation_history: List[dict] = []
        
        # State tracking
        self.running = False
        self.last_action_time = datetime.now()
        self.recent_outputs: List[str] = []
        self.max_recent_outputs = 10
        
        # Behavior settings from persona
        self.action_interval = 5.0  # Base seconds between actions
        self.chat_response_delay = 1.5  # Seconds before responding to chat
        
    async def _on_game_output(self, text: str):
        """Callback for game output - log it for context."""
        # Keep recent outputs for context
        self.recent_outputs.append(text)
        if len(self.recent_outputs) > self.max_recent_outputs:
            self.recent_outputs.pop(0)
    
    async def _on_chat_message(self, sender: str, message: str):
        """Callback when someone chats - queue for response."""
        print(f"[CHAT] {sender}: {message}")
        await self._chat_queue.put((sender, message))
    
    def _create_client(self) -> AsyncOpenAI:
        """Create the OpenAI client for GitHub Models."""
        token = os.getenv("GITHUB_TOKEN")
        if not token:
            raise ValueError("GITHUB_TOKEN not set in environment")
        
        return AsyncOpenAI(
            base_url="https://models.github.ai/inference",
            api_key=token,
        )
    
    async def _call_ai(self, prompt: str, allow_tools: bool = True) -> str:
        """Call the AI model and handle tool calls."""
        if not self.client:
            return "[AI not initialized]"
        
        # Add user prompt to conversation
        messages = [
            {"role": "system", "content": self.persona.get_system_prompt()},
            *self._conversation_history[-10:],  # Keep last 10 messages for context
            {"role": "user", "content": prompt}
        ]
        
        try:
            # Call the model
            kwargs = {
                "model": self.model,
                "messages": messages,
                "max_tokens": 500,
            }
            
            if allow_tools:
                kwargs["tools"] = get_tool_definitions()
                kwargs["tool_choice"] = "auto"
            
            response = await self.client.chat.completions.create(**kwargs)
            
            message = response.choices[0].message
            
            # Handle tool calls
            if message.tool_calls:
                results = []
                for tool_call in message.tool_calls:
                    name = tool_call.function.name
                    args = json.loads(tool_call.function.arguments)
                    print(f"[TOOL] {name}({args})")
                    result = await execute_tool(name, args)
                    results.append(f"{name}: {result[:200]}")
                
                # Add to conversation history
                self._conversation_history.append({"role": "user", "content": prompt})
                self._conversation_history.append({"role": "assistant", "content": f"[Used tools: {', '.join(r.split(':')[0] for r in results)}]"})
                
                return "\n".join(results)
            
            # Regular text response
            content = message.content or ""
            self._conversation_history.append({"role": "user", "content": prompt})
            self._conversation_history.append({"role": "assistant", "content": content})
            
            return content
            
        except Exception as e:
            print(f"[AI ERROR] {e}")
            return f"[AI Error: {e}]"
    
    async def start(self):
        """Connect to the MUD and start the agent."""
        print(f"[AGENT] Starting {self.persona.name} the {self.persona.character_class}...")
        
        # Connect to MUD
        if not await self.mud.connect():
            print("[AGENT] Failed to connect to MUD")
            return False
        
        # Set up tool connection
        set_mud_connection(self.mud)
        
        # Login
        if not await self.mud.login():
            print("[AGENT] Login failed")
            await self.mud.disconnect()
            return False
        
        # Create AI client
        self.client = self._create_client()
        
        # Start the agent loop
        self.running = True
        print(f"[AGENT] {self.persona.name} is now active!")
        
        # Initial look to get bearings
        await self.mud.send("look")
        
        # Announce arrival
        if self.persona.social_tendency > 0.5:
            greetings = [
                "Hello everyone!",
                "Greetings, adventurers!",
                "Hey there!",
                "Good day, fellow travelers!",
            ]
            await self.mud.send(f"chat {random.choice(greetings)}")
        
        return True
    
    async def stop(self):
        """Stop the agent and disconnect."""
        self.running = False
        
        # Say goodbye
        if self.persona.social_tendency > 0.5:
            await self.mud.send("chat Farewell for now!")
        
        await self.mud.disconnect()
        print(f"[AGENT] {self.persona.name} has stopped.")
    
    async def run_loop(self):
        """Main agent loop - decide and take actions."""
        while self.running:
            try:
                # Check for incoming chat messages first
                await self._process_chat_queue()
                
                # Periodic autonomous action
                if (datetime.now() - self.last_action_time).total_seconds() > self.action_interval:
                    await self._take_autonomous_action()
                    self.last_action_time = datetime.now()
                
                # Small sleep to prevent busy loop
                await asyncio.sleep(0.5)
                
            except Exception as e:
                print(f"[AGENT] Error in main loop: {e}")
                await asyncio.sleep(2)
    
    async def _process_chat_queue(self):
        """Process any pending chat messages."""
        while not self._chat_queue.empty():
            try:
                sender, message = self._chat_queue.get_nowait()
                await self._respond_to_chat(sender, message)
            except asyncio.QueueEmpty:
                break
    
    async def _respond_to_chat(self, sender: str, message: str):
        """Use AI to respond to a chat message."""
        # Small delay to seem more human
        await asyncio.sleep(self.chat_response_delay * (0.5 + random.random()))
        
        prompt = f"""Someone just talked to you or on chat. Respond naturally!

WHO: {sender}
MESSAGE: "{message}"

Recent game context:
{self._get_recent_context()}

INSTRUCTIONS:
1. Decide if this message is directed at you or is general chat
2. If it seems like they're talking to you or saying something you'd respond to, use the 'say' or 'chat' tool to reply
3. Keep responses natural and in character
4. Don't over-explain or be too formal
5. It's okay to not respond to everything - only respond if you have something to say
"""
        
        try:
            response = await self._call_ai(prompt)
            if response:
                print(f"[AI RESPONSE] {response}")
        except Exception as e:
            print(f"[AGENT] Error responding to chat: {e}")
    
    async def _take_autonomous_action(self):
        """Let the AI decide what to do next."""
        gs = self.mud.game_state
        
        prompt = f"""What should you do next? Look at your current situation and decide on an action.

CURRENT STATUS:
- HP: {gs.hp_cur}/{gs.hp_max} ({self.mud.get_hp_percent():.0f}%)
- MP: {gs.mp_cur}/{gs.mp_max}
- MV: {gs.mv_cur}/{gs.mv_max}
- In Combat: {gs.in_combat}
- Known Exits: {', '.join(gs.exits) if gs.exits else 'unknown'}

RECENT GAME OUTPUT:
{self._get_recent_context()}

GUIDELINES:
1. If HP is low (under 50%), consider resting or fleeing if in combat
2. If there are monsters around and you're healthy, consider fighting
3. If the area seems clear, explore by moving to a new room
4. Occasionally chat with others or comment on what you're doing
5. Pick up interesting items or loot corpses after combat
6. Use 'look' if you're not sure what's around
7. Be proactive - don't just stand around!

What do you do? Choose 1-2 actions that make sense right now.
"""
        
        try:
            response = await self._call_ai(prompt)
            if response:
                print(f"[AI ACTION] {response}")
            
            # Vary the next action interval based on persona
            base = 3.0 + (1.0 - self.persona.exploration_drive) * 7.0  # 3-10 seconds
            self.action_interval = base * (0.7 + random.random() * 0.6)
            
        except Exception as e:
            print(f"[AGENT] Error taking action: {e}")
    
    def _get_recent_context(self) -> str:
        """Get recent game output as context."""
        if not self.recent_outputs:
            return "(No recent output)"
        
        # Combine and truncate
        combined = "\n".join(self.recent_outputs[-5:])
        if len(combined) > 1500:
            combined = combined[-1500:]
        return combined


async def run_agent(persona_name: str | None = None):
    """Run a single agent with the specified persona."""
    load_dotenv()
    
    # Get persona
    if persona_name:
        persona = get_persona(persona_name)
        if not persona:
            print(f"Unknown persona: {persona_name}")
            print(f"Available: gareth, luna, thorne, brynn, kira, magnus, seraphina, brock")
            return
    else:
        persona = get_random_persona()
        print(f"Using random persona: {persona.name}")
    
    # Get connection settings
    host = os.getenv("MUD_HOST", "localhost")
    port = int(os.getenv("MUD_PORT", "4003"))
    
    # Create and run agent
    agent = TassMUDAgent(
        persona=persona,
        host=host,
        port=port,
    )
    
    try:
        if await agent.start():
            await agent.run_loop()
    except KeyboardInterrupt:
        print("\n[AGENT] Interrupted by user")
    finally:
        await agent.stop()


if __name__ == "__main__":
    # Get persona from command line or use random
    persona_name = sys.argv[1] if len(sys.argv) > 1 else None
    asyncio.run(run_agent(persona_name))
