# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a fork of ACINQ Eclair v0.13.1 customized for the **BitEver (BEC) L1 chain** as an LSP (Liquidity Service Provider) server. It is written in Scala 2.13, uses Akka actors, Maven for building, SQLite for storage, and requires OpenJDK 21.

## Build Commands

```bash
# Build without tests (common during development)
mvn clean install -DskipTests

# Full build with tests
./mvnw package

# Run all tests
./mvnw test

# Run a specific test class
./mvnw test -Dsuites=*<TestClassName>

# Run tests with N threads
./mvnw -T <N> test

# Build only eclair-node module
./mvnw package -pl eclair-node -am -Dmaven.test.skip=true

# Install eclair-core into local maven repo
./mvnw clean install -pl eclair-core -am -Dmaven.test.skip=true
```

## Running the Node

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
cd eclair-node/target/eclair-node-0.13.1-9830aa6
java -cp "lib/*" fr.acinq.eclair.Boot <plugin_path>
```

## Module Structure

- **`eclair-core`**: Core Lightning protocol library. Entry point for bootstrapping is `Setup.scala`. All protocol logic lives here.
- **`eclair-node`**: Server daemon built on eclair-core; exposes JSON RPC and WebSocket endpoints. Config at `eclair-node/src/main/resources/application.conf`.
- **`eclair-front`**: Cluster-mode front-end server that handles peer connections and delegates to backend nodes.

## Architecture

Eclair is actor-based (Akka). Every peer connection, channel, and payment attempt is a separate actor instance. Key actors:

| Actor | Role |
|---|---|
| `Switchboard` | Creates/deletes `Peer` actors |
| `Peer` | P2P connection to a remote Lightning node |
| `Channel` | Individual payment channel FSM (in `channel/fsm/`) |
| `Register` | Maps channel IDs to actor refs (boundary between channel and payment layers) |
| `Router` | Gossip and network graph (BOLT 7) |
| `PaymentInitiator` | Entry point for sending payments |
| `Relayer` | Entry point for relaying HTLCs |
| `PaymentHandler` | Entry point for receiving payments |

Payment flows: `PaymentInitiator` → `MultiPartPaymentLifecycle` → `PaymentLifecycle` → `Register` → `Channel`.

Actors communicate via direct messages or through a shared `EventStream`.

## BitEver-Specific Patches

These are the key customizations vs. upstream Eclair; be careful not to revert them:

1. **Chain sync validation bypassed** (`Setup.scala:203–207`): The three `assert` checks for `initialBlockDownload`, `verificationProgress`, and header/block sync are commented out. This allows the node to start against the BEC chain which has lower cumulative chain work than Bitcoin mainnet.

2. **Fee provider** (`Setup.scala:250–256`): The BEC chain registers under a non-`regtest`/non-`signet` chain hash, so it uses `BitcoinCoreFeeProvider` with a `ConstantFeeProvider` fallback. The `on-chain-fees.default-feerates.*` config values are forced to ensure stable fee estimates when the chain has low mempool data.

3. **Phoenix FCMToken handling** (`Peer.scala:610–611`): Unknown messages with tag `35017` (Phoenix FCMToken) are silently accepted without a response, enabling Phoenix wallet compatibility.

4. **Phoenix features**: `PhoenixZeroReserve` and `SimpleTaprootChannelsPhoenix` feature bits are supported. The `PhoenixSimpleTaprootChannelCommitmentFormat` signs HTLC transactions at the commit feerate so Phoenix can broadcast without additional wallet inputs.

5. **Swap-in protocol** (`InteractiveTxTlv.scala`, `InteractiveTxBuilder.scala`): Custom TLV types (`SwapInParams` at tag 1109, `SwapInNonces` at tag 101, `SwapInUserPartialSigs`/`SwapInServerPartialSigs`) support Phoenix's taproot musig2 swap-in flow.

## Configuration

The main configuration is in `eclair-core/src/main/resources/reference.conf`. Key settings to configure for BEC:

- `eclair.chain` — set to `"regtest"` to use the BEC chain hash (BEC's genesis block matches regtest hash, or override via `eclair.chainHash` if patched)
- `eclair.bitcoind.*` — RPC/ZMQ connection to the BEC L1 node
- `eclair.on-chain-fees.default-feerates.*` — forced default feerates to handle low-mempool-data scenarios

## CI/CD

GitHub Actions workflows are in `.github/workflows/`. The primary build is `build.yml` which runs `mvn clean install -DskipTests` on push.
