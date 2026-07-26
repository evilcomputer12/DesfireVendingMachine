# Appendix A — Test vectors

Everything you need to verify the crypto offline, in one place. All values are hex, lowercase, unspaced.

**These vectors were generated with Python's `cryptography` library**, deliberately not with the Java code you're writing. Agreement therefore means your implementation is *correct*, not merely self-consistent.

Regenerate or extend them with `tools/m7d_vectors.py` in this repo.

---

## A.1 AES-CMAC — RFC 4493 (Chapter 7)

**Key `K = 2b7e151628aed2a6abf7158809cf4f3c`**

Subkeys:
```
K1 = fbeed618357133667c85e08f7236a8de
K2 = f7ddac306ae266ccf90bc11ee46d513b
```

| # | Message | CMAC |
|---|---|---|
| 1 | *(empty)* | `bb1d6929e95937287fa37d129b756746` |
| 2 | `6bc1bee22e409f96e93d7e117393172a` | `070a16b46b4d4144f79bdd9dd04a287c` |
| 3 | `6bc1bee22e409f96e93d7e117393172a`<br>`ae2d8a571e03ac9c9eb76fac45af8e51`<br>`30c81c46a35ce411` | `dfa66747de9ae63030ca32611497c827` |
| 4 | message 3 + `e5fbc1191a0a52ef`<br>`f69f2445df4f9b17ad2b417be66c3710` | `51f0bebf7e3b9d92fc49741779363cfe` |

The four cases cover every branch: empty (pad, K2), one full block (K1), partial last block (pad, K2), multiple full blocks (K1).

---

## A.2 The reference session (Chapters 8–10)

Every vector below derives from these three inputs. They are chained deliberately: the same session runs through key derivation, the auth replay, and secure messaging, so a failure anywhere points at one specific step.

```
authKey = 00112233445566778899aabbccddeeff
rndA    = a0a1a2a3a4a5a6a7a8a9aaabacadaeaf     (pin this in tests)
rndB    = b0b1b2b3b4b5b6b7b8b9babbbcbdbebf
```

### Session keys (Chapter 8)

```
Kenc = d46c03528ecd3b1f1148e708d3e9729d      (label 0xA5)
Kmac = 116d156cc502da820eb57915f189ec74      (label 0x5A)
```

### Byte rotation (Chapter 9)

```
rotateLeft(b0b1b2b3b4b5b6b7b8b9babbbcbdbebf)
         = b1b2b3b4b5b6b7b8b9babbbcbdbebfb0
```

---

## A.3 The authentication replay (Chapter 9)

A complete `AuthenticateEV2First` exchange with `rndA` pinned. All AES uses an **all-zero IV**.

| Direction | Bytes |
|---|---|
| card → you, step 1 payload `E(K, RndB)` | `eab6822fe368d5bc9895fb2558b38dde` |
| you → card, step 2 payload `E(K, RndA‖RndB')` | `cf086a82c0b745a749daabb28a7a8db3`<br>`32272f9e5fdc37a4746064f25b7c2ca7` |
| card → you, step 2 payload `E(K, TI‖RndA'‖caps)` | `4e08e447d74810052caa1c5b6c60343f`<br>`51f6e901ad171e677b100d3a34c925e4` |
| → extracted TI | `11223344` |

**Fake replies must carry their status word.** Your card code strips the last two bytes off every response, so a fake that omits them chops real ciphertext:

```
step-1 reply = eab6822fe368d5bc9895fb2558b38dde + 91af
step-2 reply = 4e08e447d74810052caa1c5b6c60343f51f6e901ad171e677b100d3a34c925e4 + 9100
select reply = 9100
```

**Cross-check:** decrypting `eab6822f…` with `authKey` and a zero IV must yield exactly `b0b1b2b3b4b5b6b7b8b9babbbcbdbebf`. If it does, your auth vectors and your session-key vectors are consistent with each other.

---

## A.4 Secure messaging (Chapter 10)

Session as above, **`TI = 11223344`**.

### The MAC layer

```
truncateMac(00112233445566778899aabbccddeeff) = 1133557799bbddff
```
(literally the odd-indexed bytes — verifiable by eye)

| call | ctr | MAC input | result |
|---|---|---|---|
| `commandMac(0x6C, {01})` | 0 | `6c00001122334401` | `4d131ee17b482b16` |
| `responseMac({})` | 1 | `00010011223344` | `6b082e8062d1a89d` |

### The crypto layer

