# AntiCrash for Minecraft Client

A Fabric mod for Minecraft 1.21.11 that blocks illegal server-side packets which could crash or freeze players' clients.

> **AI project:** This project was created entirely with AI. Review and test it before relying on it.

## Features

- Checks game-state, explosion, entity velocity, particle, position, rotation and entity metadata packets.
- Drops or limits suspicious data locally while allowing other packets to continue; intercepted events are reported in the local chat.
- Applies configurable limits to packet rates and resource-intensive effects.

Filtering can hide invalid entities or effects, and no client-side mod can prevent every crash or disconnect. An interception indicates that data exceeded a protection rule; it does not establish malicious intent.

## Requirements and installation

- Minecraft Java Edition **1.21.11**
- Java **21**
- Fabric Loader **0.18.4 or newer**
- No Fabric API required

Download the JAR from [Releases](../../releases), or build it yourself using the Gradle wrapper. Place the JAR in your client `mods` folder and remove older copies of Server Crash Guard first. The configuration file is `config/server-crash-guard.json`; restart the client after changing it.

## Build

Run `./gradlew build` on Linux/macOS or `gradlew.bat build` on Windows with Java 21. The build downloads its Gradle and Fabric dependencies when necessary.

## License

MIT; see [LICENSE](LICENSE).
