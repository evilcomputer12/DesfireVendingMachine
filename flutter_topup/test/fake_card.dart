/// A software DESFire EV2 card, good enough to exercise the whole command
/// layer without any NFC hardware.
///
/// It implements the card side of the protocol independently of
/// [DesfireCard]: it derives its own session keys, checks the command CMACs
/// the client sends, maintains its own command counter, and — importantly —
/// models the value-file transaction buffer, so a `Credit` that is never
/// committed genuinely does not change the balance.
///
/// It also models enough of the *card* level to test provisioning end to end:
/// the PICC application (AID 000000) with its own master key, the legacy
/// `Authenticate` (0x0A) and `AuthenticateISO` (0x1A) handshakes a blank card
/// needs, an application directory that starts empty and answers `0xA0`, and
/// `CreateApplication` / `ChangeKey` / `CreateValueFile`.
library;

import 'dart:typed_data';

import 'package:flutter_topup/desfire/desfire_card.dart';
import 'package:flutter_topup/desfire/desfire_crypto.dart';
import 'package:flutter_topup/desfire/desfire_exceptions.dart';
import 'package:flutter_topup/desfire/desfire_legacy_crypto.dart';
import 'package:flutter_topup/desfire/value_file.dart';
import 'package:flutter_topup/nfc/nfc_service.dart';

/// [NfcService] stand-in that hands the operation a card backed by a
/// [FakeDesfireCard] instead of an Android IsoDep connection.
class FakeNfcService extends NfcService {
  FakeNfcService(this.transceiver, {this.uid, this.randomSource});

  /// Transport the simulated card is reached through.
  final Transceiver transceiver;

  /// UID reported to the operation, if any.
  final Uint8List? uid;

  /// Nonce source for the [DesfireCard] handed to the operation.
  final RandomSource? randomSource;

  /// Number of times an operation was run — i.e. cards tapped.
  int runs = 0;

  @override
  Future<T> run<T>(
    CardOperation<T> operation, {
    DesfireLogger? logger,
    Duration timeout = const Duration(seconds: 60),
  }) async {
    runs++;
    return operation(
      DesfireCard(
        transceiver,
        randomSource: randomSource,
        logger: logger,
        // No real card here, so no non-volatile write to wait for.
        nvWriteDelay: Duration.zero,
      ),
      uid,
    );
  }
}

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

/// Key type the simulated card's *PICC master key* is declared as, which is
/// what decides which authentication command it answers.
///
/// Real blank cards differ here by generation, which is why
/// `_auth_picc_factory` in the C library tries all three in turn.
enum PiccKeyType {
  /// DES / 2K3DES — answers `Authenticate` (0x0A) only.
  legacy2k3des,

  /// 3K3DES — answers `AuthenticateISO` (0x1A) only.
  iso3k3des,

  /// AES-128 — answers `AuthenticateEV2First` (0x71) only.
  aes,
}

/// Which step-2 construction the simulated card expects from the reader in a
/// legacy 0x0A handshake.
///
/// For the all-zero factory key the two are indistinguishable — an all-zero
/// DES key is a weak key, so `E_K == D_K` — but a card modelled with a
/// non-zero legacy key can tell them apart, which is how the tests prove the
/// reader implements the D40 direction rather than the ISO one.
enum LegacyAuthMode {
  /// `c[i] = DEC(p[i] XOR c[i-1])`, the DESFire native rule.
  d40,

  /// `c[i] = ENC(p[i] XOR c[i-1])`, what `df_authenticate_legacy` emits.
  cbcEncrypt,
}

/// Record of one `CreateApplication` the card accepted.
class CreatedApplication {
  const CreatedApplication({
    required this.aid,
    required this.keySettings,
    required this.numKeys,
    required this.aesKeys,
  });

  /// AID that was created.
  final int aid;

  /// Key-settings byte (the C always sends 0xEF).
  final int keySettings;

  /// Number of keys the application was given.
  final int numKeys;

  /// Whether bit 7 of the key-count byte asked for AES application keys.
  final bool aesKeys;

  @override
  String toString() =>
      'CreatedApplication(aid: 0x${aid.toRadixString(16).padLeft(6, '0')}, '
      'keySettings: 0x${keySettings.toRadixString(16)}, '
      'numKeys: $numKeys, aes: $aesKeys)';
}

enum _Level { none, picc, application }

