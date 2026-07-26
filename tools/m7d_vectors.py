#!/usr/bin/env python3
"""M7d EV2 secure-messaging test vectors, mirroring the reference algorithm.
Same known session as M7b so the user can chain from vectors they already trust.
"""
from cryptography.hazmat.primitives.cmac import CMAC
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.algorithms import AES

def h(s): return bytes.fromhex(s.replace(" ", ""))
def x(b): return b.hex()

# ---- known session (from M7b) + a fixed TI (same as the offline auth test) ----
KENC = h("d46c03528ecd3b1f1148e708d3e9729d")
KMAC = h("116d156cc502da820eb57915f189ec74")
TI   = h("11223344")

def cmac(key, msg):
    c = CMAC(AES(key)); c.update(msg); return c.finalize()

def truncate(mac16):               # DESFire keeps the odd-indexed bytes
    return bytes(mac16[i] for i in range(1, 16, 2))

def ctr_bytes(ctr):                # 2 bytes, little-endian
    return bytes([ctr & 0xFF, (ctr >> 8) & 0xFF])

def command_mac(cmd, data, ctr):
    msg = bytes([cmd]) + ctr_bytes(ctr) + TI + data
    return truncate(cmac(KMAC, msg)), msg

def response_mac(data, ctr):
    msg = bytes([0x00]) + ctr_bytes(ctr) + TI + data
    return truncate(cmac(KMAC, msg)), msg

def calc_iv(b0, b1, ctr):
    blk = bytes([b0, b1]) + TI + ctr_bytes(ctr) + b"\x00"*8
    enc = Cipher(AES(KENC), modes.ECB()).encryptor()
    return enc.update(blk) + enc.finalize()

def command_iv(ctr):  return calc_iv(0xA5, 0x5A, ctr)
def response_iv(ctr): return calc_iv(0x5A, 0xA5, ctr)

def pad(data):                     # ISO 7816-4, always >= 1 pad byte
    out = bytearray(data); out.append(0x80)
    while len(out) % 16: out.append(0x00)
    return bytes(out)

def cbc_enc(key, iv, data):
    e = Cipher(AES(key), modes.CBC(iv)).encryptor(); return e.update(data)+e.finalize()
def cbc_dec(key, iv, data):
    d = Cipher(AES(key), modes.CBC(iv)).decryptor(); return d.update(data)+d.finalize()

def le32(v): return (v & 0xFFFFFFFF).to_bytes(4, "little")

print("session  Kenc =", x(KENC))
print("         Kmac =", x(KMAC))
print("         TI   =", x(TI))
print()

# ===== M7d-1a : the MAC layer =====
print("== M7d-1a  MAC layer ==")
sample = h("00112233445566778899aabbccddeeff")
print("truncateMac(", x(sample), ") =", x(truncate(sample)))
m, msg = command_mac(0x6C, h("01"), 0)          # GetValue header = fileNo 0x01, ctr 0
print("commandMac(0x6C, [01], ctr=0):")
print("   input =", x(msg))
print("   mac8  =", x(m))
m, msg = response_mac(h(""), 1)                  # a data-less response MAC at ctr 1
print("responseMac([], ctr=1):")
print("   input =", x(msg))
print("   mac8  =", x(m))
print()

# ===== M7d-1b : the crypto layer =====
print("== M7d-1b  crypto layer ==")
print("commandIv (ctr=0)  =", x(command_iv(0)))
print("responseIv(ctr=1)  =", x(response_iv(1)))
print("pad(96000000)      =", x(pad(le32(150))))
ct = cbc_enc(KENC, command_iv(0), pad(le32(150)))   # encrypt Debit amount 150c
print("encryptCommandData(150 cents) =", x(ct))
print()

# ===== M7d-2 : GetValue (full) =====
print("== M7d-2  GetValue full ==")
balance = 1000                                    # 10.00 EUR, on the card
enc_block = cbc_enc(KENC, response_iv(1), pad(le32(balance)))
print("card sends encrypted =", x(enc_block))
rmac, _ = response_mac(enc_block, 1)
print("card resp MAC (over enc, ctr=1) =", x(rmac))
dec = cbc_dec(KENC, response_iv(1), enc_block)
print("you decrypt          =", x(dec), "-> value =",
      int.from_bytes(dec[:4], "little", signed=True), "cents")
print()

# ===== M7d-3 : Debit + Commit =====
print("== M7d-3  Debit + Commit ==")
amount = 150                                      # debit 1.50 EUR
ct = cbc_enc(KENC, command_iv(0), pad(le32(amount)))
print("debit ciphertext (amount 150c, ctr=0) =", x(ct))
mi = h("01") + ct                                 # macInput = fileNo || ciphertext
dm, dmsg = command_mac(0xDC, mi, 0)
print("debit commandMac input =", x(dmsg))
print("debit commandMac mac8  =", x(dm))
print("debit APDU data (fileNo||ct||mac) =", x(mi + dm))
drm, _ = response_mac(h(""), 1)                   # debit response MAC at ctr=1
print("debit resp MAC (ctr=1) =", x(drm))
cm, cmsg = command_mac(0xC7, h(""), 1)            # commit at ctr=1 (after debit)
print("commit commandMac input =", x(cmsg))
print("commit commandMac mac8  =", x(cm))
crm, _ = response_mac(h(""), 2)                   # commit response MAC at ctr=2
print("commit resp MAC (ctr=2) =", x(crm))
