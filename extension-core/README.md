# Extension package storage (M1)

This Android-independent JVM module imports standard ZIP packages with MV2/MV3 metadata. It does not execute scripts, create extension pages or workers, expose browser APIs, or grant permissions. Every inspection and installation reports `NOT_RUNNABLE_RUNTIME_PENDING`.

```kotlin
val repository = ExtensionRepository(File(appFilesDirectory, "extensions"))
val review = repository.inspect(privateCachedZip)
// Present review.manifest and obtain the user's explicit import action.
val installed = repository.install(privateCachedZip, review)
val persisted = repository.list()
repository.uninstall(installed.installationId)
```

The caller should copy a selected document into its private cache with the archive size limit, keep it there while review is open, and delete it when review finishes or is cancelled. `inspect` does not create or modify repository files. `install` copies into private staging, revalidates the bytes and manifest against the review, and publishes a new randomly generated installation ID through a directory rename. A manifest ID, key, name, or matching version never authorizes replacement. Updates are not implemented.

`ExtensionPackageException.error` distinguishes malformed archives/manifests, unsafe paths, duplicate entries, missing resources, resource limits, changed reviews, invalid installation IDs, and storage errors. Declared permissions, optional permissions, host access, content scripts, background declarations, and entry pages are available in the manifest model. `rawJson` retains declarations not modeled separately. This is bounded package validation, not a complete Chrome manifest schema implementation.

Default `PackageLimits`:

| Limit | Default |
| --- | --- |
| Archive bytes | 64 MiB |
| ZIP entries | 4,096 |
| Actual expanded bytes | 128 MiB |
| Actual single-file bytes | 32 MiB |
| Manifest or default locale JSON | 1 MiB |
| Actual expanded/compressed ratio per entry | 1,000 |

Paths are limited to 4,096 UTF-8 bytes, 255 bytes per component, and 64 components. Traversal, absolute paths, backslashes, control characters, conflicting paths, symbolic links, and special files are rejected. Central and local ZIP records must agree; actual expanded sizes and CRCs are checked. Standard stored/deflated ZIPs are supported. ZIP64, split/encrypted archives, prefixed containers, and CRX files are rejected.

JSON nesting, localization output, and wildcard matching work are bounded independently of ZIP limits. Referenced entry scripts, styles, pages, icons, locale files, managed schemas, rule sets, and declared accessible resources must be present inside the package. Missing capabilities never become working API stubs.

Each published installation contains `metadata.json` and `package/`. A process monitor plus an OS file lock serializes repository operations across instances and processes. On the next list/import/uninstall operation, orphan staging directories left by a killed process are removed while holding that lock. The lock is released by the OS on process death, so recovery cannot remove a concurrent active import. Corrupt installed records are excluded from listing; uninstall accepts only the generated ID and does not follow symbolic links.

Run tests and formatting inside the `dev` Distrobox using JDK 21:

```sh
distrobox enter -n dev -- env JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  ./gradlew :extension-core:test :extension-core:ktfmtCheck
```
