# ServerUtilities Architecture

ServerUtilities is a Minecraft Forge 1.7.10 mod and a compatibility-oriented backport of FTB Utilities, FTB
Library, and Aurora. Changes must preserve existing worlds, NBT data, network packet layouts, configuration keys,
and integration entry points unless a migration is explicitly designed.

## Lifecycle and state ownership

`Universe` is the server-lifecycle facade. Forge creates it before the server starts, loads persistent state after
the primary world is available, and closes it during shutdown. The object is registered on the Forge event buses,
so lifecycle and annotated event methods remain on the facade even when implementation details are delegated to
package-private components.

Players and teams belong to one `Universe`. Mutations must use their domain methods so cache invalidation, events,
and dirty-state propagation happen together. Read-only views are provided for inspection. Deprecated public
collections remain only as compatibility bridges and must not be used by project code.

## Persistence compatibility

Universe, player, and team data is stored as compressed NBT. Existing key names, event ordering, team UIDs, and
save order are compatibility contracts. Loading/import code may hydrate state without producing normal gameplay
events; ordinary mutations must use the public domain operations and mark affected objects dirty. NBT writes use a
sibling temporary file and atomic replacement where the platform supports it; dirty state is cleared only after a
successful replacement.

Backup restoration validates every archive entry against the chosen restore root. Both ZIP implementations share
the same path and filtering policy, while backend-specific code only adapts archive entry access. Restores extract
into an isolated staging directory before live files move, and a rollback journal restores every moved path if the
install does not complete. The journal is forced to disk before live mutations and recovered on the next restore if
the process previously stopped mid-transaction. Entry-count, extracted-size, free-space, link, and selected-world
boundaries are enforced by the shared archive layer.

## Threads and networking

Minecraft and Forge state is owned by the server thread unless a class explicitly documents otherwise. Background
backup, image, web, and file-I/O tasks must not mutate game state directly. Interrupted operations restore the
thread interrupt flag and stop or roll back their work. Backup runs share a single lifecycle token across threaded
and synchronous modes, and each run owns its original world save-state snapshot. Packet field order and NBT field
names are wire contracts.

## Supported integrations

New integrations should use `serverutils.api.ServerUtilitiesRegistry`. Its checked registration, duplicate-ID,
lookup, and read-only-view behavior are supported facade contracts. Extension value types retain their existing
compatibility guarantees; merely appearing in a facade signature does not make all of their implementation details a
stability promise. The facade is distributed in the main mod artifact, not as a standalone API-only artifact.
Supported extension points include reload handlers, team and admin actions, configuration value providers,
synchronized data, invsee inventories, and posted player/team/universe events.
