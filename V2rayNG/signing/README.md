# Release signing

Every RX VPN APK is signed with one key, and that key never changes.

- **SHA-256:** `845b7323f50757566efeda24c07a6c4f732821d1ee473fbc1ec27998acf39ab8`
- **Alias:** `rxvpn`
- **Type:** PKCS#12, RSA 3072, self-signed, valid to 2061

## Where it is

Only in the repository secrets `APP_KEYSTORE_BASE64` (the `.p12`, base64) and
`APP_KEYSTORE_PASSWORD`, plus the owner's private copy. It is **not** in the repository: with a
public key anyone could sign a fake app as RX VPN.

## Rules

- **Never generate a new key.** Android installs an update only over an app with the same
  signature; a new key forces every user to uninstall, losing settings and configs.
- `build.yml` fails when the secret is missing or when an APK does not carry the fingerprint
  above. Do not add a fallback key and do not change the expected fingerprint.
- If the secrets are lost, restore them from the owner's copy. Do not make a new key.

The earlier key (`52864c67...`, alias `mobix`) was public in this repository and is retired.
Apps signed with it must be uninstalled once before a build with the current key installs.
