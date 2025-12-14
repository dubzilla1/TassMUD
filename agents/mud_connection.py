"""
MUD Connection Handler for TassMUD

Handles telnet connection to the MUD server, sending commands,
and receiving/parsing game output.
"""

from __future__ import annotations
import asyncio
import re
from typing import Optional, Callable, Awaitable, List
from dataclasses import dataclass, field
from enum import Enum, auto


class ConnectionState(Enum):
    """Current state of the MUD connection."""
    DISCONNECTED = auto()
    CONNECTING = auto()
    LOGIN_PROMPT = auto()
    PASSWORD_PROMPT = auto()
    LOGGED_IN = auto()
    IN_GAME = auto()


@dataclass
class GameState:
    """Parsed state from game output."""
    hp_cur: int = 100
    hp_max: int = 100
    mp_cur: int = 100
    mp_max: int = 100
    mv_cur: int = 100
    mv_max: int = 100
    room_name: str = ""
    room_description: str = ""
    exits: List[str] = field(default_factory=list)
    items_in_room: List[str] = field(default_factory=list)
    mobs_in_room: List[str] = field(default_factory=list)
    players_in_room: List[str] = field(default_factory=list)
    in_combat: bool = False
    last_output: str = ""
    inventory: List[str] = field(default_factory=list)
    

