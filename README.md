# TassMUD (Minimal Telnet Server)

This is a minimal Java 17 Maven project that provides a tiny telnet-based MUD server for experimentation.

Features
- Listens on TCP port 4000
- Accepts multiple clients (thread-per-connection)
- Commands: `look`, `say <text>`, `quit`

Build

```bash
mvn -v
mvn package
```

Run

```bash
java -jar target/tass-mud-0.1.0.jar
# connect via telnet from another terminal
telnet localhost 4000
```

Notes
- Java 17 is required.
- This is a tiny starter project meant for learning and extension.
