/// DESFire EV2/EV3 command layer.
///
/// The commands that already exist in the reference C library
/// (`SelectApplication`, `AuthenticateEV2First`) are ported byte-for-byte. The
/// value-file commands (`CreateValueFile`, `GetValue`, `Credit`, `Debit`,
/// `LimitedCredit`, `CommitTransaction`, `AbortTransaction`) do not exist in
/// the C library and are implemented here against the NXP EV2 secure-messaging
/// rules, reusing the exact same MAC/IV construction as the ported code.
///
/// Nothing in this file touches Flutter or NFC: the only I/O is through the
/// [Transceiver] interface, so the whole command layer is unit testable against
/// a scripted fake.
library;

import 'dart:math';
import 'dart:typed_data';

import 'desfire_crypto.dart';
import 'desfire_exceptions.dart';
import 'desfire_session.dart';
import 'value_file.dart';

/// DESFire native command bytes.
class DesfireCommand {
  const DesfireCommand._();

  // --- ported from desfire_cmd.c ---
  /// GetVersion.
  static const int getVersion = 0x60;

  /// SelectApplication.
  static const int selectApplication = 0x5A;

  /// AuthenticateEV2First (AES-128).
  static const int authenticateEv2First = 0x71;

  /// Additional frame / continue.
  static const int additionalFrame = 0xAF;

  // --- value-file commands, new in this port ---
  /// CreateValueFile.
  static const int createValueFile = 0xCC;

  /// GetValue.
  static const int getValue = 0x6C;

  /// Credit.
  static const int credit = 0x0C;

  /// Debit.
  static const int debit = 0xDC;

  /// LimitedCredit.
  static const int limitedCredit = 0x1C;

  /// CommitTransaction.
  static const int commitTransaction = 0xC7;

  /// AbortTransaction.
  static const int abortTransaction = 0xA7;
}

/// DESFire status bytes that the command layer branches on.
class DesfireStatus {
  const DesfireStatus._();

  /// Operation OK.
  static const int ok = 0x00;

  /// More data follows; send `additionalFrame`.
  static const int additionalFrame = 0xAF;

  /// Value would leave the file's configured limits.
  static const int boundaryError = 0xBE;

  /// File or application already exists.
  static const int duplicateError = 0xDE;
}

/// Raw byte-level transport to a card. Implemented over Android IsoDep in
/// `lib/nfc/`, and over a scripted list of responses in tests.
abstract class Transceiver {
  /// Sends a complete ISO 7816-4 APDU and returns the complete response
  /// including the two status bytes.
  ///
  /// Implementations must throw [TransceiveException] on link failure.
  Future<Uint8List> transceive(Uint8List apdu);
}

/// Source of the `RndA` nonce used during authentication.
abstract class RandomSource {
  /// Returns [count] fresh random bytes.
  Uint8List nextBytes(int count);
}

/// Cryptographically secure [RandomSource] backed by `Random.secure()`.
class SecureRandomSource implements RandomSource {
  SecureRandomSource() : _random = Random.secure();

  final Random _random;

  @override
  Uint8List nextBytes(int count) {
    final out = Uint8List(count);
    for (var i = 0; i < count; i++) {
      out[i] = _random.nextInt(256);
    }
    return out;
  }
}

/// Optional trace callback; mirrors `df_log_fn` from the C library.
typedef DesfireLogger = void Function(String message);

/// Result of a single APDU exchange.
class DesfireResponse {
  const DesfireResponse(this.body, this.status);

  /// Response payload with SW1/SW2 stripped.
  final Uint8List body;

  /// The DESFire status byte (SW2).
  final int status;
}

/// Card version information returned by [DesfireCard.getVersion].
class DesfireVersion {
  const DesfireVersion({
    required this.vendorId,
    required this.hwType,
    required this.hwSubType,
    required this.hwMajor,
    required this.hwMinor,
    required this.hwStorage,
    required this.hwProtocol,
  });

  /// NXP vendor identifier (0x04 for NXP).
  final int vendorId;

  /// Hardware type byte.
  final int hwType;

  /// Hardware sub-type byte.
  final int hwSubType;

  /// Hardware major version, which is what identifies EV1/EV2/EV3.
  final int hwMajor;

