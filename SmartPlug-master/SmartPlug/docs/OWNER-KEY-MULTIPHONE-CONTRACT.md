# Owner Key and Multi-Phone Contract

**Status:** implementation contract — not yet deployed  
**Version:** proposed API `1.1` additions, existing API `1.0` retained  
**Goal tracker:** [MATURITY-GOAL-TRACKER.md](MATURITY-GOAL-TRACKER.md)

## Intent

Every operational SmartPlug read and mutation requires an authenticated device credential. A SmartPlug can nevertheless be used from more than one phone. Each phone stores its own credential and can import the existing safe profile after explicit approval. The solution must not make the existing **Tambah SmartPlug** onboarding flow handle an already paired device.

## Roles

| Role | Can read measurements/status | Can control relay/timer/schedule | Can connect/disconnect server | Can issue/revoke other phone credentials | Can factory reset / Wi-Fi access change |
|---|---:|---:|---:|---:|---:|
| `owner` (first phone) | Yes | Yes | Yes | Yes | Yes |
| `member` (additional phone) | Yes | Yes | Yes | No | No |

The first release creates exactly one `owner` credential. Additional phones receive `member` credentials. This preserves a recovery authority if a member phone is lost. Changing ownership is a later, separately reviewed feature; factory reset remains the physical fallback if the only owner phone is lost.

## Security and storage rules

1. A credential is generated from 32 random bytes and returned only once over the authorized local request.
2. Firmware stores a credential ID, role, salt, and SHA-256 HMAC verifier—never the plaintext credential.
3. Android stores the credential only through the existing Keystore-backed `SecureTokenStore`; Room, logs, notifications, and UI never receive it.
4. The vault has a hard maximum of **four** active credentials (one owner plus three members). A full vault returns `credential_limit_reached` rather than silently replacing a phone.
5. The vault is a LittleFS A/B record: sequence number, CRC32, alternating target slot, flush, close, and read-back verification. A failed write leaves the previous valid slot authoritative.
6. Existing single-owner verifier fields remain untouched as a compatibility fallback until a verified vault migration has succeeded. The first boot after update migrates it to `owner-legacy`; failed vault migration must preserve existing single-phone access rather than resetting the device.
7. Factory reset clears Wi-Fi/server settings, the legacy verifier, and both credential-vault slots; it does not silently retain an old authorized phone.

## Additional-phone flow

```text
HP 1 (owner)                         SmartPlug                         HP 2 (new phone)
--------------                       ---------                         ----------------
Open Device access -> Add phone
POST /access/invitations ---------> create one-time, 6 digit invite
display code + 5 minute timer
                                                                      discover via mDNS
                                                                      select existing SmartPlug
                                                                      enter one-time invite
                                      <------ POST /access/enroll ----
verify invite + create member credential
return credential + safe profile -------------------------------> store in Android Keystore
invalidate invite; audit event
```

The invite is bound to its issuing owner credential, device, and an expiry. It is single-use. It has no value after expiry, owner revocation, or factory reset. The user must initiate it from HP 1; merely joining the same Wi-Fi never grants a second phone access.

## API additions

All endpoints return the current flat error envelope while API major version remains `1`. New structured fields are additive.

| Route | Caller | Request | Response / failure |
|---|---|---|---|
| `GET /api/v1/access/profile` | any credential | — | safe profile: device identity, device display name, network reachability, integration mode/server ID, relay/timer/schedule; never Wi-Fi or MQTT passwords |
| `POST /api/v1/access/invitations` | owner | `{ "role":"member" }` | `invite_code`, `expires_in_s`; `403 owner_required`, `409 invite_already_active` |
| `POST /api/v1/access/enroll` | unauthenticated but LAN-only | `{ "device_id":"…", "invite_code":"123456" }` | one-time `credential`, `credential_id`, `role`, and safe profile; `401 invalid_or_expired_invite`, `409 credential_limit_reached` |
| `GET /api/v1/access/credentials` | owner | — | credential IDs/roles only, no verifiers/tokens |
| `DELETE /api/v1/access/credentials/{id}` | owner | — | removes one non-owner credential; `409 cannot_revoke_last_owner` |

`/access/enroll` applies rate limiting (five invalid attempts then a timed lock) and returns the same generic error for a wrong code, wrong device ID, expired invite, or unavailable invitation. It must never disclose whether a device is paired.

## Protected routes

After implementation, every operational route requires either:

- a valid `Authorization: Bearer <credential>`; or
- a valid browser admin session where browser administration is still supported.

This covers `status`, `health`, all `measurements/*`, relay, timer, schedule, energy reset, MQTT settings, Wi-Fi settings, and factory reset. Pairing setup routes remain AP-only and use their separate expiring pairing token.

The mDNS service may reveal only product/device identity needed for discovery. It does not contain status, measurements, SSID, credential state, or invitation state.

## Android UX boundary

- **Tambah SmartPlug** remains only for a factory-reset/unpaired SmartPlug and Wi-Fi onboarding.
- A new **Tambahkan SmartPlug yang sudah ada** action discovers mDNS devices and starts the invite-code enrollment; it never asks for Wi-Fi password.
- The imported profile is stored as `Direct` or `Server` according to the safe profile returned by the device. It does not assume a saved ServerSmartPlug profile is already available on the new phone; if missing, server monitoring is shown as unavailable until that server is separately registered.
- Device Access shows only owner-visible generated invitations and member credential labels/IDs; it never renders a credential value after creation.

## Mandatory verification matrix

1. Existing HP 1 Direct onboarding still produces one owner credential and protected Direct monitoring works.
2. An unauthenticated caller receives 401 on status, health, and measurements; browser session remains functional after explicit login.
3. HP 1 creates invite; HP 2 joins using it and loads the same safe profile without knowing Wi-Fi credentials.
4. Reusing, guessing, and expiring an invite fail without exposing enrollment state; rate limit works.
5. HP 2 controls permitted settings; HP 2 cannot create/revoke credentials, change Wi-Fi, or factory reset.
6. Revoke HP 2: HP 2 gets 401 while HP 1 remains operational across reboot.
7. Simulated failed vault write/corrupt newest slot selects prior valid slot; legacy single-owner fallback remains usable.
8. Factory reset clears all credentials; ordinary reboot does not.