/// Simulated DESFire EV2 card.
class FakeDesfireCard implements Transceiver {
  FakeDesfireCard({
    required this.applicationId,
    required Map<int, Uint8List> keys,
    required this.fileNo,
    int initialBalance = 0,
    this.lowerLimit = 0,
    this.upperLimit = 100000,
    Uint8List? rndB,
    Uint8List? transactionId,
    this.applicationExists = true,
    this.valueFileExists = true,
    Uint8List? piccMasterKey,
    this.piccKeyType = PiccKeyType.legacy2k3des,
    this.legacyAuthMode = LegacyAuthMode.d40,
    Uint8List? legacyRndB,
  }) : keys = Map<int, Uint8List>.of(keys),
       _balance = initialBalance,
       _rndB =
           rndB ?? Uint8List.fromList(List<int>.generate(16, (i) => 0xA0 + i)),
       _legacyRndB =
           legacyRndB ??
           Uint8List.fromList(List<int>.generate(8, (i) => 0xB0 + i)),
       piccMasterKey = piccMasterKey ?? Uint8List(16),
       _ti = transactionId ?? fromHex('deadbeef');

  /// A card straight out of the box: no applications, factory PICC master key.
  factory FakeDesfireCard.blank({
    required int applicationId,
    required int fileNo,
    int lowerLimit = 0,
    int upperLimit = 100000,
    Uint8List? piccMasterKey,
    PiccKeyType piccKeyType = PiccKeyType.legacy2k3des,
    LegacyAuthMode legacyAuthMode = LegacyAuthMode.d40,
    Uint8List? rndB,
    Uint8List? legacyRndB,
  }) {
    return FakeDesfireCard(
      applicationId: applicationId,
      keys: const {},
      fileNo: fileNo,
      lowerLimit: lowerLimit,
      upperLimit: upperLimit,
      applicationExists: false,
      valueFileExists: false,
      piccMasterKey: piccMasterKey,
      piccKeyType: piccKeyType,
      legacyAuthMode: legacyAuthMode,
      rndB: rndB,
      legacyRndB: legacyRndB,
    );
  }

  /// AID this card answers to.
  final int applicationId;

  /// Key number -> 16-byte AES key, for [applicationId].
  final Map<int, Uint8List> keys;

  /// Value file number.
  final int fileNo;

  /// Lower limit of the value file.
  final int lowerLimit;

  /// Upper limit of the value file.
  final int upperLimit;

  /// Whether [applicationId] is present in the card's application directory.
  bool applicationExists;

  /// Whether the value file exists inside [applicationId].
  bool valueFileExists;

  /// Card-level master key. The factory value is 16 zero bytes.
  final Uint8List piccMasterKey;

  /// Declared type of [piccMasterKey].
  final PiccKeyType piccKeyType;

  /// Step-2 construction this card expects in a 0x0A handshake.
  final LegacyAuthMode legacyAuthMode;

  final Uint8List _rndB;
  final Uint8List _legacyRndB;
  final Uint8List _ti;

  int _balance;
  int _pendingDelta = 0;
  bool _transactionOpen = false;
  _Level _level = _Level.none;
  bool _piccAuthenticated = false;

  Uint8List? _sessKeyEnc;
  Uint8List? _sessKeyMac;
  int _counter = 0;
  int _authKeyNo = 0;

  Uint8List? _pendingAuthKey;
  int _pendingAuthKeyNo = 0;
  ({Uint8List key, Uint8List challenge, bool iso})? _pendingLegacy;

  /// Every APDU the client sent, for assertions on the wire format.
  final List<Uint8List> sentApdus = [];

  /// Every DESFire command byte the client sent, in order.
  List<int> get sentCommands => [
    for (final apdu in sentApdus)
      if (apdu.length >= 2) apdu[1],
  ];

  /// Applications the client asked the card to create, in order.
  final List<CreatedApplication> createdApplications = [];

  /// Key numbers written with `ChangeKey`, in order.
  final List<int> changedKeys = [];

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

  /// Whether a PICC-level authentication is currently in force.
  bool get isPiccAuthenticated => _piccAuthenticated;

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
      case DesfireCommand.authenticateLegacy:
        return _legacyStep1(data, iso: false);
      case DesfireCommand.authenticateIso:
        return _legacyStep1(data, iso: true);
      case DesfireCommand.additionalFrame:
        return _pendingLegacy != null ? _legacyStep2(data) : _authStep2(data);
      case DesfireCommand.createApplication:
        return _createApplication(data);
      case DesfireCommand.changeKey:
        return _changeKey(data);
      case DesfireCommand.createValueFile:
        return _createValueFile(data);
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

  void _dropSession() {
    _sessKeyEnc = null;
    _sessKeyMac = null;
    _pendingAuthKey = null;
    _pendingLegacy = null;
    _counter = 0;
  }