  /// Hardware minor version.
  final int hwMinor;

  /// Storage size code.
  final int hwStorage;

  /// Protocol byte.
  final int hwProtocol;

  /// Human-readable product name. Port of `_hw_name`.
  String get name {
    if (hwType == 0x04) {
      return hwSubType == 0x02 ? 'NTAG 424 DNA TT' : 'NTAG 424 DNA';
    }
    if (hwType == 0x01) {
      switch (hwMajor) {
        case 0x00:
          return 'DESFire';
        case 0x01:
          return 'DESFire EV1';
        case 0x12:
          return 'DESFire EV2';
        case 0x22:
          return 'DESFire Light';
        case 0x32:
          return 'DESFire Light EV1';
        case 0x33:
          return 'DESFire EV3';
        default:
          return 'DESFire (unknown)';
      }
    }
    if (hwType == 0x02) return 'MIFARE Plus';
    if (hwType == 0x03) return 'MIFARE Ultralight EV1';
    return 'Unknown';
  }

  @override
  String toString() => name;
}

/// Result of a committed value-file change.
class TopUpResult {
  const TopUpResult({
    required this.previousBalance,
    required this.amount,
    required this.newBalance,
  });

  /// Balance read from the card before the credit, in cents.
  final int previousBalance;

  /// Amount credited, in cents.
  final int amount;

  /// Balance read back from the card after `CommitTransaction`, in cents.
  final int newBalance;

  @override
  String toString() =>
      'TopUpResult($previousBalance + $amount = $newBalance cents)';
}

/// Builds an ISO 7816-4 wrapped DESFire APDU: `90 CMD 00 00 [Lc DATA] 00`.
///
/// Port of `_build_apdu`. When [data] is empty the Lc byte is omitted entirely,
/// which is what the card expects for parameterless commands.
Uint8List buildApdu(int command, [Uint8List? data]) {
  final payload = data ?? Uint8List(0);
  if (payload.length > 255) {
    throw InvalidParameterException(
      'APDU payload of ${payload.length} bytes exceeds the short-APDU limit',
    );
  }
  final out = BytesBuilder();
  out.addByte(0x90);
  out.addByte(command & 0xFF);
  out.addByte(0x00);
  out.addByte(0x00);
  if (payload.isNotEmpty) {
    out.addByte(payload.length);
    out.add(payload);
  }
  out.addByte(0x00);
  return out.toBytes();
}

/// Splits a raw card response into body and status byte.
///
/// Throws [ResponseTooShortException] when fewer than 2 bytes arrived and
/// [FramingException] when SW1 is not `0x91`.
DesfireResponse parseResponse(Uint8List raw) {
  if (raw.length < 2) {
    throw ResponseTooShortException(
      'Card returned ${raw.length} byte(s); need SW1+SW2 at minimum.',
    );
  }
  final sw1 = raw[raw.length - 2];
  final sw2 = raw[raw.length - 1];
  if (sw1 != 0x91) {
    throw FramingException(sw1);
  }
  return DesfireResponse(Uint8List.sublistView(raw, 0, raw.length - 2), sw2);
}

/// High-level DESFire EV2/EV3 card interface.
class DesfireCard {
  DesfireCard(this.transceiver, {RandomSource? randomSource, this.logger})
    : _random = randomSource ?? SecureRandomSource();

  /// The byte transport in use.
  final Transceiver transceiver;

  /// Optional trace sink.
  final DesfireLogger? logger;

  final RandomSource _random;

  DesfireSession? _session;

  /// The active EV2 session, or null when not authenticated.
  DesfireSession? get session => _session;

  /// Whether a usable EV2 session exists.
  bool get isAuthenticated => _session?.isActive ?? false;

  DesfireSession get _requireSession {
    final s = _session;
    if (s == null || !s.isActive) {
      throw const AuthenticationFailedException(
        'Not authenticated — call authenticateEv2First first.',
      );
    }
    return s;
  }

  void _log(String message) => logger?.call(message);

  /// Sends one command and returns the parsed response, without interpreting
  /// the status byte.
  Future<DesfireResponse> _send(int command, [Uint8List? data]) async {
    final apdu = buildApdu(command, data);
    _log('TX ${toHex(apdu)}');
    final raw = await transceiver.transceive(apdu);
    _log('RX ${toHex(raw)}');
    return parseResponse(raw);
  }

