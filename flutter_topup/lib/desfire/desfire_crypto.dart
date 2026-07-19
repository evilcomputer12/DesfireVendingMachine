/// AES primitives and DESFire EV2 key/IV derivation.
///
/// Every routine here is a direct port of the reference C implementation in
/// `plainc-desfire-rc522-main/lib/desfire/desfire_crypto.c`. Where the C code
/// makes a choice that is not obvious from the NXP documentation (for example
/// the odd-byte MAC truncation, or the exact SV byte layout) the Dart code
/// mirrors the C byte-for-byte rather than the spec, because the C side is the
/// implementation the cards in this project were personalised with.
///
/// This library has no Flutter or NFC dependency, so it is fully unit testable.
library;

import 'dart:typed_data';

import 'package:pointycastle/export.dart';

/// AES block size in bytes.
const int kAesBlockSize = 16;

/// Encrypts a single 16-byte block with AES-128 in ECB mode.
///
/// Port of `df_aes_ecb_encrypt`.
Uint8List aesEcbEncryptBlock(Uint8List key, Uint8List block) {
  _requireKey(key);
  _requireLength(block, kAesBlockSize, 'ECB block');
  final engine = AESEngine()..init(true, KeyParameter(key));
  final out = Uint8List(kAesBlockSize);
  engine.processBlock(block, 0, out, 0);
  return out;
}

/// Decrypts a single 16-byte block with AES-128 in ECB mode.
///
/// Port of `df_aes_ecb_decrypt`.
Uint8List aesEcbDecryptBlock(Uint8List key, Uint8List block) {
  _requireKey(key);
  _requireLength(block, kAesBlockSize, 'ECB block');
  final engine = AESEngine()..init(false, KeyParameter(key));
  final out = Uint8List(kAesBlockSize);
  engine.processBlock(block, 0, out, 0);
  return out;
}

/// Encrypts [data] with AES-128-CBC. [data] length must be a multiple of 16.
///
/// Port of `df_aes_cbc_encrypt`. No padding is applied; use [padIso7816] first
/// when the payload is not already block aligned.
Uint8List aesCbcEncrypt(Uint8List key, Uint8List iv, Uint8List data) {
  return _cbc(key, iv, data, forEncryption: true);
}

/// Decrypts [data] with AES-128-CBC. [data] length must be a multiple of 16.
///
/// Port of `df_aes_cbc_decrypt`.
Uint8List aesCbcDecrypt(Uint8List key, Uint8List iv, Uint8List data) {
  return _cbc(key, iv, data, forEncryption: false);
}

Uint8List _cbc(
  Uint8List key,
  Uint8List iv,
  Uint8List data, {
  required bool forEncryption,
}) {
  _requireKey(key);
  _requireLength(iv, kAesBlockSize, 'IV');
  if (data.length % kAesBlockSize != 0) {
    throw ArgumentError.value(
      data.length,
      'data',
      'CBC payload must be a multiple of $kAesBlockSize bytes',
    );
  }
  final cipher = CBCBlockCipher(AESEngine())
    ..init(forEncryption, ParametersWithIV(KeyParameter(key), iv));
  final out = Uint8List(data.length);
  for (var off = 0; off < data.length; off += kAesBlockSize) {
    cipher.processBlock(data, off, out, off);
  }
  return out;
}

/// AES-128-CMAC (RFC 4493 / NIST SP 800-38B) over [message].
///
/// Returns the full 16-byte MAC. Port of `df_cmac`; verified against the
/// RFC 4493 known-answer vectors in `test/desfire_crypto_test.dart`.
Uint8List aesCmac(Uint8List key, Uint8List message) {
  _requireKey(key);
  final mac = CMac(AESEngine(), 128)..init(KeyParameter(key));
  return mac.process(Uint8List.fromList(message));
}

/// Truncates a 16-byte CMAC to the 8 bytes DESFire actually transmits.
///
/// DESFire keeps the odd-indexed bytes: `mac[1], mac[3], ... mac[15]`.
/// Port of `df_truncate_mac`.
Uint8List truncateMac(Uint8List mac) {
  _requireLength(mac, kAesBlockSize, 'CMAC');
  final out = Uint8List(8);
  for (var i = 0; i < 8; i++) {
    out[i] = mac[i * 2 + 1];
  }
  return out;
}

/// Session key labels used by [deriveSessionKey].
class SessionKeyLabel {
  const SessionKeyLabel._();

  /// Label for the encryption session key (SV1).
  static const int enc = 0xA5;

  /// Label for the MAC session key (SV2).
  static const int mac = 0x5A;
}

