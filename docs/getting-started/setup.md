# Project setup

Every step from a Kotlin Multiplatform project to a first offline read and write.

> **Not written yet.** This page is a placeholder in the planned site. The headings below are its
> outline; the content will be written for 0.1.0, once the API it describes has settled.

## Requirements

## Add the dependency

## Add a Ktor engine per target

## Create the supabase-kt client

## Create the offline client

## Tell it about connectivity

## Sync the queue

## First read

## First observed read

## First write

## Sign-out

## Logging

## Android manifest

The library's manifest declares `android.permission.ACCESS_NETWORK_STATE`, for the connectivity
monitor that tells it when the network comes back. It is merged into every app that uses the
library, so it appears in your app's merged manifest and its store listing's permissions. Give
`install(Offline) { networkAvailability = … }` your own signal and the monitor is not used; the
permission is declared all the same.

## Next steps