  /// Sends one command and requires the status to be `0x00`.
  Future<Uint8List> _sendExpectOk(
    int command, [
    Uint8List? data,
    Set<int> alsoAccept = const {},
  ]) async {
    final response = await _send(command, data);
    if (response.status != DesfireStatus.ok &&
        !alsoAccept.contains(response.status)) {
      throw CardStatusException(response.status, command: command);
    }
    return response.body;
  }

  // ---------------------------------------------------------------------
  // Ported from the C library
  // ---------------------------------------------------------------------

  /// `GetVersion` (0x60). Three frames; only the first carries the hardware
  /// identification bytes this app cares about.
  Future<DesfireVersion> getVersion() async {
    var response = await _send(DesfireCommand.getVersion);
    if (response.status != DesfireStatus.additionalFrame ||
        response.body.length < 7) {
      throw CardStatusException(
        response.status,
        command: DesfireCommand.getVersion,
      );
    }
    final hw = Uint8List.fromList(response.body.sublist(0, 7));

    response = await _send(DesfireCommand.additionalFrame);
    if (response.status != DesfireStatus.additionalFrame) {
      throw CardStatusException(
        response.status,
        command: DesfireCommand.additionalFrame,
      );
    }
    response = await _send(DesfireCommand.additionalFrame);
    if (response.status != DesfireStatus.ok) {
      throw CardStatusException(
        response.status,
        command: DesfireCommand.additionalFrame,
      );
    }

    return DesfireVersion(
      vendorId: hw[0],
      hwType: hw[1],
      hwSubType: hw[2],
      hwMajor: hw[3],
      hwMinor: hw[4],
      hwStorage: hw[5],
      hwProtocol: hw[6],
    );
  }

  /// `SelectApplication` (0x5A). [aid] is the 3-byte AID in the low 24 bits,
  /// transmitted little-endian. Selecting an application always drops any
  /// existing session.
  Future<void> selectApplication(int aid) async {
    final data = Uint8List.fromList([
      aid & 0xFF,
      (aid >> 8) & 0xFF,
      (aid >> 16) & 0xFF,
    ]);
    await _sendExpectOk(DesfireCommand.selectApplication, data);
    _session?.invalidate();
    _session = null;
  }

  /// `AuthenticateEV2First` (0x71) with an AES-128 key.
  ///
  /// Port of `df_authenticate_ev2_first`. On success a fresh [DesfireSession]
  /// is installed with `cmdCounter == 0`.
  Future<DesfireSession> authenticateEv2First(int keyNo, Uint8List key) async {
    _session?.invalidate();
    _session = null;

    if (key.length != 16) {
      throw const InvalidParameterException('AES key must be 16 bytes');
    }

    // Step 1: [keyNo, LenCap=0x00] -> card returns E(RndB).
    final step1 = await _send(
      DesfireCommand.authenticateEv2First,
      Uint8List.fromList([keyNo & 0xFF, 0x00]),
    );
    if (step1.status != DesfireStatus.additionalFrame) {
      throw AuthenticationFailedException(
        'Card rejected AuthenticateEV2First: '
        '${describeDesfireStatus(step1.status)}',
      );
    }
    if (step1.body.length < 16) {
      throw const AuthenticationFailedException(
        'Card returned a short challenge.',
      );
    }

    final zeroIv = Uint8List(16);
    final rndB = aesCbcDecrypt(
      key,
      zeroIv,
      Uint8List.fromList(step1.body.sublist(0, 16)),
    );
    final rndA = _random.nextBytes(16);
    final rndBRot = rotateLeft1(rndB);

    final plain = Uint8List(32)
      ..setRange(0, 16, rndA)
      ..setRange(16, 32, rndBRot);
    final cryptogram = aesCbcEncrypt(key, zeroIv, plain);

    // Step 2: send E(RndA || RndB') -> card returns E(TI || RndA' || caps).
    final step2 = await _send(DesfireCommand.additionalFrame, cryptogram);
    if (step2.status != DesfireStatus.ok) {
      throw AuthenticationFailedException(
        'Card rejected the authentication token: '
        '${describeDesfireStatus(step2.status)} — wrong key?',
      );
    }
    if (step2.body.length < 20) {
      throw const AuthenticationFailedException(
        'Authentication response was too short.',
      );
    }

    // The C code decrypts 32 bytes when available, else 16.
    final decLen = step2.body.length >= 32 ? 32 : 16;
    final decrypted = aesCbcDecrypt(
      key,
      zeroIv,
      Uint8List.fromList(step2.body.sublist(0, decLen)),
    );

    final expectedRndARot = rotateLeft1(rndA);
    final receivedRndARot = Uint8List.sublistView(decrypted, 4, 20);
    if (!bytesEqual(expectedRndARot, receivedRndARot)) {
      throw const AuthenticationFailedException(
        'Card echoed the wrong RndA — key mismatch or replayed response.',
      );
    }

    final ti = Uint8List.sublistView(decrypted, 0, 4);
    final s = DesfireSession(
      sessKeyEnc: deriveSessionKey(key, rndA, rndB, SessionKeyLabel.enc),
      sessKeyMac: deriveSessionKey(key, rndA, rndB, SessionKeyLabel.mac),
      ti: ti,
      authKeyNo: keyNo,
    );
    _session = s;
    _log('Authenticated: $s');
    return s;
  }