  Uint8List _selectApplication(Uint8List data) {
    if (data.length != 3) return _sw(0x7E);
    final aid = data[0] | (data[1] << 8) | (data[2] << 16);
    _dropSession();
    _piccAuthenticated = false;

    if (aid == kPiccApplicationId) {
      _level = _Level.picc;
      return _sw(0x00);
    }
    if (aid != applicationId || !applicationExists) {
      _level = _Level.none;
      return _sw(0xA0);
    }
    _level = _Level.application;
    return _sw(0x00);
  }

  // ------------------------------------------------------------------
  // AuthenticateEV2First (0x71)
  // ------------------------------------------------------------------

  Uint8List _authStep1(Uint8List data) {
    if (_level == _Level.none) return _sw(0x1C);
    if (data.length != 2) return _sw(0x7E);

    final Uint8List? key;
    if (_level == _Level.picc) {
      key = piccKeyType == PiccKeyType.aes && data[0] == 0
          ? piccMasterKey
          : null;
    } else {
      key = keys[data[0]];
    }
    if (key == null) return _sw(0x40);

    _pendingAuthKey = key;
    _pendingAuthKeyNo = data[0];
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
    _authKeyNo = _pendingAuthKeyNo;
    if (_level == _Level.picc) _piccAuthenticated = true;
    _pendingAuthKey = null;
    return _sw(0x00, response);
  }

  // ------------------------------------------------------------------
  // Legacy Authenticate (0x0A) and AuthenticateISO (0x1A)
  // ------------------------------------------------------------------

  /// The 24-byte key an ISO handshake uses, matching `_auth_picc_factory`'s
  /// `key16 || key16[0..8]`.
  Uint8List get _piccKey24 => Uint8List(24)
    ..setRange(0, 16, piccMasterKey)
    ..setRange(16, 24, piccMasterKey);

  Uint8List _legacyStep1(Uint8List data, {required bool iso}) {
    if (_level != _Level.picc) return _sw(0x1C);
    if (data.length != 1 || data[0] != 0) return _sw(0x40);

    final wanted = iso ? PiccKeyType.iso3k3des : PiccKeyType.legacy2k3des;
    if (piccKeyType != wanted) return _sw(0xAE);

    final zeroIv = Uint8List(kDesBlockSize);
    final challenge = iso
        ? des3k3CbcEncrypt(_piccKey24, zeroIv, _legacyRndB)
        : des3CbcEncrypt(piccMasterKey, zeroIv, _legacyRndB);
    _pendingLegacy = (
      key: iso ? _piccKey24 : piccMasterKey,
      challenge: challenge,
      iso: iso,
    );
    return _sw(0xAF, challenge);
  }

  Uint8List _legacyStep2(Uint8List data) {
    final pending = _pendingLegacy!;
    _pendingLegacy = null;
    if (data.length != 2 * kDesBlockSize) return _sw(0x7E);

    final first = Uint8List.sublistView(data, 0, kDesBlockSize);
    final second = Uint8List.sublistView(
      data,
      kDesBlockSize,
      2 * kDesBlockSize,
    );

    final Uint8List plain;
    if (pending.iso) {
      plain = des3k3CbcDecrypt(pending.key, pending.challenge, data);
    } else {
      switch (legacyAuthMode) {
        case LegacyAuthMode.d40:
          // Invert c[i] = DEC(p[i] XOR c[i-1]).
          final b0 = des3EcbEncrypt(pending.key, first);
          final b1 = des3EcbEncrypt(pending.key, second);
          plain = Uint8List(2 * kDesBlockSize);
          for (var i = 0; i < kDesBlockSize; i++) {
            plain[i] = b0[i] ^ pending.challenge[i];
            plain[kDesBlockSize + i] = b1[i] ^ first[i];
          }
        case LegacyAuthMode.cbcEncrypt:
          plain = des3CbcDecrypt(pending.key, pending.challenge, data);
      }
    }

    final rndA = Uint8List.sublistView(plain, 0, kDesBlockSize);
    final rndBRot = Uint8List.sublistView(
      plain,
      kDesBlockSize,
      2 * kDesBlockSize,
    );
    if (!bytesEqual(rndBRot, rotateLeft1(_legacyRndB))) return _sw(0xAE);

    // The card's reply is enc(RndA') chained onto the last block it received.
    final reply = pending.iso
        ? des3k3CbcEncrypt(pending.key, second, rotateLeft1(rndA))
        : des3CbcEncrypt(pending.key, second, rotateLeft1(rndA));

    _piccAuthenticated = true;
    // Legacy authentication installs no EV2 session: no TI, no counter.
    _sessKeyEnc = null;
    _sessKeyMac = null;
    return _sw(0x00, reply);
  }

