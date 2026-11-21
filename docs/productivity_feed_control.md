# Productivity feed control for X 12.19.1

## Scope

Add an optional, persistent feed-control feature to the Piko patches for X `12.19.1-release.0`. Keep Piko's existing patches and leave X's recommendation and ranking algorithm unchanged.

## Agreed behavior

- X remains launchable at all times; the feature does not block the whole app.
- The feature is enabled from Piko settings. Once enabled, its setting is permanently locked on; the only intended way to remove that lock is to uninstall X.
- The For You feed is empty from 00:00 to 06:00 (device local time).
- The feed refreshes at 06:00, 12:00, and 18:00. Each six-hour window provides 10 minutes of feed allowance.
- Allowance is cumulative within each window and does not carry to the next window.
- Once a window's allowance is exhausted, the user may remain in X and finish the post detail already open, but cannot open another post or its comments and cannot refresh the feed until the next window.
- Time spent in post details/comments consumes allowance at half speed.
- Time spent in X's in-app browser on external websites does not consume allowance.
- Hide recommendation modules appended after the comments on a post.
- Preserve the order and recommendations supplied by X.

## Existing Piko support to reuse

Piko already has a `Hide recommendation items` patch for X. It exposes `hideDetailedPost`, which removes `tweetdetail*` entries from the legacy timeline parser. The new feature should reuse or enable that behavior for the post-detail recommendations after validating it on the target APK.

## Target and baseline

- X version: `12.19.1-release.0`.
- Piko baseline: upstream `main`, release `v3.11.1`, commit `297519632c897d19e9f0df0c39e5b315d1a7390b`.
- Local implementation branch: `codex/productivity-feed-control`.
- Supplied APKM: `com.twitter.android_12.19.1-release.0-312191000_4arch_7dpi_24lang_39a6836c92f41a93acaa05d052b8d62c_apkmirror.com.apkm` (SHA-256 `E288ABB4B3BDF94C3D0397137B42CBF27A8F90049276166AEE0383AD694DBD2C`).

## APK inspection

- The APKM contains a base APK and ABI/density/language splits; use the original APKM when patching rather than treating the extracted base APK as a replacement.
- Piko's target matches `12.19.1-release.0`.
- In the base APK, the For You timeline controller is `Lcom/twitter/app/home/t;`. Its `c0()V` method checks timeline type `0x11` before applying the For You scroll-restoration logic, and its `U(Lcom/twitter/model/common/collection/e;)V` method renders timeline data.
- The manifest identifies `com.twitter.app.main.MainActivity`, `com.twitter.tweetdetail.TweetDetailActivity`, `com.twitter.browser.BrowserActivity`, and `com.twitter.android.webview.WebViewActivity`. The controller uses these to distinguish For You use, post details, and browser time.

## Implementation status

- Added a Piko settings switch. It latches on when enabled and cannot be switched off or cleared by Piko's reset/import controls; uninstalling X removes the app's local settings data.
- The time allowance is stored in Piko's private preferences and is scoped to local-time windows 06:00–12:00, 12:00–18:00, and 18:00–00:00. Each gets 10 minutes; post detail consumes at half speed; recognized X browser activities do not count.
- At the start of an available window, the patch calls X's existing `HomeTimeline.P()` timeline action once and guards that same action when the window is closed. The exact runtime effect still needs confirmation on a patched install.
- When a window is closed, the For You RecyclerView is hidden, which also disables scrolling, opening feed posts, and pull-to-refresh. An already-open `TweetDetailActivity` remains open; new post details and `onNewIntent` navigation are guarded.
- The productivity patch depends on Piko's existing `Hide recommendation items` patch. Its `tweetdetail*` filter stays active while this productivity setting is locked on, using X's existing response ordering and ranking.
- Remaining validation: compile/package the custom Morphe patch and check its hooks against the target APKM. The local build currently stops before compilation because the Morphe Gradle plugin needs GitHub Packages authentication. The Android OS can always clear app data or a user can change the device clock; this in-app latch does not claim to resist those external actions.
