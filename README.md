# Logify

Logify is a login plugin for Velocity. Players link their Minecraft nickname to a Discord account, and every new login must be approved in Discord. Until then the player waits in a virtual world built with [LimboAPI](https://github.com/Elytrium/LimboAPI) and never reaches the backend servers.

The Discord bot runs inside the proxy: no separate process is needed.

## How it works

### Registration

1. A player joins with a nickname that is not registered yet and lands in the waiting world.
2. The chat shows a command with a one-time code, for example `/r AZA-AXF`. Clicking it copies it to the clipboard.
3. The player pastes it into the registration channel on the Discord server. `/r` is a Discord slash command with a `code` parameter, so it can also be typed as `/r` and filled in from the command picker.
4. The bot links the nickname to the sender's Discord account and answers right in the channel with an ephemeral message that only the sender sees. The player is let through to the server.

A slash command leaves no message in the channel, so the channel stays clean, and nobody else sees the codes or the answers. The command works only in the registration channel; elsewhere the bot answers with a hint where to use it.

The code is valid until the registration timeout runs out (5 minutes by default). A Discord user can link a limited number of nicknames (one by default), and the number of commands per minute is limited to prevent guessing codes.

### Login

1. A player joins with a registered nickname.
2. The owner gets a direct message from the bot with the nickname, the IP and the time, and the buttons **Подтвердить** and **Отклонить**.
3. Approve lets the player in, deny disconnects them. If nobody answers in time (2 minutes by default), the player is disconnected and the message is marked as expired.

After an approved login, joining again from the same IP does not need approval for a while (12 hours by default). The session counts from the last approval and is not extended by playing.

### Other rules

- Nicknames are matched case-sensitively: offline UUIDs depend on case, so `steve` cannot join an account registered as `Steve`.
- Only one connection per nickname can be in the waiting world at a time.
- Nicknames that do not match `auth.nickname-pattern` are rejected before login.
- Players verified by Mojang (online mode) skip Logify when `premium.skip-online-mode` is on. With `premium.auto-detect`, Logify turns on Mojang verification for free nicknames that belong to licensed accounts, so only their owners can take them. Nicknames already registered through Discord are never forced into online mode.
- If the plugin fails to start (for example, a broken `config.toml`), the proxy refuses every login instead of letting players in without checks. If Discord or the database is unavailable, players who need them are disconnected with a message to try later.

## Requirements

- Velocity 4.3 on Java 25
- [LimboAPI](https://github.com/Elytrium/LimboAPI/releases) 1.2.0 or newer (the first release that supports Minecraft 26.3)
- MariaDB
- A Discord bot

## Setup

### Discord bot

1. Create an application in the [Discord Developer Portal](https://discord.com/developers/applications) and open **Bot**.
2. Copy the token (**Reset Token**). No privileged intents are needed.
3. Invite the bot to your server: **OAuth2 → URL Generator**, scopes `bot` and `applications.commands`. The bot needs no channel permissions.
4. Create a registration channel and copy its ID (turn on Developer Mode in Discord, then right-click the channel → **Copy Channel ID**). Players need the **Use Application Commands** permission there, which members have by default.

On start the bot registers the registration command on the server of that channel. It replaces the bot's own slash commands on that server, so use a separate bot for Logify.

Login requests arrive as direct messages, so players must allow direct messages from server members. Logify disconnects players whose direct messages are closed and tells them why.

### Database

Create a database and a user for Logify, for example:

```sql
CREATE DATABASE logify;
CREATE USER 'logify'@'%' IDENTIFIED BY 'password';
GRANT ALL PRIVILEGES ON logify.* TO 'logify'@'%';
```

The plugin creates its table (`logify_accounts` by default) on start.

### Plugin

1. Put `limboapi-<version>.jar` and `logify-<version>-all.jar` into the proxy's `plugins` folder.
2. Start the proxy once. Logify creates `plugins/logify/config.toml` and refuses logins until it is filled in.
3. Fill in `[database]` and `[discord]` and restart the proxy.

Secrets can be passed through environment variables instead of the file, which keeps them out of git:

| Variable | Overrides |
|---|---|
| `LOGIFY_DISCORD_TOKEN` | `discord.token` |
| `LOGIFY_DATABASE_PASSWORD` | `database.password` |

## Configuration

`config.toml` is created on first start and commented in detail. Keys missing from the file get their default values, so the file keeps working after updates. Changes apply after a proxy restart.

| Section | Contents |
|---|---|
| `[database]` | MariaDB connection, table prefix, pool size |
| `[discord]` | Bot token, registration channel, registration slash command (`/r` by default), bot status |
| `[auth]` | Timeouts, session length, accounts per Discord user, reminder interval, allowed nicknames, attempts per minute |
| `[premium]` | Handling of licensed accounts |
| `[limbo]` | Dimension and game mode of the waiting world |
| `[messages]` | In-game messages in [MiniMessage](https://docs.advntr.dev/minimessage/format.html) format |
| `[discord-messages]` | Slash command descriptions and bot messages with Discord Markdown |

## Commands

| Command | Description |
|---|---|
| `/logify info <nickname>` | Show the linked Discord ID, registration date and last approved login |
| `/logify discord <id>` | List the nicknames linked to a Discord user |
| `/logify unregister <nickname>` | Delete a registration, so the nickname can be registered again |
| `/logify reset-session <nickname>` | Require approval on the next login |

All commands need the `logify.admin` permission.

## Building

```sh
./gradlew build
```

The plugin jar is `build/libs/logify-<version>-all.jar`. Its dependencies (JDA, HikariCP, the MariaDB driver) are bundled and relocated. LimboAPI 1.2.0 is not published to Maven, so the build takes its API from the release jar on GitHub.

`./gradlew runVelocity` starts a local Velocity with LimboAPI and the plugin in `run/`.

Pushing a tag such as `v1.0.0` builds the plugin and attaches `logify-1.0.0-all.jar` to a GitHub release.