  // ------------------------------------------------------------------
  // Application and key management
  // ------------------------------------------------------------------

  Uint8List _createApplication(Uint8List data) {
    if (_level != _Level.picc) return _sw(0x1C);
    if (!_piccAuthenticated) return _sw(0xAE);

    final maced = _authenticated;
    final expectedLength = maced ? 13 : 5;
    if (data.length != expectedLength) return _sw(0x7E);

    final plain = Uint8List.sublistView(data, 0, 5);
    if (maced) {
      final expected = _commandMac(DesfireCommand.createApplication, plain);
      if (!bytesEqual(expected, Uint8List.sublistView(data, 5, 13))) {
        return _sw(0x1E);
      }
      _counter++;
    }

    final aid = plain[0] | (plain[1] << 8) | (plain[2] << 16);
    final numKeys = plain[4] & 0x0F;
    createdApplications.add(
      CreatedApplication(
        aid: aid,
        keySettings: plain[3],
        numKeys: numKeys,
        aesKeys: (plain[4] & 0x80) != 0,
      ),
    );

    if (aid == applicationId && applicationExists) return _sw(0xDE);
    if (aid == applicationId) {
      applicationExists = true;
      keys.clear();
      for (var i = 0; i < numKeys; i++) {
        keys[i] = Uint8List(16);
      }
    }
    return _sw(0x00);
  }

  Uint8List _changeKey(Uint8List data) {
    if (!_authenticated) return _sw(0xAE);
    if (data.length != 41) return _sw(0x7E);

    final keyNo = data[0];
    final cmdData = Uint8List.sublistView(data, 0, 33);
    final expected = _commandMac(DesfireCommand.changeKey, cmdData);
    if (!bytesEqual(expected, Uint8List.sublistView(data, 33, 41))) {
      return _sw(0x1E);
    }

    final iv = calcIvCommand(_sessKeyEnc!, _ti, _counter);
    final plain = aesCbcDecrypt(
      _sessKeyEnc!,
      iv,
      Uint8List.sublistView(data, 1, 33),
    );

    final Uint8List newKey;
    if (keyNo == _authKeyNo) {
      if (plain[17] != 0x80) return _sw(0x1E);
      newKey = Uint8List.fromList(plain.sublist(0, 16));
    } else {
      if (plain[21] != 0x80) return _sw(0x1E);
      final old = keys[keyNo];
      if (old == null) return _sw(0x40);
      newKey = Uint8List(16);
      for (var i = 0; i < 16; i++) {
        newKey[i] = plain[i] ^ old[i];
      }
      final crc = desfireCrc32(newKey);
      if (!bytesEqual(crc32ToBytes(crc), plain.sublist(16, 20))) {
        return _sw(0x1E);
      }
    }

    keys[keyNo] = newKey;
    changedKeys.add(keyNo);
    _counter++;

    if (keyNo == _authKeyNo) {
      // The card tears the session down when the authenticated key changes.
      _sessKeyEnc = null;
      _sessKeyMac = null;
      return _sw(0x00);
    }
    return _sw(0x00, _responseMac(Uint8List(0)));
  }

  Uint8List _createValueFile(Uint8List data) {
    if (!_authenticated) return _sw(0xAE);
    if (data.length != 25) return _sw(0x7E);

    final plain = Uint8List.sublistView(data, 0, 17);
    final expected = _commandMac(DesfireCommand.createValueFile, plain);
    if (!bytesEqual(expected, Uint8List.sublistView(data, 17, 25))) {
      return _sw(0x1E);
    }
    _counter++;

    if (plain[0] == fileNo && valueFileExists) return _sw(0xDE);
    if (plain[0] != fileNo) return _sw(0x9E);

    valueFileExists = true;
    _balance = decodeValue(plain, 12);
    return _sw(0x00, _responseMac(Uint8List(0)));
  }

  // ------------------------------------------------------------------
  // Value file
  // ------------------------------------------------------------------

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
    if (data[0] != fileNo || !valueFileExists) return _sw(0xF0);

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
    if (data[0] != fileNo || !valueFileExists) return _sw(0xF0);

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
    _dropSession();
    _piccAuthenticated = false;
    _level = _Level.none;
  }
}

/// Transceiver that always reports a link failure, for error-path tests.
class DeadTransceiver implements Transceiver {
  @override
  Future<Uint8List> transceive(Uint8List apdu) async {
    throw const TransceiveException();
  }
}
