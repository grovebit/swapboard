# ClipSync wire protocol, version 1

Two parties:
- **Server:** the Mac agent (`clipsync agent`).
- **Client:** the Android app.

Both implementations must pass `vectors.json` (see "Test vectors" below).

## 1. Pairing

The Mac runs `clipsync pair`. It generates:
- `psk`: 32 random bytes
- `pid`: 12 random bytes, encoded as base64url without padding (16 characters)

It then shows this URI as a QR code:

```
clipsync://pair?v=1&pid=<pid>&name=<mac name>&k=<psk>&h=<host>,<host>,...&p=<port>
```

| Param  | Meaning |
|--------|---------|
| `v`    | Must be `1`. |
| `pid`  | Pairing id. The server looks up the PSK by it. |
| `name` | Mac display name, percent-encoded (UTF-8; space = `%20`; `+` must be encoded as `%2B`). |
| `k`    | PSK, base64url without padding. Must decode to exactly 32 bytes. |
| `h`    | The Mac's Tailscale IPv4 addresses (100.64.0.0/10), comma-separated, in the order to try. At least one is required. Clients **must ignore** any other host (older links also listed LAN IPs and `.local` names). |
| `p`    | TCP port, 1–65535 (default 48613). |

Unknown parameters are ignored.
- A client must ask the user to confirm before storing a pairing.
- It must never silently replace an existing pairing.

## 2. Transport

- **Tailscale only.** The server binds its listener to its Tailscale address. It also rejects peers outside 100.64.0.0/10 and connections that arrive on Wi-Fi/Ethernet. The client connects only to Tailscale addresses, over sockets bound to the Tailscale VPN network. With no VPN it doesn't connect at all; it never falls back to Wi-Fi or mobile data.
- WebSocket (RFC 6455) at `ws://<host>:<port>/`.
- The client tries the hosts in order, with a connect timeout of about 4 s per host.
- There is no TLS. Confidentiality and authentication come from the PSK layer below.

## 3. Handshake

All byte strings in the JSON are base64url without padding.

1. **Client → server**, WebSocket *text* frame:
   ```json
   {"v":1,"pid":"<pid>","nc":"<16 random bytes>"}
   ```
   - If `v` ≠ 1, the server closes with 4002.
   - If the `pid` is unknown, it closes with 4001.
2. **Server → client**, *text* frame:
   ```json
   {"v":1,"ns":"<16 random bytes>"}
   ```
3. **Both sides derive the session keys:**
   ```
   salt   = nc ‖ ns                                  (32 bytes)
   k_c2s  = HKDF-SHA256(ikm=psk, salt, info="clipsync v1 c2s", L=32)
   k_s2c  = HKDF-SHA256(ikm=psk, salt, info="clipsync v1 s2c", L=32)
   ```
4. The client sends encrypted `{"type":"auth","device":"<phone name>"}`, with c2s counter 0.
   - If decryption fails, the server closes with 4001.
5. The server sends encrypted `{"type":"welcome","mac":"<mac name>"}`, with s2c counter 0.
   - If decryption fails, the client closes with 4001.

After step 5 the session is established. Step 4 proves to the server that the client holds the PSK, and step 5 proves it to the client. Because both sides contribute a fresh random value to the salt, frames from an earlier session can't be replayed into a new one.

## 4. Encrypted frames

Every message after step 3 is a WebSocket *binary* frame:

```
frame = ctr (uint64, big-endian, 8 bytes) ‖ AES-256-GCM ciphertext ‖ tag (16 bytes)
nonce = 00 00 00 00 ‖ ctr (8 bytes, big-endian)        (12 bytes)
aad   = "clipsync-v1"                                  (ASCII)
key   = k_c2s for client→server, k_s2c for server→client
```

- Each direction keeps its own counter. The first frame is 0, and every later frame is previous + 1.
- A receiver closes with **4001** in any of these cases:
  - the counter isn't exactly the expected value (this covers both replays and gaps)
  - authentication fails
  - a text frame arrives after the handshake
- The plaintext is one UTF-8 JSON object.
- A frame with more than 8 MiB of plaintext, or longer than 8 MiB + 24 bytes on the wire, makes the receiver close with **4003**.

## 5. Messages

| `type`    | Direction | Fields | Notes |
|-----------|-----------|--------|-------|
| `auth`    | c→s | `device` (string) | First encrypted frame from the client. |
| `welcome` | s→c | `mac` (string) | First encrypted frame from the server. |
| `clip`    | both | `id` (UUID string), `text` (string), `sensitive` (bool), `ts` (ms since epoch, int) | `text` ≤ 1 MiB of UTF-8. Senders never send larger text. |
| `ack`     | both | `id` | Sent after a `clip` has been processed. This includes clips dropped as duplicates. |
| `ping`    | both | — | The receiver answers with `pong`. |
| `pong`    | both | — | |

- Unknown fields and unknown `type`s are ignored.
- `sensitive: true` means the clip must be marked as sensitive locally:
  - Mac: `org.nspasteboard.ConcealedType`
  - Android: `ClipDescription.EXTRA_IS_SENSITIVE`

## 6. Behaviour rules

- **Pending clip.** Each side holds only the latest clip that wasn't acknowledged, for 5 minutes. It's sent (again) after the next successful handshake. An older pending clip is dropped.
- **Duplicates.** Receivers remember the last 50 clip `id`s and ignore repeats, but still ack them.
- **No echo.** A side never forwards a clip it received from the other side back to it.
- **Liveness.**
  - The client sends `ping` every 30 s and treats 75 s without any incoming frame as a dead connection.
  - The server closes connections that send nothing for 180 s. The timeout is long because Android Doze pauses the client's timers.
- **One connection per pairing.** When a new connection completes authentication for a `pid`, the server closes the older one with **4004**. A client that receives 4004 doesn't reconnect immediately (it backs off).

## 7. Close codes

| Code | Meaning |
|------|---------|
| 1000 | Normal close |
| 4001 | Authentication failed: unknown pid, decrypt failure, bad counter, protocol violation. The client stops retrying and shows "re-pair needed" when this happens before `welcome`. |
| 4002 | Unsupported protocol version |
| 4003 | Message too large |
| 4004 | Replaced by a newer connection for the same pairing |

## 8. Test vectors (`vectors.json`)

`tools/gen-vectors.mjs` generates them with Node's `crypto`, which serves as an independent third implementation. The file contains:

- `hkdf_rfc5869_case1`: RFC 5869 test case 1 (sanity check for HKDF-SHA256).
- `session`:
  - inputs: `psk`, `nc`, `ns` (base64url)
  - expected outputs: `salt`, `k_c2s` and `k_s2c` (hex)
- `frames`:
  - fields: `direction`, `counter`, `plaintext` (exact UTF-8 string) and `frame_hex`
  - Implementations must produce exactly `frame_hex` when they encrypt `plaintext` with that counter.
  - Encrypt the given bytes as-is; don't re-serialize the JSON.
- `invalid_frames`: frames that must be rejected at the given `expected_counter`. Cases: tampered tag, replayed counter, skipped counter, wrong direction key.
- `pairing_uri`: one URI and its parsed fields.

Regenerate with `node protocol/tools/gen-vectors.mjs`. The inputs are fixed, so the output is deterministic.
