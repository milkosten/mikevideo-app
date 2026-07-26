# MikeVideo app — auth via account.osmike.com (OAuth 2.0)

The full integration spec lives in the **cloud** repo (that's where auth is enforced):
`mikevideo-cloud/docs/integrate_with_account_osmike_com.md`, and the ecosystem design is
`mikeos-architecture/docs/ACCOUNT-OSMIKE-OAUTH-PLAN.md`. Read those. This note is the **app half**.

## What changes in `mikevideo-app`

Today the app sends its per-app **hive key** (`HiveIdentity.agentKey`) as `X-API-KEY` to
`mikevideo-cloud`. Target: send a **short-lived OAuth Bearer token** obtained from the on-device
daemon — the app stores no long-lived credential.

```kotlin
// The daemon is the on-device OAuth "token agent": it holds the device refresh token
// (from onboarding's Device Authorization Grant) and mints per-app access tokens.
// GET https://127.0.0.1:7743/api/auth/token?aud=mikevideo&scope=video.read+video.write
//   Authorization: Bearer <loopback daemon token>  ->  { access_token, expires_in }
suspend fun mikeVideoAccessToken(): String? =
    daemonGet("/api/auth/token?aud=mikevideo&scope=video.read+video.write")
        ?.optString("access_token")

// on every mikevideo-cloud request:
//   .header("Authorization", "Bearer $token")     // replaces .header("X-API-KEY", agentKey)
// cache until ~expires_in; on 401, re-fetch and retry once.
```

## Sequence (non-technical for the user — invisible)
1. Onboarding: the user approved this phone once at account.osmike.com (pairing = OAuth device grant).
2. The daemon now holds the device refresh token and issues scoped access tokens on request.
3. MikeVideo asks the daemon for a `mikevideo`-scoped token and sends it as `Bearer`. No key pasted, nothing stored, no re-login.

## Interim (do this order, no coordinated deploy needed)
- The cloud's **dual-auth** accepts **both** Bearer and the legacy `X-API-KEY`.
- So the app can **keep sending `X-API-KEY` today** and flip to `Bearer` only once the daemon's
  `GET /api/auth/token` endpoint ships. Nothing breaks in between.
