---
name: testing
description: Use when adding or fixing a unit test in this repo - the Robolectric setup, assertion argument order, and the workspace helpers
---

# Writing a unit test here

Tests are JVM tests against real files, run under Robolectric. A test that mocks the
filesystem proves the mock works, so it is not written here.

## The file

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThingTest {
  private val ws = newWorkspace("thing")

  @After fun cleanUp() { ws.dispose() }
}
```

The runner is not optional even when nothing looks Android-ish: `org.json` is
implemented by the framework, so a plain JUnit test building a `JSONObject` dies with
"Method put in org.json.JSONObject not mocked".

## Workspace helpers

`newWorkspace(tag)` from `ToolTestWorkspace.kt` gives a real temporary project folder
with a `Project` pointing at it, and `contextFor(ws)` gives the `ToolContext` a tool
needs. Write through `ws.write(rel, text)` and assert on what lands on disk -
`ws.read`, `ws.bytes`, `ws.exists` - because line endings, encodings and what a failed
batch leaves behind are exactly what these tests exist to catch.

`File(context.getDir("agentisco", MODE_PRIVATE), "<store>.json").delete()` in `@Before`
when a store is involved: the Robolectric filesystem survives between test classes in
one run, and a leftover file makes a later test pass for the wrong reason.

## Assertions

The message comes **first**, always:

```kotlin
assertTrue("output was: ${result.output}", result.output.contains("expected"))
```

Two consequences that bite: passing a `List` as the message does not compile, so write
`views.toString()`; and `assertEquals(expected, actual)` order matters for the failure
to read correctly - `expected:<[a]> but was:<[b]>`.

Test names are sentences in backticks, and cannot contain a colon or a quote - the
Kotlin compiler rejects them. Use a dash: `` `a new agent takes its id - and keeps it` ``.

## What a test should claim

One behaviour each, named for the behaviour rather than the method. When a test fails
after a change to production code, decide first whether the code or the claim is wrong,
and never loosen an assertion just to see green - a test that accepts anything has
already been deleted, with more steps.
