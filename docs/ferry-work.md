# Backgrounding: `:ferry-work`

`:ferry-work` is an optional module that wraps `RepoDownloader.download` in a `CoroutineWorker`,
with a host-controlled foreground notification, WorkManager's own retry and backoff, and a
uniqueness guarantee `:ferry` itself cannot provide. It depends on `:ferry`, and nothing in `:ferry`
depends on it. A host with no interest in WorkManager takes only `:ferry` and never notices
`:ferry-work` exists.

```kotlin
dependencies {
    implementation("dev.thuat:ferry-work:0.2.0")
}
```

Your notification, your channel, your wording, registered once via WorkManager's own factory hook:

```kotlin
class App : Application(), Configuration.Provider {
    override val workManagerConfiguration = Configuration.Builder()
        .setWorkerFactory(
            RepoDownloadWorkerFactory { repoId, progress ->
                // progress is null before the first update arrives
                buildNotification(repoId, progress)
            },
        )
        .build()
}
```

Then enqueue:

```kotlin
WorkManager.getInstance(context)
    .enqueueRepoDownload(repoId = "google/gemma-2-2b-it", into = filesDir, notificationId = 42)
```

Ferry moves bytes. It never decides what your users read while it does.

## Enqueueing twice is a no-op, not a race

The second call keeps the running work instead of replacing it.

Replacing it would cancel the in-flight `download` call, and coroutine cancellation is cooperative,
stopping at the next checkpoint rather than instantly. A replacement's own `download` call could
therefore start writing into the same staging directory while the cancelled one's last write is still
landing. That is the same concurrent-download hazard `download`'s own KDoc already warns against for
two ordinary calls, self-inflicted here by cancelling one enqueue's work out from under the request
about to replace it. `KEEP` avoids it by never starting the second call at all while the first is
still running.

## Why a separate module, not a feature of `:ferry`

`:ferry`'s own `checkEmbeddable` Gradle task fails the build the moment `androidx.work` appears on
its classpath, and it is wired into `check`, so it is enforced rather than merely intended.

The reason is the README's "Why this exists" table. Alibaba's MNN backgrounds downloads with a
plain foreground `Service`; Google's AI Edge Gallery backgrounds them with a `CoroutineWorker`. A
`:ferry` that required WorkManager would rule MNN's approach out by construction, which is the exact
failure this project exists to avoid for a *hub*, applied one layer up to a *backgrounding strategy*.

Splitting backgrounding into its own module, rather than gating it behind a feature flag inside
`:ferry`, is what makes the exclusion real: a flag can still be compiled in and inspected on the
classpath, but a dependency that is never declared cannot be.

## Design decisions

Full reasoning lives in `RepoDownloadWorker`'s own KDoc; this is the shape of each choice.

- **Input and output.** `repoId`, an absolute `into` path and a notification id go in as
  `androidx.work.Data`, primitives only. On success, the committed directory's own absolute path
  comes out, not recomputed from the inputs by this module. On failure, only
  `InsufficientSpaceException` survives in detail (a reason code, its message, and its
  `SpaceReport`'s numbers). Every other failure loses its exception type and carries no `Data`
  while it is still retrying. See Retry for when it stops.
- **Retry.** `InsufficientSpaceException` fails outright: a full device is exactly as full on the
  next attempt. Everything else retries, including a `VerificationException`, because
  `RepoDownloader` exposes no richer taxonomy than these two named exceptions plus a bare
  `IOException` for everything else, so a dropped connection and a permanently-corrupt file are not
  distinguishable by type from outside `:ferry`. That retry is bounded, not indefinite: exponential
  backoff makes attempts less frequent, never fewer, so a case that will in fact never pass needs
  an actual ceiling. `RepoDownloadWorker.MAX_RETRY_ATTEMPTS` (5) is that ceiling, read off
  WorkManager's own `runAttemptCount`. Past it, the worker fails with `REASON_RETRIES_EXHAUSTED` in
  the same `KEY_FAILURE_REASON` field `InsufficientSpaceException` uses, rather than a parallel one.
- **Progress.** `RepoProgress.Downloading` fires once per read buffer. `RepoDownloadThrottle`, a
  direct port of `:sample`'s own `DownloadingThrottle` rather than a shared dependency (the only
  module both could share is `:ferry`), gates both `setProgress` and the notification update to at
  most once a second, always letting a file's last byte through.
- **Uniqueness.** `enqueueRepoDownload` wraps `WorkManager.enqueueUniqueWork` with
  `ExistingWorkPolicy.KEEP`, keyed by repo id: a second enqueue for a repo already running is
  dropped rather than cancelling and restarting it, which would forfeit whatever had already
  downloaded. This closes `known-limitations.md`'s concurrent-download entry *within WorkManager*.
  A host calling `RepoDownloader` directly, outside this enqueue path, is still responsible for
  serialising itself the way that entry has always said.
- **Foreground.** The notification's text, icon and actions are supplied by the host through
  `RepoDownloadNotifications`, never hardcoded by Ferry, the same reasoning that keeps `:ferry`
  itself free of a UI framework. `:ferry-work`'s own manifest declares
  `android.permission.FOREGROUND_SERVICE_DATA_SYNC` and overrides WorkManager's
  `SystemForegroundService` with `android:foregroundServiceType="dataSync"`, verified directly
  against work-runtime 2.11.2's own merged manifest (which declares neither), so a host does not
  need to know that internal class name exists at all.
