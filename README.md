# hytale-mod-template-advanced

Advanced mod starter that demonstrates several systems working together:

- profile progression (coins, xp, levels)
- permission grants from starter packs and events
- bounded event queue with retries/drops
- signed envelope flow with replay protection
- source lockouts on repeated security failures
- snapshot persistence and runtime audit trail

Main class: `net.hytaledepot.templates.mod.advanced.AdvancedModPlugin`

## Commands

- `/hdadvancedmodstatus`  
  Runtime status, counters, queue depth, and latest audit line.
- `/hdadvancedmoddemo <action> [args...]`  
  Runs one advanced action.
- `/hdadvancedmodflush`  
  Persists the current runtime snapshot immediately.

## Demo actions

Recommended sequence:

1. `/hdadvancedmoddemo grant-starter`
2. `/hdadvancedmoddemo add-xp 180`
3. `/hdadvancedmoddemo transfer market 50`
4. `/hdadvancedmoddemo queue-event economy.credit market:30`
5. `/hdadvancedmoddemo process-event`
6. `/hdadvancedmoddemo sign-envelope sync.delta coins=120`
7. `/hdadvancedmoddemo verify-envelope`
8. `/hdadvancedmoddemo lock-source`
9. `/hdadvancedmoddemo unlock-source`
10. `/hdadvancedmoddemo info`

Extra actions:

- `queue-event permission.grant user:permission.node`
- `queue-event security.lock user`
- `flush`
- `audit`

## Snapshot file

The mod writes runtime counters to:

`<plugin-data-dir>/advanced-mod-state.properties`

## Build

1. Ensure `HytaleServer.jar` is available (workspace root, `HYTALE_SERVER_JAR`, launcher path, or `libs/`).
2. Run:

```bash
./gradlew clean build
```

3. Copy `build/libs/hytale-mod-template-advanced-1.0.0.jar` into `mods/`.

## License

MIT
