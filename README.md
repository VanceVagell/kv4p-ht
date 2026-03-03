# kv4p-ht
Open source handheld ham radio project kv4p HT

Please see the main project site: https://kv4p.com

## Development Environment

This project includes a pre-configured VS Code devcontainer for streamlined development.

### Prerequisites
- [Docker](https://www.docker.com/get-started) or [Podman](https://podman.io/)
- [Visual Studio Code](https://code.visualstudio.com/)
- [Dev Containers extension](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers)

### Quick Start

1. Clone this repository
2. Open the project folder in VS Code
3. When prompted, click "Reopen in Container" (or use Command Palette → "Dev Containers: Reopen in Container")
4. Wait for the container to build and configure

### What's Included

The devcontainer provides:
- **Java 17** - OpenJDK from Microsoft devcontainer base image
- **Node.js 20** - For tooling and development utilities
- **Android SDK** - Command line tools with platform-tools, API 35, and build-tools 35.0.0
- **ADB (Android Debug Bridge)** - For deploying and debugging on devices
- **VS Code Extensions** - GitHub PR, SonarLint, and GitHub Actions support
- **Debian Bookworm** - Base Linux environment

### Building the Android App

```bash
cd android-src/KV4PHT
./gradlew assembleDebug
```