class MUDConnection:
    """
    Async connection handler for TassMUD.
    
    Manages telnet connection, login flow, command sending,
    and output parsing.
    """
    
    def __init__(
        self,
        host: str = "localhost",
        port: int = 4003,
        character_name: str = "TestAgent",
        password: str = "password123",
        character_class: str = "Fighter",
        on_output: Optional[Callable[[str], Awaitable[None]]] = None,
        on_chat: Optional[Callable[[str, str], Awaitable[None]]] = None,
    ):
        self.host = host
        self.port = port
        self.character_name = character_name
        self.password = password
        self._character_class = character_class
        self.on_output = on_output  # Callback for all output
        self.on_chat = on_chat  # Callback for chat messages (sender, message)
        
        self.state = ConnectionState.DISCONNECTED
        self.game_state = GameState()
        
        self._reader: Optional[asyncio.StreamReader] = None
        self._writer: Optional[asyncio.StreamWriter] = None
        self._output_buffer: str = ""
        self._output_queue: asyncio.Queue[str] = asyncio.Queue()
        self._running = False
        self._read_task: Optional[asyncio.Task] = None
        
    async def connect(self) -> bool:
        """Connect to the MUD server."""
        try:
            self.state = ConnectionState.CONNECTING
            self._reader, self._writer = await asyncio.open_connection(
                self.host, self.port
            )
            self._running = True
            self._read_task = asyncio.create_task(self._read_loop())
            print(f"[MUD] Connected to {self.host}:{self.port}")
            return True
        except Exception as e:
            print(f"[MUD] Connection failed: {e}")
            self.state = ConnectionState.DISCONNECTED
            return False
    
    async def disconnect(self):
        """Disconnect from the MUD server."""
        self._running = False
        if self._read_task:
            self._read_task.cancel()
            try:
                await self._read_task
            except asyncio.CancelledError:
                pass
        if self._writer:
            self._writer.close()
            try:
                await self._writer.wait_closed()
            except Exception:
                pass
        self.state = ConnectionState.DISCONNECTED
        print("[MUD] Disconnected")
    
    async def send(self, command: str) -> str:
        """
        Send a command to the MUD and wait for response.
        Returns the output received after sending the command.
        """
        if not self._writer or self.state == ConnectionState.DISCONNECTED:
            return "[Error: Not connected]"
        
        # Clear any pending output
        while not self._output_queue.empty():
            try:
                self._output_queue.get_nowait()
            except asyncio.QueueEmpty:
                break
        
        # Send the command
        self._writer.write(f"{command}\r\n".encode('utf-8'))
        await self._writer.drain()
        
        # Wait for response (with timeout)
        try:
            # Collect output for a short time to get full response
            output_parts = []
            deadline = asyncio.get_event_loop().time() + 1.0  # 1 second timeout
            
            while asyncio.get_event_loop().time() < deadline:
                try:
                    chunk = await asyncio.wait_for(
                        self._output_queue.get(),
                        timeout=0.3
                    )
                    output_parts.append(chunk)
                    # Reset deadline on new data
                    deadline = asyncio.get_event_loop().time() + 0.3
                except asyncio.TimeoutError:
                    break
            
            output = "".join(output_parts)
            self._parse_output(output)
            return output
            
        except Exception as e:
            return f"[Error receiving response: {e}]"
    
    async def login(self) -> bool:
        """
        Handle the login flow.
        Creates character if it doesn't exist.
        """
        print(f"[MUD] Logging in as {self.character_name}...")
        
        # Wait for initial prompt/title screen
        await asyncio.sleep(1.5)
        
        # Drain any initial output
        while not self._output_queue.empty():
            try:
                self._output_queue.get_nowait()
            except:
                break
        
        # Send character name
        response = await self.send(self.character_name)
        
        # Check if new character or existing
        if "not found" in response.lower() or "creating new" in response.lower():
            print(f"[MUD] Creating new character: {self.character_name}")
            
            # Wait for "Create password:" prompt
            await asyncio.sleep(0.5)
            response = await self.send(self.password)
            
            # Wait for "Re-type password:" prompt  
            await asyncio.sleep(0.5)
            response = await self.send(self.password)
            
            # Wait for class selection prompt
            await asyncio.sleep(0.5)
            
            # Select class by name (partial match works)
            response = await self.send(self._character_class)
            await asyncio.sleep(0.5)
            
            # Confirm class selection with "yes"
            response = await self.send("yes")
            
            # Wait for age prompt
            await asyncio.sleep(0.5)
            response = await self.send("25")  # Default age
            
            # Wait for description prompt
            await asyncio.sleep(0.5)
            response = await self.send("An adventurer from distant lands.")
            
            # Should now be logged in
            await asyncio.sleep(1.0)
            
        elif "password" in response.lower():
            # Existing character - send password
            print(f"[MUD] Existing character, entering password")
            response = await self.send(self.password)
            await asyncio.sleep(0.5)
        
        # Check if we're in the game
        response = await self.send("look")
        
        if "You see" in response or "exits" in response.lower() or "Exits" in response or "[" in response:
            self.state = ConnectionState.IN_GAME
            print(f"[MUD] Successfully logged in as {self.character_name}")
            return True
        else:
            print(f"[MUD] Login may have failed. Response: {response[:200]}")
            return False
    
    async def _read_loop(self):
        """Background task to read from the server."""
        try:
            while self._running and self._reader:
                try:
                    data = await asyncio.wait_for(
                        self._reader.read(4096),
                        timeout=0.1
                    )
                    if not data:
                        print("[MUD] Connection closed by server")
                        break
                    
                    text = data.decode('utf-8', errors='replace')
                    
                    # Strip telnet control sequences
                    text = self._strip_telnet_codes(text)
                    
                    # Put in queue for send() to collect
                    await self._output_queue.put(text)
                    
                    # Callback for real-time output
                    if self.on_output:
                        await self.on_output(text)
                    
                    # Check for chat messages
                    self._check_for_chat(text)
                    
                except asyncio.TimeoutError:
                    continue
                except Exception as e:
                    if self._running:
                        print(f"[MUD] Read error: {e}")
                    break
        except asyncio.CancelledError:
            pass
        finally:
            self._running = False
    
    def _strip_telnet_codes(self, text: str) -> str:
        """Remove telnet IAC sequences and ANSI codes."""
        # Remove telnet IAC sequences (bytes 255, etc.)
        # Simple approach - remove non-printable chars except newlines/tabs
        result = []
        i = 0
        while i < len(text):
            c = text[i]
            if c == '\xff':  # IAC
                # Skip IAC and next 1-2 bytes
                i += 2
                if i < len(text) and ord(text[i-1]) in (251, 252, 253, 254):
                    i += 1  # WILL/WONT/DO/DONT have extra byte
            elif c == '\x1b':  # ANSI escape
                # Skip until 'm' or end of string
                while i < len(text) and text[i] != 'm':
                    i += 1
                i += 1  # Skip the 'm'
            elif ord(c) >= 32 or c in '\r\n\t':
                result.append(c)
                i += 1
            else:
                i += 1
        return ''.join(result)
    
    def _parse_output(self, text: str):
        """Parse game output to update game state."""
        self.game_state.last_output = text
        
        # Parse prompt for HP/MP/MV
        # Default prompt format: <HP:100/100 MP:50/50 MV:100/100>
        prompt_match = re.search(
            r'<\s*HP:\s*(\d+)/(\d+)\s+MP:\s*(\d+)/(\d+)\s+MV:\s*(\d+)/(\d+)',
            text
        )
        if prompt_match:
            self.game_state.hp_cur = int(prompt_match.group(1))
            self.game_state.hp_max = int(prompt_match.group(2))
            self.game_state.mp_cur = int(prompt_match.group(3))
            self.game_state.mp_max = int(prompt_match.group(4))
            self.game_state.mv_cur = int(prompt_match.group(5))
            self.game_state.mv_max = int(prompt_match.group(6))
        
        # Check for combat
        if "[COMBAT]" in text:
            self.game_state.in_combat = True
        elif "Combat has ended" in text:
            self.game_state.in_combat = False
        
        # Parse room (after "look" command)
        # Room names are typically the first line after look
        lines = text.strip().split('\n')
        for i, line in enumerate(lines):
            # Look for exits line to identify room description end
            if line.strip().startswith("Exits:") or line.strip().startswith("[ Exits:"):
                # Parse exits
                exit_match = re.search(r'Exits?:\s*\[?\s*([^\]]+)\]?', line)
                if exit_match:
                    exits_str = exit_match.group(1)
                    self.game_state.exits = [
                        e.strip() for e in exits_str.split()
                        if e.strip() in ('north', 'south', 'east', 'west', 'up', 'down', 'n', 's', 'e', 'w', 'u', 'd')
                    ]
    
    def _check_for_chat(self, text: str):
        """Check for chat messages and trigger callback."""
        if not self.on_chat:
            return
        
        # Global chat: [CHAT] Player: message
        chat_match = re.search(r'\[CHAT\]\s*(\w+):\s*(.+)', text)
        if chat_match:
            sender = chat_match.group(1)
            message = chat_match.group(2).strip()
            if sender.lower() != self.character_name.lower():
                asyncio.create_task(self.on_chat(sender, message))
        
        # Room say: Player says 'message'
        say_match = re.search(r"(\w+) says ['\"](.+)['\"]", text)
        if say_match:
            sender = say_match.group(1)
            message = say_match.group(2).strip()
            if sender.lower() != self.character_name.lower():
                asyncio.create_task(self.on_chat(sender, message))
        
        # Whisper: Player whispers to you 'message'
        whisper_match = re.search(r"(\w+) whispers to you ['\"](.+)['\"]", text)
        if whisper_match:
            sender = whisper_match.group(1)
            message = whisper_match.group(2).strip()
            asyncio.create_task(self.on_chat(sender, message))
    
    def get_hp_percent(self) -> float:
        """Get current HP as percentage."""
        if self.game_state.hp_max <= 0:
            return 100.0
        return (self.game_state.hp_cur / self.game_state.hp_max) * 100
    
    def is_in_combat(self) -> bool:
        """Check if currently in combat."""
        return self.game_state.in_combat
    
    def needs_rest(self, threshold: float = 50.0) -> bool:
        """Check if HP is below threshold and needs rest."""
        return self.get_hp_percent() < threshold