/// Derives an AES-128 EV2 session key from the authentication nonces.
///
/// Port of `df_derive_session_key`. The 32-byte derivation vector is:
///
/// ```text
/// [0]      label                       (0xA5 for enc, 0x5A for MAC)
/// [1]      counter-label               (0x5A when label is 0xA5, else 0xA5)
/// [2..5]   00 01 00 80                 (fixed)
/// [6..7]   rndA[0..1]
/// [8..13]  rndA[2..7] XOR rndB[0..5]
/// [14..23] rndB[6..15]
/// [24..31] rndA[8..15]
/// ```
///
/// The session key is `CMAC(key, SV)`.
Uint8List deriveSessionKey(
  Uint8List key,
  Uint8List rndA,
  Uint8List rndB,
  int label,
) {
  _requireKey(key);
  _requireLength(rndA, 16, 'rndA');
  _requireLength(rndB, 16, 'rndB');
  if (label != SessionKeyLabel.enc && label != SessionKeyLabel.mac) {
    throw ArgumentError.value(label, 'label', 'must be 0xA5 or 0x5A');
  }

  final sv = Uint8List(32);
  sv[0] = label;
  sv[1] = label == SessionKeyLabel.enc
      ? SessionKeyLabel.mac
      : SessionKeyLabel.enc;
  sv[2] = 0x00;
  sv[3] = 0x01;
  sv[4] = 0x00;
  sv[5] = 0x80;
  sv[6] = rndA[0];
  sv[7] = rndA[1];
  for (var i = 0; i < 6; i++) {
    sv[8 + i] = rndA[2 + i] ^ rndB[i];
  }
  for (var i = 0; i < 10; i++) {
    sv[14 + i] = rndB[6 + i];
  }
  for (var i = 0; i < 8; i++) {
    sv[24 + i] = rndA[8 + i];
  }
  return aesCmac(key, sv);
}

/// Builds the IV used to encrypt *command* data in an EV2 session.
///
/// `AES-ECB(sessKeyEnc, [0xA5, 0x5A, TI0..TI3, ctrLo, ctrHi, 00 x8])`.
/// Port of `_calc_iv_cmd`.
Uint8List calcIvCommand(Uint8List sessKeyEnc, Uint8List ti, int cmdCounter) {
  return _calcIv(sessKeyEnc, ti, cmdCounter, 0xA5, 0x5A);
}

/// Builds the IV used to decrypt *response* data in an EV2 session.
///
/// `AES-ECB(sessKeyEnc, [0x5A, 0xA5, TI0..TI3, ctrLo, ctrHi, 00 x8])`.
/// Port of `_calc_iv_resp`.
Uint8List calcIvResponse(Uint8List sessKeyEnc, Uint8List ti, int cmdCounter) {
  return _calcIv(sessKeyEnc, ti, cmdCounter, 0x5A, 0xA5);
}

Uint8List _calcIv(
  Uint8List sessKeyEnc,
  Uint8List ti,
  int cmdCounter,
  int b0,
  int b1,
) {
  _requireKey(sessKeyEnc);
  _requireLength(ti, 4, 'TI');
  final input = Uint8List(kAesBlockSize);
  input[0] = b0;
  input[1] = b1;
  input.setRange(2, 6, ti);
  input[6] = cmdCounter & 0xFF;
  input[7] = (cmdCounter >> 8) & 0xFF;
  return aesEcbEncryptBlock(sessKeyEnc, input);
}

/// Applies ISO/IEC 7816-4 padding: append `0x80`, then zeros to the next
/// 16-byte boundary.
///
/// Mirrors the padding in `_df_write_data_single`, which always emits at least
/// one pad byte (a 16-byte payload becomes 32 bytes).
Uint8List padIso7816(Uint8List data) {
  final paddedLength = (data.length | 0x0F) + 1;
  final out = Uint8List(paddedLength);
  out.setRange(0, data.length, data);
  out[data.length] = 0x80;
  return out;
}

/// Removes ISO/IEC 7816-4 padding, returning the payload before the `0x80`
/// marker. Returns [data] unchanged when no valid padding is found.
Uint8List unpadIso7816(Uint8List data) {
  for (var i = data.length - 1; i >= 0; i--) {
    if (data[i] == 0x80) return Uint8List.sublistView(data, 0, i);
    if (data[i] != 0x00) break;
  }
  return data;
}

/// Rotates [data] left by one byte: `data[1..n-1] || data[0]`.
///
/// Used for the `RndB'` / `RndA'` values in the authentication handshake.
Uint8List rotateLeft1(Uint8List data) {
  if (data.isEmpty) return Uint8List(0);
  final out = Uint8List(data.length);
  out.setRange(0, data.length - 1, data, 1);
  out[data.length - 1] = data[0];
  return out;
}

/// Constant-time-ish comparison of two byte sequences.
bool bytesEqual(List<int> a, List<int> b) {
  if (a.length != b.length) return false;
  var diff = 0;
  for (var i = 0; i < a.length; i++) {
    diff |= a[i] ^ b[i];
  }
  return diff == 0;
}

/// Formats bytes as uppercase space-separated hex, matching the C logger.
String toHex(List<int> bytes) => bytes
    .map((b) => b.toRadixString(16).padLeft(2, '0').toUpperCase())
    .join(' ');

/// Parses a hex string (with or without separators) into bytes.
Uint8List fromHex(String hex) {
  final clean = hex.replaceAll(RegExp(r'[^0-9a-fA-F]'), '');
  if (clean.length.isOdd) {
    throw ArgumentError.value(hex, 'hex', 'odd number of hex digits');
  }
  final out = Uint8List(clean.length ~/ 2);
  for (var i = 0; i < out.length; i++) {
    out[i] = int.parse(clean.substring(i * 2, i * 2 + 2), radix: 16);
  }
  return out;
}

void _requireKey(Uint8List key) => _requireLength(key, 16, 'AES-128 key');

void _requireLength(Uint8List value, int length, String name) {
  if (value.length != length) {
    throw ArgumentError.value(
      value.length,
      name,
      '$name must be exactly $length bytes',
    );
  }
}