  // ---------------------------------------------------------------------
  // Value-file commands (not present in the C library)
  // ---------------------------------------------------------------------

  /// `CreateValueFile` (0xCC).
  ///
  /// Requires an active session authenticated with the application master key.
  /// Follows the same shape as `df_create_std_data_file`: plaintext command
  /// data with an 8-byte command CMAC appended.
  ///
  /// When [ignoreDuplicate] is true, a `0xDE` (file already exists) status is
  /// treated as success, which makes provisioning idempotent.
  Future<void> createValueFile(
    ValueFileSettings settings, {
    bool ignoreDuplicate = true,
  }) async {
    final s = _requireSession;
    final plain = settings.toBytes();
    final mac = s.commandMac(DesfireCommand.createValueFile, plain);

    final response = await _send(
      DesfireCommand.createValueFile,
      Uint8List.fromList([...plain, ...mac]),
    );
    if (response.status != DesfireStatus.ok) {
      if (ignoreDuplicate && response.status == DesfireStatus.duplicateError) {
        s.incrementCounter();
        return;
      }
      s.invalidate();
      throw CardStatusException(
        response.status,
        command: DesfireCommand.createValueFile,
      );
    }
    s.incrementCounter();
  }

  /// `GetValue` (0x6C) in CommMode.Full.
  ///
  /// Command data is the plaintext file number plus the command CMAC. The
  /// response is a single AES-CBC encrypted block holding the 4-byte value
  /// followed by ISO 7816-4 padding, then the 8-byte response CMAC.
  ///
  /// Returns the balance in cents.
  Future<int> getValue(int fileNo) async {
    final s = _requireSession;
    final header = Uint8List.fromList([fileNo & 0xFF]);
    final mac = s.commandMac(DesfireCommand.getValue, header);

    final body = await _sendExpectOk(
      DesfireCommand.getValue,
      Uint8List.fromList([...header, ...mac]),
    );

    s.incrementCounter();

    if (body.length < kAesBlockSize + 8) {
      throw ResponseTooShortException(
        'GetValue returned ${body.length} bytes; expected at least 24.',
      );
    }
    final encrypted = Uint8List.sublistView(body, 0, kAesBlockSize);
    final respMac = Uint8List.sublistView(
      body,
      kAesBlockSize,
      kAesBlockSize + 8,
    );
    if (!s.verifyResponseMac(encrypted, respMac)) {
      throw const CmacMismatchException(
        'GetValue response CMAC failed — refusing to trust the balance.',
      );
    }
    final plain = s.decryptResponseData(encrypted);
    return decodeValue(plain);
  }

  /// `Credit` (0x0C) in CommMode.Full.
  ///
  /// **This only stages the change.** The balance on the card does not move
  /// until [commitTransaction] returns successfully. Prefer [topUp], which
  /// sequences read / credit / commit / verify for you.
  Future<void> credit(int fileNo, int amountCents) async {
    if (amountCents <= 0) {
      throw const InvalidParameterException('Credit amount must be positive');
    }
    return _valueOperation(DesfireCommand.credit, fileNo, amountCents);
  }

