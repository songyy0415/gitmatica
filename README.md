# Gitmatica

Gitmatica is a client-side Litematica addon that brings Git-backed version
control to builds. It tracks a project as semantic chunk data, supports commits,
branches, history, change inspection, and safe restore workflows in singleplayer
and on compatible Servux servers.

This repository is the standalone-addon migration of Gitmatica. Install it
alongside the matching Minecraft 26.2 releases of Litematica and MaLiLib.

## Documentation

See the [Gitmatica wiki](https://github.com/Arnavpmr/gitmatica/wiki) for usage
notes and project documentation.

## Development

Use Java 25:

```sh
env JAVA_HOME=/home/arnav/.jdks/temurin-25.0.3 ./gradlew build
env JAVA_HOME=/home/arnav/.jdks/temurin-25.0.3 ./gradlew integrationTest
```
