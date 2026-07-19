# Contributing

## Development requirements

- Preserve Java 8 bytecode compatibility. Modern source syntax is provided by Jabel and does not raise the runtime
  requirement.
- Build against the repository's Gradle wrapper and pinned GTNH dependencies.
- Keep changes focused and preserve existing NBT keys, configuration names, packet layouts, and public descriptors.

## Verification

Run these commands before submitting a change:

```powershell
.\gradlew.bat test
.\gradlew.bat spotlessCheck
.\gradlew.bat build
```

Add focused unit tests for pure logic and regression tests for corrected behavior. Forge lifecycle behavior that
cannot run in a plain unit test should be isolated behind a small package-private seam and covered with a server
smoke test.

## Code conventions

- Use domain mutation methods instead of writing public compatibility fields or collections.
- Use `Locale.ROOT` for identifiers, persisted names, permission nodes, and deterministic formatting.
- Missing optional data may fall back silently; malformed persisted/configuration data should log a warning;
  unexpected failures should log an error with an ID or path. Do not use `printStackTrace` or empty catch blocks.
- Restore the interrupt flag when catching `InterruptedException`, and stop or roll back the interrupted operation.
- Use try-with-resources for streams, readers, archive handles, and other closeable objects.
- Add stable integration entry points under `serverutils.api`; retain deprecated bridges when compatibility requires
  them.