  /// `Debit` (0xDC) in CommMode.Full.
  ///
  /// **This only stages the change.** It is not permanent until
  /// [commitTransaction] succeeds.
  Future<void> debit(int fileNo, int amountCents) async {
    if (amountCents <= 0) {
      throw const InvalidParameterException('Debit amount must be positive');
    }
    return _valueOperation(DesfireCommand.debit, fileNo, amountCents);
  }

  /// `LimitedCredit` (0x1C) in CommMode.Full.
  ///
  /// Credits without full read/write rights, bounded by the amount debited in
  /// the previous session. Only usable when the value file was created with
  /// `limitedCreditEnabled`. **Also requires [commitTransaction].**
  Future<void> limitedCredit(int fileNo, int amountCents) async {
    if (amountCents <= 0) {
      throw const InvalidParameterException(
        'LimitedCredit amount must be positive',
      );
    }
    return _valueOperation(DesfireCommand.limitedCredit, fileNo, amountCents);
  }

  /// Shared wire format for Credit / Debit / LimitedCredit.
  ///
  /// `fileNo` is a plaintext command header (it is not encrypted, matching the
  /// way `WriteData` keeps its header in the clear). The 4-byte value is the
  /// encrypted command data: ISO 7816-4 padded to 16 bytes then AES-CBC
  /// encrypted under the command IV. The command CMAC covers
  /// `[cmd][ctr][TI][fileNo][ciphertext]`.
  Future<void> _valueOperation(int command, int fileNo, int amountCents) async {
    final s = _requireSession;
    final header = Uint8List.fromList([fileNo & 0xFF]);
    final ciphertext = s.encryptCommandData(encodeValue(amountCents));
    final macInput = Uint8List.fromList([...header, ...ciphertext]);
    final mac = s.commandMac(command, macInput);

    final response = await _send(
      command,
      Uint8List.fromList([...macInput, ...mac]),
    );
    if (response.status != DesfireStatus.ok) {
      s.invalidate();
      throw CardStatusException(response.status, command: command);
    }

    s.incrementCounter();

    if (response.body.length < 8) {
      throw ResponseTooShortException(
        'Value command returned ${response.body.length} bytes; expected a '
        'CMAC.',
      );
    }
    final respMac = Uint8List.sublistView(
      response.body,
      response.body.length - 8,
    );
    if (!s.verifyResponseMac(null, respMac)) {
      throw const CmacMismatchException();
    }
  }

  /// `CommitTransaction` (0xC7) in CommMode.MAC.
  ///
  /// This is what actually makes a staged [credit] / [debit] permanent. If the
  /// card leaves the field before this returns, the card silently discards the
  /// staged delta and the balance is unchanged.
  ///
  /// [returnTransactionMac] sends the EV2 option byte `0x01`, which asks the
  /// card for the Transaction MAC Counter and Transaction MAC Value. Leave it
  /// false unless the application has a Transaction MAC file configured — the
  /// option byte is omitted entirely in that case, which is what EV1-style
  /// cards expect.
  Future<TransactionMac?> commitTransaction({
    bool returnTransactionMac = false,
  }) async {
    final s = _requireSession;
    final header = returnTransactionMac
        ? Uint8List.fromList([0x01])
        : Uint8List(0);
    final mac = s.commandMac(
      DesfireCommand.commitTransaction,
      header.isEmpty ? null : header,
    );

    final response = await _send(
      DesfireCommand.commitTransaction,
      Uint8List.fromList([...header, ...mac]),
    );
    if (response.status != DesfireStatus.ok) {
      s.invalidate();
      throw CardStatusException(
        response.status,
        command: DesfireCommand.commitTransaction,
      );
    }

    s.incrementCounter();

    final body = response.body;
    if (body.length < 8) {
      throw ResponseTooShortException(
        'CommitTransaction returned ${body.length} bytes; expected a CMAC.',
      );
    }
    final payload = Uint8List.sublistView(body, 0, body.length - 8);
    final respMac = Uint8List.sublistView(body, body.length - 8);
    if (!s.verifyResponseMac(payload.isEmpty ? null : payload, respMac)) {
      throw const CmacMismatchException(
        'CommitTransaction response CMAC failed — the commit is unconfirmed. '
        'Re-read the balance before assuming anything.',
      );
    }

    if (!returnTransactionMac || payload.length < 12) return null;
    return TransactionMac(
      counter: ByteData.sublistView(payload, 0, 4).getUint32(0, Endian.little),
      value: Uint8List.fromList(payload.sublist(4, 12)),
    );
  }