| call | ctr | result |
|---|---|---|
| `commandIv()` | 0 | `dd42134cd6970980d22066cb00ab1b16` |
| `responseIv()` | 1 | `cb73ae774aafd81868062906135969b9` |
| `padIso7816(96000000)` | — | `96000000800000000000000000000000` |
| `encryptCommandData(150)` | 0 | `a2eb8f000bf3ddfe6e18e8859b284591` |

`96 00 00 00` is 150 little-endian (a €1.50 amount).

### GetValue (balance 1000 cents)

| item | ctr | bytes |
|---|---|---|
| card's encrypted payload | 1 | `7950692fc2f01e72b3cee290f7122b5c` |
| card's response MAC | 1 | `a44a7f2359b2f512` |
| decrypts to | | `e8030000` + `80` padding → **1000** |

Full fake reply: `7950692fc2f01e72b3cee290f7122b5c` + `a44a7f2359b2f512` + `9100`

### Debit 150 cents + Commit

| item | ctr | bytes |
|---|---|---|
| debit ciphertext | 0 | `a2eb8f000bf3ddfe6e18e8859b284591` |
| debit command MAC input | 0 | `dc00001122334401a2eb8f000bf3ddfe6e18e8859b284591` |
| debit command MAC | 0 | `27e5ea2cb8acad7d` |
| **debit APDU data** (fileNo‖ct‖mac) | 0 | `01a2eb8f000bf3ddfe6e18e8859b28459127e5ea2cb8acad7d` |
| debit response MAC | 1 | `6b082e8062d1a89d` |
| commit command MAC input | 1 | `c7010011223344` |
| commit command MAC | 1 | `4dc9859e1a783ee0` |
| commit response MAC | 2 | `febea1fcb55208ba` |

Watch the counter walk 0 → 1 → 2. That progression *is* the increment-ordering rule made visible.

---

## A.5 Card personalisation

The values the kiosk and the top-up app must agree on. Change them anywhere and you must change them everywhere.

| Setting | Value | On the wire |
|---|---|---|
| Application ID | `0x010203` | `03 02 01` (**LSB-first**) |
| App master key no. | 0 | |
| App **user** key no. | **2** | the one everyday operations use |
| Value file no. | `0x01` | |
| Comm mode | Full (`0x03`) | encrypted + MAC'd |
| Access rights | key 2 for all four roles | word `0x2222` |
| Lower limit | 0 cents | |
| Upper limit | 100000 cents (€1000) | |
| PICC master key | 16 × `0x00` | factory default, **never changed** |
| App master key | 16 × `0x11` | ⚠️ bench key |
| App user key | 16 × `0x22` | ⚠️ bench key |

⚠️ These are **bench keys**, identical on every card, hardcoded in the app. See Chapter 12 for what a real deployment does instead.

---

## A.6 DESFire command and status reference

### Commands used in this book

| Cmd | Name | Comm mode | Notes |
|---|---|---|---|
| `0x5A` | SelectApplication | plain | AID LSB-first |
| `0x60` | GetVersion | plain | chains 3 frames |
| `0x71` | AuthenticateEV2First | — | data `{keyNo, 0x00}` |
| `0xAF` | *(continue / additional frame)* | — | both a command and a status |
| `0x6C` | GetValue | Full | |
| `0x0C` | Credit | Full | stages only |
| `0xDC` | Debit | Full | stages only |
| `0x1C` | LimitedCredit | Full | needs the file flag |
| `0xC7` | CommitTransaction | MAC | **makes it permanent** |
| `0xA7` | AbortTransaction | MAC | discards staged changes |
| `0xCA` | CreateApplication | plain | provisioning |
| `0xCC` | CreateValueFile | MAC | provisioning |
| `0xC4` | ChangeKey | Full | ⚠️ irreversible |

### Status words

| Status | Meaning | Usual cause |
|---|---|---|
| `91 00` | OK | |
| `91 AF` | additional frame follows | not an error — drain the chain |
| `91 0C` | no changes | |
| `91 1C` | illegal command | wrong command for this file type |
| `91 1E` | integrity error | **your MAC or CRC is wrong** |
| `91 7E` | length error | wrong `Lc` — e.g. auth step 1 missing its `0x00` |
| `91 9D` | permission denied | authenticated with the wrong key |
| `91 AE` | authentication error | wrong key, or session invalidated |
| `91 BE` | boundary error | debit would breach the file's limits |
| `91 F0` | file not found | card not provisioned |
| `91 F1` | file integrity error | card-side corruption |

`6E 00` / `6D 00` are **ISO-7816** errors, not DESFire ones — they mean your APDU wrapper is malformed (bad CLA/INS), not that the card rejected the command.

---

*Next: [Appendix B — The bug museum](B-troubleshooting.md)*
