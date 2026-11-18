# PodAnswer

PodAnswer is an Android app for sharing podcast episodes from Podcast Republic, transcribing the MP3 with Gemini 4 Hydrogen, and answering the questions or themes promised by the episode title.

## What works

- Receives Podcast Republic's `text/plain` share payload.
- Extracts title, show, duration, publication date, episode URL, MP3 URL, description, and RSS URL.
- Also accepts a directly shared or manually selected audio file.
- Downloads the MP3 and sends it to the Gemini Files API.
- Requests a timestamped, speaker-labelled transcript and evidence-based answers in structured JSON.
- Supports audio up to six hours: processes 20-minute sections, then combines their answers and summaries into one episode analysis while retaining the full transcript.
- Labels each title topic as answered, partly answered, or unanswered.
- Plays the local audio from every evidence or transcript timestamp.
- Saves the analysis and audio locally, supports transcript search, retry, deletion, and text export.
- Encrypts the Gemini API key with Android Keystore.

## Export to Podcast Transcriber via OneDrive

Each finished analysis has a cloud button (**Nach OneDrive exportieren**); the cloud button on the start screen exports all finished analyses into one file. Android's save dialog opens: choose OneDrive and a folder such as `PodAnswer`. The app writes a `*.podanswer.json` file with the episode metadata, the timestamped transcript, the summary, and every answered title question with its evidence. Local file paths are not included.

On the PC, Podcast Transcriber imports these files from the synced OneDrive folder (**Settings → PodAnswer folder**, then **Import from PodAnswer**). Exporting the same episode again updates it there instead of creating a duplicate.

## Build

Requirements: Android Studio with JDK 17 and Android SDK 35.

1. Open this directory in Android Studio.
2. Let Gradle sync and install requested SDK components.
3. Run the `app` configuration on Android 10 or newer.

The included wrapper uses Gradle 8.9. No API key is included in the source.

## Signed release builds

Release builds require the following environment variables and fail if signing is missing:

- `ANDROID_KEYSTORE_PATH`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The GitHub Actions workflow reads the same values from repository secrets. It runs only when started manually or when a `v*` tag is pushed, so ordinary source-code pushes consume no Actions minutes. A manual workflow run publishes a verified, signed release APK as the `PodAnswer-signed-release` workflow artifact with seven-day retention. Pushing a tag such as `v0.1.0` additionally creates a GitHub Release containing the APK and SHA-256 checksum.

The release key must be retained permanently. Future APK updates must be signed with the same key to install over an earlier version.

## First use

1. Create a Gemini API key in Google AI Studio.
2. Open PodAnswer settings and save the key.
3. In Podcast Republic, share an episode and select PodAnswer.
4. Verify the detected title and select **Podcast analysieren**.

The audio is sent to Google's Gemini API. After a successful analysis, PodAnswer requests deletion of the uploaded Gemini file. The downloaded MP3 and generated result remain in the app's private local storage until the analysis is deleted.

For episodes longer than 20 minutes, PodAnswer exports one temporary M4A section at a time, uploads and analyzes it, then deletes the temporary section and requests deletion of the Gemini upload, including on failure. Transcript and evidence timestamps are shifted to positions in the original audio, which remains available for playback and export. Speaker labels include their section number because separate analyses cannot reliably identify the same speaker across sections. A final text-only request combines all section summaries and answers; it does not regenerate or shorten the transcript. Longer episodes require more API requests and processing time. Audio longer than six hours, or with an unreadable duration, is rejected before upload.

## Architecture

- Kotlin and Jetpack Compose
- Single activity with Android share intents
- Local JSON repository with atomic writes
- OkHttp for podcast download and Gemini REST calls
- Android Keystore backed AES-GCM storage for the API key
- Android `MediaPlayer` for timestamp playback

## Current operational limit

The analysis coroutine runs in the app process. Keep the app open while a podcast is being processed. If Android stops the process, open the failed item and retry; an existing MP3 is reused.