  /// `AbortTransaction` (0xA7) in CommMode.MAC.
  ///
  /// Explicitly discards any staged value change. Best-effort: if this throws,
  /// the card will discard the transaction anyway once it leaves the field.
  Future<void> abortTransaction() async {
    final s = _requireSession;
    final mac = s.commandMac(DesfireCommand.abortTransaction);

    final response = await _send(DesfireCommand.abortTransaction, mac);
    if (response.status != DesfireStatus.ok) {
      s.invalidate();
      throw CardStatusException(
        response.status,
        command: DesfireCommand.abortTransaction,
      );
    }
    s.incrementCounter();

    if (response.body.length >= 8) {
      final respMac = Uint8List.sublistView(
        response.body,
        response.body.length - 8,
      );
      if (!s.verifyResponseMac(null, respMac)) {
        throw const CmacMismatchException();
      }
    }
  }

  // ---------------------------------------------------------------------
  // Composed flows
  // ---------------------------------------------------------------------

  /// Reads the balance, credits [amountCents], commits, and reads back.
  ///
  /// The read-back is not decoration: `CommitTransaction` is the only point at
  /// which the card's balance actually changes, so the post-commit `GetValue`
  /// is what turns "the card said OK" into "the money is on the card". If any
  /// step before the commit fails, [abortTransaction] is attempted so the card
  /// is left in a clean state.
  ///
  /// Throws [LimitExceededException] before touching the card when the new
  /// balance would exceed [upperLimit].
  Future<TopUpResult> topUp(
    int fileNo,
    int amountCents, {
    int upperLimit = kValueFileMax,
  }) async {
    if (amountCents <= 0) {
      throw const InvalidParameterException('Top-up amount must be positive');
    }

    final previous = await getValue(fileNo);
    if (previous + amountCents > upperLimit) {
      throw LimitExceededException(
        balance: previous,
        requested: amountCents,
        upperLimit: upperLimit,
      );
    }

    try {
      await credit(fileNo, amountCents);
    } catch (_) {
      await _tryAbort();
      rethrow;
    }

    // Past this point the delta is staged on the card but not yet permanent.
    await commitTransaction();

    final updated = await getValue(fileNo);
    return TopUpResult(
      previousBalance: previous,
      amount: amountCents,
      newBalance: updated,
    );
  }

  /// Reads the balance, debits [amountCents], commits, and reads back.
  ///
  /// Throws [InsufficientFundsException] before touching the card when the
  /// balance would fall below [lowerLimit].
  Future<TopUpResult> spend(
    int fileNo,
    int amountCents, {
    int lowerLimit = 0,
  }) async {
    if (amountCents <= 0) {
      throw const InvalidParameterException('Debit amount must be positive');
    }

    final previous = await getValue(fileNo);
    if (previous - amountCents < lowerLimit) {
      throw InsufficientFundsException(
        balance: previous,
        requested: amountCents,
      );
    }

    try {
      await debit(fileNo, amountCents);
    } catch (_) {
      await _tryAbort();
      rethrow;
    }

    await commitTransaction();

    final updated = await getValue(fileNo);
    return TopUpResult(
      previousBalance: previous,
      amount: -amountCents,
      newBalance: updated,
    );
  }

  Future<void> _tryAbort() async {
    if (!isAuthenticated) return;
    try {
      await abortTransaction();
    } on DesfireException catch (e) {
      _log('AbortTransaction failed (ignored): $e');
    }
  }
}

/// Transaction MAC returned by `CommitTransaction` when the option byte asks
/// for it.
class TransactionMac {
  const TransactionMac({required this.counter, required this.value});

  /// Transaction MAC Counter (TMC).
  final int counter;

  /// Transaction MAC Value (TMV), 8 bytes.
  final Uint8List value;

  @override
  String toString() =>
      'TransactionMac(counter: $counter, value: ${toHex(value)})';
}
