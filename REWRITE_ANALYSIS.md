# ServerUtilities Codebase Analysis (for near-term improvement + eventual rewrite)

## Snapshot
- Language/build: Java + Gradle (legacy Forge/FML style mod lifecycle).
- Approximate size: **592 Java files** under `src/main/java/serverutils`.
- Major concentration areas:
  - `serverutils/lib`: 243 files (internal framework / utility layer)
  - `serverutils/command`: 93 files
  - `serverutils/client`: 53 files
  - `serverutils/events`: 46 files
  - `serverutils/net`: 41 files

## What the architecture looks like today

### 1) Centralized bootstrap with broad responsibility
`ServerUtilities` delegates lifecycle methods to `ServerUtilitiesCommon` via proxy.

`ServerUtilitiesCommon` currently acts as a **god object**:
- global registries/maps for config providers, synced data, actions, reload handlers
- event bus registrations
- chunk loading setup
- built-in value provider and admin action registrations

This centralization makes startup behavior hard to reason about and tightly couples unrelated modules.

### 2) Feature set is command-heavy and configuration-heavy
`ServerUtilitiesCommands` conditionally registers a large set of commands from config toggles. This indicates:
- broad feature surface
- many feature flags
- high chance of hidden coupling between command handlers, permissions, teams, sync data, and network messages

### 3) Internal “platform library” is embedded in the same repo/module
The large `serverutils/lib` subtree suggests the project includes its own framework abstractions (data model, GUI toolkit, utilities, config system, net helpers).
This is powerful but increases rewrite risk because the app layer and framework layer evolved together.

### 4) Large classes likely indicate mixed concerns
Several files are 600–900 LOC (e.g., `ServerUtilitiesConfig`, `GuiEditNBT`, `Universe`, `ForgeTeam`, `Ranks`) which is a likely indicator of:
- weak boundaries
- mixed state + behavior + serialization + IO in single files
- difficult testability and onboarding

## Key technical risks to address before rewrite

1. **Implicit global state**
   - static maps/registries across core classes can create ordering and lifecycle bugs.
2. **Feature coupling through shared data structures**
   - command, team, ranks, chunk, and net features likely communicate through common globals.
3. **Protocol drift risk**
   - many `Message*` classes in `net` package imply a custom packet protocol that may be brittle without schema/version contracts.
4. **Config surface complexity**
   - very large config class means migration/compatibility work is non-trivial.
5. **Legacy platform constraints**
   - older Forge/FML lifecycle patterns may constrain modernization strategy.

## Pragmatic improvement plan (before full rewrite)

### Phase 0: Baseline and safety rails (1–2 weeks)
- Create an architectural decision log (`docs/adr/`).
- Add module ownership map (who owns command, team, ranks, net, config).
- Add smoke tests for startup + command registration + packet decode/encode for critical paths.
- Add “golden” serialization tests for important persisted objects and config nodes.

### Phase 1: Boundaries without behavior change (2–4 weeks)
- Introduce clear service interfaces around:
  - command registration
  - permission/ranks
  - team management
  - sync/network dispatch
  - config access
- Move static registries behind injectable/service-locator facades.
- Split very large files by concern (read/write model, orchestration, UI, serialization).

### Phase 2: Strangler migration prep (3–6 weeks)
- Define a target package/module layout (`core`, `platform`, `features/*`, `api`).
- Create compatibility adapters so new services can coexist with legacy globals.
- Introduce explicit DTOs/events for cross-feature communication.
- Version network messages and write compatibility tests.

### Phase 3: Rewrite execution (incremental)
- Rewrite one vertical slice at a time (e.g., teleport commands + permissions + net messages), ship, then proceed.
- Keep old and new implementations behind feature flags where possible.
- Preserve data formats or provide migration tooling.

## Recommended target architecture for rewrite
- **Core domain**: teams, ranks, claims, teleport state, tasks.
- **Application services**: use-case orchestration (commands/events call services, not globals).
- **Infrastructure adapters**: Forge hooks, packet transport, persistence, scheduler.
- **Presentation**: command handlers + GUIs as thin adapters.
- **Contracts**: versioned config schema + versioned network protocol.

## Concrete first refactors to start now
1. Extract `CommandRegistryService` from `ServerUtilitiesCommands`.
2. Extract `BootstrapRegistry` from `ServerUtilitiesCommon` for startup registration logic.
3. Split config definition vs config access from `ServerUtilitiesConfig`.
4. Add packet contract tests for top 10 most used `Message*` classes.
5. Replace direct static map mutations with dedicated registry classes.

## Suggested success metrics
- Startup path cyclomatic complexity reduced by X%.
- Number of static mutable maps reduced by X.
- % of commands routed through service interfaces.
- Packet compatibility test coverage for critical messages.
- Mean time to add a new command/feature reduced.

## Notes
This document is intentionally based on structural review and code topology, not runtime profiling. A deeper phase should include profiling (tick impact, IO hotspots, packet volume) before finalizing rewrite scope.
