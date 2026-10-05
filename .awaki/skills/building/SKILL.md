---
name: building
description: Use before saying any change to this app is done - the Gradle gate, and what a passing build must look like
---

# Building and gating a change

Nothing is finished until the gate passes. Run it, do not assume it:

```
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain --no-parallel
```

- `--no-parallel` is not cosmetic. Without it the Kotlin daemon and the resource
  tasks race on Windows and you get a failure that no code caused.
- The build must come back **warning-free**. `w:` lines are treated as failures here:
  an unused import today is a merge conflict in three weeks, and a deprecation warning
  is a future breakage with a date on it. Filter with `grep -E "^(w:|e:|BUILD|FAILURE)"`.
- A full run is three to five minutes. While iterating on one file,
  `:app:compileDebugKotlin` is enough; the full gate still has to run before the work
  is called done.
- Gradle on Windows occasionally fails with a file lock, or Kotlin reads back a class
  file it just wrote half. That is the daemon or a poisoned build cache, not the code:
  retry once before believing it, and say which you got.

## Commits

Commit each unit of work once it passes, without being asked, and never push unless
asked. The subject says what changed for whom, not which files were touched:

```
feat(skills): let a project write down how its work should be done
```

The body explains why it is built this way - the constraint or the failure that
decided it - because the diff already shows what.
