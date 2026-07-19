/// A software DESFire EV2 card, good enough to exercise the whole command
/// layer without any NFC hardware.
///
/// It implements the card side of the protocol independently of
/// [DesfireCard]: it derives its own session keys, checks the command CMACs
/// the client sends, maintains its own command counter, and — importantly —
/// models the value-file transaction buffer, so a `Credit` that is never
/// committed genuinely does not change the balance.
library;

import 'dart:typed_data';

import 'package:flutter_topup/desfire/desfire_card.dart';
import 'package:flutter_topup/desfire/desfire_crypto.dart';
import 'package:flutter_topup/desfire/desfire_exceptions.dart';
import 'package:flutter_topup/desfire/value_file.dart';

/// Deterministic [RandomSource] for reproducible tests.
class FixedRandomSource implements RandomSource {
  FixedRandomSource(this.bytes);

  /// The bytes handed out, repeated if more are requested.
  final Uint8List bytes;

  @override
  Uint8List nextBytes(int count) {
    return Uint8List.fromList(
      List<int>.generate(count, (i) => bytes[i % bytes.length]),
    );
  }
}

/// Simulated DESFire EV2 card.
class FakeDesfireCard implements Transceiver {
  FakeDesfireCard({
    required this.applicationId,
    required this.keys,
    required this.fileNo,
    int initialBalance = 0,
    this.lowerLimit = 0,
    this.upperLimit = 100000,
    Uint8List? rndB,
    Uint8List? transactionId,
  }) : _balance = initialBalance,
       _rndB =
           rndB ?? Uint8List.fromList(List<int>.generate(16, (i) => 0xA0 + i)),
       _ti = transactionId ?? fromHex('deadbeef');

  /// AID this card answers to.
  final int applicationId;

  /// Key number -> 16-byte AES key.
  final Map<int, Uint8List> keys;

  /// Value file number.
  final int fileNo;

  /// Lower limit of the value file.
  final int lowerLimit;

  /// Upper limit of the value file.
  final int upperLimit;

  final Uint8List _rndB;
  final Uint8List _ti;

  int _balance;
  int _pendingDelta = 0;
  bool _transactionOpen = false;
  bool _appSelected = false;

  Uint8List? _sessKeyEnc;
  Uint8List? _sessKeyMac;
  int _counter = 0;

  Uint8List? _pendingAuthKey;

  /// Every APDU the client sent, for assertions on the wire format.
  final List<Uint8List> sentApdus = [];

  /// Overrides the status byte returned for the next command with [cmd].
  final Map<int, int> forcedStatus = {};

  /// Corrupts the response MAC of the next command with [cmd].
  final Set<int> corruptResponseMacFor = {};

  /// The committed balance. Staged credits/debits are *not* included, which
  /// is exactly the property the app has to respect.
  int get committedBalance => _balance;

  /// Whether a value change is staged but not yet committed.
  bool get hasOpenTransaction => _transactionOpen;

  /// Number of commands the card has processed since authentication.
  int get commandCounter => _counter;

  @override
  Future<Uint8List> transceive(Uint8List apdu) async {
    sentApdus.add(Uint8List.fromList(apdu));

    if (apdu.length < 5 || apdu[0] != 0x90) {
      return _sw(0x7E);
    }
    final command = apdu[1];
    final data = _extractData(apdu);

    final forced = forcedStatus.remove(command);
    if (forced != null) return _sw(forced);

    switch (command) {
      case DesfireCommand.selectApplication:
        return _selectApplication(data);
      case DesfireCommand.authenticateEv2First:
        return _authStep1(data);
      case DesfireCommand.additionalFrame:
        return _authStep2(data);
      case DesfireCommand.getValue:
        return _getValue(data);
      case DesfireCommand.credit:
        return _valueOperation(command, data, sign: 1);
      case DesfireCommand.debit:
        return _valueOperation(command, data, sign: -1);
      case DesfireCommand.limitedCredit:
        return _valueOperation(command, data, sign: 1);
      case DesfireCommand.commitTransaction:
        return _commit(data);
      case DesfireCommand.abortTransaction:
        return _abort(data);
      default:
        return _sw(0x6D);
    }
  }

  Uint8List _extractData(Uint8List apdu) {
    // 90 CMD 00 00 [Lc DATA] 00
    if (apdu.length == 5) return Uint8List(0);
    final lc = apdu[4];
    return Uint8List.sublistView(apdu, 5, 5 + lc);
  }

  Uint8List _sw(int status, [Uint8List? body]) {
    final payload = body ?? Uint8List(0);
    return Uint8List.fromList([...payload, 0x91, status]);
  }

  Uint8List _selectApplication(Uint8List data) {
    if (data.length != 3) return _sw(0x7E);
    final aid = data[0] | (data[1] << 8) | (data[2] << 16);
    if (aid != applicationId) return _sw(0xA0);
    _appSelected = true;
    _sessKeyEnc = null;
    _sessKeyMac = null;

    _counter = 0;
    return _sw(0x00);
  }

  Uint8List _authStep1(Uint8List data) {
    if (!_appSelected) return _sw(0x1C);
    if (data.length != 2) return _sw(0x7E);
    final key = keys[data[0]];
    if (key == null) return _sw(0x40);
    _pendingAuthKey = key;
    _sessKeyEnc = null;
    _sessKeyMac = null;

    return _sw(0xAF, aesCbcEncrypt(key, Uint8List(16), _rndB));
  }

