/// EV2 secure-messaging session state and the primitives built on top of it.
///
/// Port of the `DFSession` struct plus `_calc_cmd_mac`, `_verify_resp_mac`,
/// `_calc_iv_cmd` and `_calc_iv_resp` from `desfire_cmd.c`.
library;

import 'dart:typed_data';

import 'desfire_crypto.dart';

/// Live state of an `AuthenticateEV2First` session.
///
/// A session is only meaningful for the application that was selected when the
/// authentication ran; selecting a different application invalidates it, which
/// is why [invalidate] is called from `selectApplication`.
class DesfireSession {
  DesfireSession({
    required Uint8List sessKeyEnc,
    required Uint8List sessKeyMac,
    required Uint8List ti,
    required this.authKeyNo,
    int cmdCounter = 0,
  }) : sessKeyEnc = Uint8List.fromList(sessKeyEnc),
       sessKeyMac = Uint8List.fromList(sessKeyMac),
       ti = Uint8List.fromList(ti) {
    if (this.sessKeyEnc.length != 16) {
      throw ArgumentError('sessKeyEnc must be 16 bytes');
    }
    if (this.sessKeyMac.length != 16) {
      throw ArgumentError('sessKeyMac must be 16 bytes');
    }
    if (this.ti.length != 4) {
      throw ArgumentError('TI must be 4 bytes');
    }
    _cmdCounter = cmdCounter;
  }

  /// AES-128 session key used to encrypt/decrypt command and response data.
  final Uint8List sessKeyEnc;

  /// AES-128 session key used for the command and response CMACs.
  final Uint8List sessKeyMac;

  /// 4-byte transaction identifier issued by the card during authentication.
  final Uint8List ti;

  /// Key number this session authenticated with.
  final int authKeyNo;

  int _cmdCounter = 0;
  bool _active = true;

  /// Number of commands issued since authentication. Starts at 0 and is
  /// incremented by [incrementCounter] after each accepted command.
  int get cmdCounter => _cmdCounter;

  /// Whether the session may still be used.
  bool get isActive => _active;

  /// Marks the session dead. Any further secure command must re-authenticate.
  void invalidate() => _active = false;

  /// Advances the command counter.
  ///
  /// The C code increments *after* the card accepts a command and *before*
  /// verifying the response MAC, so the response MAC and the response IV both
  /// use the already-incremented value. This ordering is load-bearing.
  void incrementCounter() {
    _cmdCounter = (_cmdCounter + 1) & 0xFFFF;
  }

  /// Computes the 8-byte truncated CMAC that accompanies a command.
  ///
  /// MAC input is `[cmd][ctrLo][ctrHi][TI0..TI3][cmdData...]`.
  /// Port of `_calc_cmd_mac`.
  Uint8List commandMac(int command, [Uint8List? commandData]) {
    final data = commandData ?? Uint8List(0);
    final buf = Uint8List(7 + data.length);
    buf[0] = command;
    buf[1] = _cmdCounter & 0xFF;
    buf[2] = (_cmdCounter >> 8) & 0xFF;
    buf.setRange(3, 7, ti);
    buf.setRange(7, 7 + data.length, data);
    return truncateMac(aesCmac(sessKeyMac, buf));
  }

  /// Computes the expected 8-byte truncated CMAC for a response.
  ///
  /// MAC input is `[0x00][ctrLo][ctrHi][TI0..TI3][respData...]`. The leading
  /// byte is the return code, which is always `0x00` here because a response
  /// MAC is only checked after a successful status.
  /// Port of `_verify_resp_mac`.
  Uint8List responseMac(Uint8List? responseData, {int returnCode = 0x00}) {
    final data = responseData ?? Uint8List(0);
    final buf = Uint8List(7 + data.length);
    buf[0] = returnCode;
    buf[1] = _cmdCounter & 0xFF;
    buf[2] = (_cmdCounter >> 8) & 0xFF;
    buf.setRange(3, 7, ti);
    buf.setRange(7, 7 + data.length, data);
    return truncateMac(aesCmac(sessKeyMac, buf));
  }

  /// Whether [mac8] matches the expected response MAC over [responseData].
  bool verifyResponseMac(Uint8List? responseData, Uint8List mac8) {
    return bytesEqual(responseMac(responseData), mac8);
  }

  /// IV for encrypting command data with [sessKeyEnc].
  Uint8List get commandIv => calcIvCommand(sessKeyEnc, ti, _cmdCounter);

  /// IV for decrypting response data with [sessKeyEnc].
  Uint8List get responseIv => calcIvResponse(sessKeyEnc, ti, _cmdCounter);

  /// Encrypts [plaintext] in CommMode.Full: ISO 7816-4 pad, then AES-CBC with
  /// the current [commandIv].
  Uint8List encryptCommandData(Uint8List plaintext) {
    return aesCbcEncrypt(sessKeyEnc, commandIv, padIso7816(plaintext));
  }

  /// Decrypts CommMode.Full response data with the current [responseIv].
  /// Padding is left in place; callers know their expected payload length.
  Uint8List decryptResponseData(Uint8List ciphertext) {
    return aesCbcDecrypt(sessKeyEnc, responseIv, ciphertext);
  }

  @override
  String toString() =>
      'DesfireSession(keyNo: $authKeyNo, TI: ${toHex(ti)}, '
      'ctr: $_cmdCounter, active: $_active)';
}