  Uint8List _authStep2(Uint8List data) {
    final key = _pendingAuthKey;
    if (key == null || data.length != 32) return _sw(0xAE);

    final decrypted = aesCbcDecrypt(key, Uint8List(16), data);
    final rndA = Uint8List.sublistView(decrypted, 0, 16);
    final rndBRot = Uint8List.sublistView(decrypted, 16, 32);
    if (!bytesEqual(rndBRot, rotateLeft1(_rndB))) {
      _pendingAuthKey = null;
      return _sw(0xAE);
    }

    // TI || RndA' || PDcap2(6) || PCDcap2(6) = 32 bytes.
    final payload = Uint8List(32)
      ..setRange(0, 4, _ti)
      ..setRange(4, 20, rotateLeft1(rndA));
    final response = aesCbcEncrypt(key, Uint8List(16), payload);

    _sessKeyEnc = deriveSessionKey(key, rndA, _rndB, SessionKeyLabel.enc);
    _sessKeyMac = deriveSessionKey(key, rndA, _rndB, SessionKeyLabel.mac);
    _counter = 0;
    _pendingAuthKey = null;
    return _sw(0x00, response);
  }

  bool get _authenticated => _sessKeyMac != null && _sessKeyEnc != null;

  Uint8List _commandMac(int command, Uint8List commandData) {
    final buf = Uint8List(7 + commandData.length)
      ..[0] = command
      ..[1] = _counter & 0xFF
      ..[2] = (_counter >> 8) & 0xFF
      ..setRange(3, 7, _ti)
      ..setRange(7, 7 + commandData.length, commandData);
    return truncateMac(aesCmac(_sessKeyMac!, buf));
  }

  Uint8List _responseMac(Uint8List responseData) {
    final buf = Uint8List(7 + responseData.length)
      ..[0] = 0x00
      ..[1] = _counter & 0xFF
      ..[2] = (_counter >> 8) & 0xFF
      ..setRange(3, 7, _ti)
      ..setRange(7, 7 + responseData.length, responseData);
    return truncateMac(aesCmac(_sessKeyMac!, buf));
  }

  Uint8List _maybeCorrupt(int command, Uint8List mac) {
    if (!corruptResponseMacFor.remove(command)) return mac;
    return Uint8List.fromList(mac)..[0] ^= 0xFF;
  }

  Uint8List _getValue(Uint8List data) {
    if (!_authenticated) return _sw(0xAE);
    if (data.length != 9) return _sw(0x7E);
    if (data[0] != fileNo) return _sw(0xF0);

    final expected = _commandMac(
      DesfireCommand.getValue,
      Uint8List.sublistView(data, 0, 1),
    );
    if (!bytesEqual(expected, Uint8List.sublistView(data, 1, 9))) {
      return _sw(0x1E);
    }

    _counter++;
    final iv = calcIvResponse(_sessKeyEnc!, _ti, _counter);
    final cipher = aesCbcEncrypt(
      _sessKeyEnc!,
      iv,
      padIso7816(encodeValue(_balance)),
    );
    final mac = _maybeCorrupt(DesfireCommand.getValue, _responseMac(cipher));
    return _sw(0x00, Uint8List.fromList([...cipher, ...mac]));
  }

  Uint8List _valueOperation(int command, Uint8List data, {required int sign}) {
    if (!_authenticated) return _sw(0xAE);
    if (data.length != 25) return _sw(0x7E);
    if (data[0] != fileNo) return _sw(0xF0);

    final macInput = Uint8List.sublistView(data, 0, 17);
    final expected = _commandMac(command, macInput);
    if (!bytesEqual(expected, Uint8List.sublistView(data, 17, 25))) {
      return _sw(0x1E);
    }

    final iv = calcIvCommand(_sessKeyEnc!, _ti, _counter);
    final plain = aesCbcDecrypt(
      _sessKeyEnc!,
      iv,
      Uint8List.sublistView(data, 1, 17),
    );
    if (plain[4] != 0x80) return _sw(0x1E);
    final amount = decodeValue(plain);

    final projected = _balance + _pendingDelta + sign * amount;
    if (projected < lowerLimit || projected > upperLimit) {
      _counter++;
      return _sw(0xBE);
    }

    _pendingDelta += sign * amount;
    _transactionOpen = true;

    _counter++;
    final mac = _maybeCorrupt(command, _responseMac(Uint8List(0)));
    return _sw(0x00, mac);
  }

  Uint8List _commit(Uint8List data) {
    if (!_authenticated) return _sw(0xAE);
    if (data.length != 8) return _sw(0x7E);

    final expected = _commandMac(
      DesfireCommand.commitTransaction,
      Uint8List(0),
    );
    if (!bytesEqual(expected, data)) return _sw(0x1E);

    _balance += _pendingDelta;
    _pendingDelta = 0;
    _transactionOpen = false;

    _counter++;
    final mac = _maybeCorrupt(
      DesfireCommand.commitTransaction,
      _responseMac(Uint8List(0)),
    );
    return _sw(0x00, mac);
  }

  Uint8List _abort(Uint8List data) {
    if (!_authenticated) return _sw(0xAE);
    if (data.length != 8) return _sw(0x7E);

    final expected = _commandMac(DesfireCommand.abortTransaction, Uint8List(0));
    if (!bytesEqual(expected, data)) return _sw(0x1E);

    _pendingDelta = 0;
    _transactionOpen = false;

    _counter++;
    final mac = _maybeCorrupt(
      DesfireCommand.abortTransaction,
      _responseMac(Uint8List(0)),
    );
    return _sw(0x00, mac);
  }

  /// Simulates the card leaving the RF field: any staged transaction is lost.
  void removeFromField() {
    _pendingDelta = 0;
    _transactionOpen = false;
    _sessKeyEnc = null;
    _sessKeyMac = null;
    _appSelected = false;
  }
}

/// Transceiver that always reports a link failure, for error-path tests.
class DeadTransceiver implements Transceiver {
  @override
  Future<Uint8List> transceive(Uint8List apdu) async {
    throw const TransceiveException();
  }
}
