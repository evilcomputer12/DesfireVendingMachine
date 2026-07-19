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
import 'desfire_legacy_crypto.dart';
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

  /// Authenticate, legacy / D40 (DES or 2K3DES).
  static const int authenticateLegacy = 0x0A;

  /// AuthenticateISO (3K3DES).
  static const int authenticateIso = 0x1A;

  /// CreateApplication.
  static const int createApplication = 0xCA;

  /// ChangeKey.
  static const int changeKey = 0xC4;

  /// Additional frame / continue.
  static const int additionalFrame = 0xAF;

  // NOTE: FormatPICC (0xFC) is deliberately absent, and must stay absent.
  //
  // The reference C library has it (`df_format_picc`) and `df_setup_desfire`
  // calls it as step 1 via `df_full_format`, which erases every application
  // and every file on the card. That is fine for a bench script aimed at a
  // known blank card. It is not fine here: this app provisions whatever card
  // the user happens to tap, and that card may be an office badge or a transit
  // card. A top-up app that meets an unrecognised card must never be able to
  // wipe it. `CardGateway.provisionCard` implements steps 2-7 of
  // `df_setup_desfire` only. Do not add 0xFC.

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

  /// Requested application not found — the AID is not on this card.
  static const int applicationNotFound = 0xA0;

  /// Requested file not found inside the selected application.
  static const int fileNotFound = 0xF0;

  /// Authentication error.
  static const int authenticationError = 0xAE;

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

/// Settle time given to the card before a `ChangeKey`.
///
/// `DF_NV_WRITE_DELAY_MS` in `desfire_cmd.c` is 75 ms, and the reference
/// firmware does install the delay callback (`platform_sleep_ms` in
/// `desfiire.c`), so this pause is part of the configuration that is known to
/// work against real cards. A key change is a non-volatile write and some
/// cards need a moment before the next command; the failure mode without it is
/// an intermittent `0xEE` mid-provisioning.
const Duration kDefaultNvWriteDelay = Duration(milliseconds: 75);

/// AID of the card-level "PICC" application — `APP_PICC` in the C library.
///
/// Selecting it moves to card level, where the PICC master key governs and
/// `CreateApplication` is permitted.
const int kPiccApplicationId = 0x000000;

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
  DesfireCard(
    this.transceiver, {
    RandomSource? randomSource,
    this.logger,
    this.nvWriteDelay = kDefaultNvWriteDelay,
  }) : _random = randomSource ?? SecureRandomSource();

  /// The byte transport in use.
  final Transceiver transceiver;

  /// Optional trace sink.
  final DesfireLogger? logger;

  /// Settle time given to the card before a key write. See
  /// [kDefaultNvWriteDelay]. Tests pass [Duration.zero].
  final Duration nvWriteDelay;

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
  ///
  /// A `0xA0` status means the AID is simply not on this card, which is the
  /// normal state of a blank card and of any card belonging to somebody else.
  /// It is raised as [CardNotProvisionedException] rather than a generic
  /// [CardStatusException] so callers can offer to set the card up instead of
  /// showing a hex error code.
  Future<void> selectApplication(int aid) async {
    final data = Uint8List.fromList([
      aid & 0xFF,
      (aid >> 8) & 0xFF,
      (aid >> 16) & 0xFF,
    ]);
    final response = await _send(DesfireCommand.selectApplication, data);
    if (response.status == DesfireStatus.applicationNotFound) {
      _session?.invalidate();
      _session = null;
      throw CardNotProvisionedException(applicationId: aid);
    }
    if (response.status != DesfireStatus.ok) {
      throw CardStatusException(
        response.status,
        command: DesfireCommand.selectApplication,
      );
    }
    _session?.invalidate();
    _session = null;
  }

  // ---------------------------------------------------------------------
  // Legacy (pre-AES) authentication — only needed to reach a blank card
  // ---------------------------------------------------------------------

  /// `Authenticate` (0x0A), the legacy D40 handshake, with a 16-byte
  /// DES/2K3DES key. Port of `df_authenticate_legacy`.
  ///
  /// Structurally this is *not* a cut-down EV2 handshake. There is no
  /// transaction identifier and no command counter, the nonces are 8 bytes
  /// rather than 16, and — the part that trips people up — the reader applies
  /// the DES **decrypt** primitive when sending. Accordingly this method
  /// **does not install a session** (mirroring the C's "session stays inactive
  /// — legacy auth has no EV2 MAC/TI counter"): commands issued afterwards go
  /// out in plain, which is exactly what `CreateApplication` needs.
  ///
  /// [cbcSendDecrypt] selects the step-2 construction:
  ///
  /// * `true` (default) — the D40 rule, `c[i] = DEC(p[i] XOR c[i-1])`.
  /// * `false` — the ISO-style `c[i] = ENC(p[i] XOR c[i-1])` that
  ///   `df_authenticate_legacy` actually emits.
  ///
  /// For the 16-zero-byte factory key the two are byte-identical, because an
  /// all-zero DES key is a weak key and `E_K == D_K` for it; see
  /// [desfireLegacyCbcSend]. [authenticatePiccFactory] tries both so a card
  /// with a non-default legacy key still has a chance of working.
  ///
  /// Like the C, a `0x00` status is accepted as success even if the card's
  /// `RndA'` echo cannot be reproduced — the echo is only ever logged. The
  /// authentication that matters is the card's, not ours: nothing here trusts
  /// data the card returns, it only needs the card to grant PICC-level rights
  /// for the subsequent `CreateApplication`.
  Future<void> authenticateLegacy(
    int keyNo,
    Uint8List key16, {
    bool cbcSendDecrypt = true,
  }) async {
    _session?.invalidate();
    _session = null;

    if (key16.length != 16) {
      throw const InvalidParameterException(
        'Legacy DES/2K3DES key must be 16 bytes',
      );
    }

    final step1 = await _send(
      DesfireCommand.authenticateLegacy,
      Uint8List.fromList([keyNo & 0xFF]),
    );
    if (step1.status != DesfireStatus.additionalFrame ||
        step1.body.length != kDesBlockSize) {
      throw AuthenticationFailedException(
        'Authenticate (0x0A) step 1 refused: '
        '${describeDesfireStatus(step1.status)} '
        '(${step1.body.length} challenge bytes, expected 8)',
      );
    }

    final encRndB = Uint8List.fromList(step1.body.sublist(0, kDesBlockSize));
    final zeroIv = Uint8List(kDesBlockSize);
    final rndB = desfireLegacyCbcReceive(key16, zeroIv, encRndB);
    final rndA = _random.nextBytes(kDesBlockSize);
    final rndBRot = rotateLeft1(rndB);

    final plain = Uint8List(2 * kDesBlockSize)
      ..setRange(0, kDesBlockSize, rndA)
      ..setRange(kDesBlockSize, 2 * kDesBlockSize, rndBRot);

    // IV for step 2 is the card's challenge ciphertext, not zero.
    final token = cbcSendDecrypt
        ? desfireLegacyCbcSend(key16, encRndB, plain)
        : des3CbcEncrypt(key16, encRndB, plain);

    final step2 = await _send(DesfireCommand.additionalFrame, token);
    if (step2.status != DesfireStatus.ok) {
      throw AuthenticationFailedException(
        'Authenticate (0x0A) step 2 refused: '
        '${describeDesfireStatus(step2.status)} — wrong PICC master key, or '
        'the card wants a different legacy key type.',
      );
    }

    if (step2.body.length >= kDesBlockSize) {
      final received = desfireLegacyCbcReceive(
        key16,
        Uint8List.sublistView(token, kDesBlockSize, 2 * kDesBlockSize),
        Uint8List.fromList(step2.body.sublist(0, kDesBlockSize)),
      );
      _log(
        bytesEqual(rotateLeft1(rndA), received)
            ? 'Authenticate (0x0A) OK — RndA echo verified'
            : 'Authenticate (0x0A) OK — card accepted, RndA echo not '
                  'reproducible with this construction',
      );
    } else {
      _log('Authenticate (0x0A) OK — card accepted');
    }
  }

  /// `AuthenticateISO` (0x1A) with a 24-byte 3K3DES key. Port of
  /// `df_authenticate_iso`.
  ///
  /// Same shape as [authenticateLegacy] but with ordinary CBC in both
  /// directions and a three-key DES engine. Also installs no session.
  Future<void> authenticateIso(int keyNo, Uint8List key24) async {
    _session?.invalidate();
    _session = null;

    if (key24.length != 24) {
      throw const InvalidParameterException('3K3DES key must be 24 bytes');
    }

    final step1 = await _send(
      DesfireCommand.authenticateIso,
      Uint8List.fromList([keyNo & 0xFF]),
    );
    if (step1.status != DesfireStatus.additionalFrame ||
        step1.body.length != kDesBlockSize) {
      throw AuthenticationFailedException(
        'AuthenticateISO (0x1A) step 1 refused: '
        '${describeDesfireStatus(step1.status)} '
        '(${step1.body.length} challenge bytes, expected 8)',
      );
    }

    final encRndB = Uint8List.fromList(step1.body.sublist(0, kDesBlockSize));
    final zeroIv = Uint8List(kDesBlockSize);
    final rndB = des3k3CbcDecrypt(key24, zeroIv, encRndB);
    final rndA = _random.nextBytes(kDesBlockSize);
    final rndBRot = rotateLeft1(rndB);

    final plain = Uint8List(2 * kDesBlockSize)
      ..setRange(0, kDesBlockSize, rndA)
      ..setRange(kDesBlockSize, 2 * kDesBlockSize, rndBRot);
    final token = des3k3CbcEncrypt(key24, encRndB, plain);

    final step2 = await _send(DesfireCommand.additionalFrame, token);
    if (step2.status != DesfireStatus.ok) {
      throw AuthenticationFailedException(
        'AuthenticateISO (0x1A) step 2 refused: '
        '${describeDesfireStatus(step2.status)} — wrong PICC master key.',
      );
    }
    _log('AuthenticateISO (0x1A) OK — card accepted');
  }

  /// Authenticates at PICC level with a factory master key, trying each key
  /// type a card generation might be using. Mirrors `_auth_picc_factory`.
  ///
  /// The factory PICC master key is 16 zero bytes on every DESFire, but its
  /// declared *type* is not constant across generations: older cards want the
  /// D40 handshake (0x0A), some EV1/EV2 cards are shipped or left configured
  /// for 3K3DES (0x1A, key `key16 || key16[0..8]`), and a card that has been
  /// through an AES personalisation wants `AuthenticateEV2First` (0x71). The C
  /// tries all three in that order and so does this.
  ///
  /// Only [DesfireCommand.authenticateEv2First] leaves a usable session
  /// behind; after either legacy path [isAuthenticated] stays false and
  /// subsequent commands go out in plain.
  Future<void> authenticatePiccFactory(Uint8List key16) async {
    if (key16.length != 16) {
      throw const InvalidParameterException('PICC master key must be 16 bytes');
    }
    final key24 = Uint8List(24)
      ..setRange(0, 16, key16)
      ..setRange(16, 24, key16);

    final attempts = <String, Future<void> Function()>{
      'Authenticate (0x0A), D40 CBC-send-decrypt': () =>
          authenticateLegacy(0, key16),
      'Authenticate (0x0A), CBC-encrypt (as df_authenticate_legacy)': () =>
          authenticateLegacy(0, key16, cbcSendDecrypt: false),
      'AuthenticateISO (0x1A), 3K3DES': () => authenticateIso(0, key24),
      'AuthenticateEV2First (0x71), AES-128': () async {
        await authenticateEv2First(0, key16);
      },
    };

    final failures = <String>[];
    for (final entry in attempts.entries) {
      try {
        await entry.value();
        _log('PICC factory auth succeeded via ${entry.key}');
        return;
      } on TransceiveException {
        // The link is gone; retrying a different key type cannot help and
        // would only keep the user holding a card against a dead session.
        rethrow;
      } on DesfireException catch (e) {
        _log('PICC factory auth failed via ${entry.key}: ${e.message}');
        failures.add('${entry.key}: ${e.message}');
      }
    }

    throw AuthenticationFailedException(
      'No PICC-level authentication succeeded with the configured card master '
      'key. Tried:\n${failures.join('\n')}',
    );
  }

  // ---------------------------------------------------------------------
  // Application and key management
  // ---------------------------------------------------------------------

  /// `CreateApplication` (0xCA). Port of `df_create_application`.
  ///
  /// Command data is exactly the C's five bytes:
  /// `[aidLo, aidMid, aidHi, 0xEF, 0x80 | numKeys]`. `0xEF` is the key-settings
  /// byte and `0x80` in the final byte selects **AES** application keys, which
  /// is what lets everything after this point use `AuthenticateEV2First`.
  ///
  /// The 8-byte command CMAC is appended only when an EV2 session is live. It
  /// normally is not: this command runs straight after a legacy PICC
  /// authentication, which installs no session, so the command goes out plain.
  ///
  /// A `0xDE` (DUPLICATE_ERROR) status is treated as success and reported via
  /// the return value, which is what makes provisioning idempotent — re-running
  /// it against a card that already has the application is not an error.
  ///
  /// Returns true when the application already existed.
  Future<bool> createApplication(int aid, int numKeys) async {
    if (numKeys < 1 || numKeys > 14) {
      throw InvalidParameterException(
        'An application needs between 1 and 14 keys, not $numKeys',
      );
    }
    final plain = Uint8List.fromList([
      aid & 0xFF,
      (aid >> 8) & 0xFF,
      (aid >> 16) & 0xFF,
      0xEF,
      0x80 | (numKeys & 0x0F),
    ]);

    final s = _session;
    final live = s != null && s.isActive;
    final payload = live
        ? Uint8List.fromList([
            ...plain,
            ...s.commandMac(DesfireCommand.createApplication, plain),
          ])
        : plain;

    final response = await _send(DesfireCommand.createApplication, payload);
    if (response.status != DesfireStatus.ok &&
        response.status != DesfireStatus.duplicateError) {
      if (live) s.invalidate();
      throw CardStatusException(
        response.status,
        command: DesfireCommand.createApplication,
      );
    }
    if (live) s.incrementCounter();

    final existed = response.status == DesfireStatus.duplicateError;
    _log(
      existed
          ? 'CreateApplication: AID already present, left untouched'
          : 'CreateApplication: created AID with $numKeys key(s)',
    );
    return existed;
  }

  /// `ChangeKey` (0xC4) in an EV2 session. Port of `df_change_key`.
  ///
  /// The cryptogram depends on whether the key being replaced is the one the
  /// session authenticated with:
  ///
  /// * **Same key** — `newKey || keyVersion`, padded to 32 bytes. The card has
  ///   the old key already, so no proof of possession is needed.
  /// * **Different key** — `(newKey XOR oldKey) || CRC32(newKey) || keyVersion`,
  ///   padded. The XOR means the card can only recover `newKey` if the caller
  ///   really knew `oldKey`, and the CRC is what lets the card check that it
  ///   did. Byte order here follows the C exactly.
  ///
  /// Changing the authenticated key makes the card tear the session down, so
  /// this method clears the local session too. The caller must re-select the
  /// application and re-authenticate before doing anything else — that is why
  /// `df_setup_desfire` has a re-select/re-auth after every key change, and why
  /// `CardGateway.provisionCard` repeats the dance.
  Future<void> changeKey({
    required int keyNo,
    required Uint8List oldKey,
    required Uint8List newKey,
    int keyVersion = 0x01,
  }) async {
    final s = _requireSession;
    if (oldKey.length != 16 || newKey.length != 16) {
      throw const InvalidParameterException('AES keys must be 16 bytes');
    }

    // Port of the `ctx->delay(..., DF_NV_WRITE_DELAY_MS)` the C performs here.
    if (nvWriteDelay > Duration.zero) {
      await Future<void>.delayed(nvWriteDelay);
    }

    final changingAuthKey = keyNo == s.authKeyNo;
    final plain = Uint8List(32);
    if (changingAuthKey) {
      plain.setRange(0, 16, newKey);
      plain[16] = keyVersion & 0xFF;
      plain[17] = 0x80;
    } else {
      for (var i = 0; i < 16; i++) {
        plain[i] = newKey[i] ^ oldKey[i];
      }
      plain.setRange(16, 20, crc32ToBytes(desfireCrc32(newKey)));
      plain[20] = keyVersion & 0xFF;
      plain[21] = 0x80;
    }

    final cryptogram = aesCbcEncrypt(s.sessKeyEnc, s.commandIv, plain);
    final cmdData = Uint8List.fromList([keyNo & 0xFF, ...cryptogram]);
    final mac = s.commandMac(DesfireCommand.changeKey, cmdData);

    final response = await _send(
      DesfireCommand.changeKey,
      Uint8List.fromList([...cmdData, ...mac]),
    );
    if (response.status != DesfireStatus.ok) {
      s.invalidate();
      _session = null;
      throw CardStatusException(
        response.status,
        command: DesfireCommand.changeKey,
      );
    }

    s.incrementCounter();

    if (changingAuthKey) {
      s.invalidate();
      _session = null;
      _log('ChangeKey($keyNo): done, session invalidated by the card');
      return;
    }

    if (response.body.length < 8) {
      throw ResponseTooShortException(
        'ChangeKey returned ${response.body.length} bytes; expected a CMAC.',
      );
    }
    final respMac = Uint8List.sublistView(
      response.body,
      response.body.length - 8,
    );
    if (!s.verifyResponseMac(null, respMac)) {
      throw const CmacMismatchException(
        'ChangeKey response CMAC failed — the key state on the card is '
        'unknown. Re-read the card before writing anything else.',
      );
    }
    _log('ChangeKey($keyNo): done');
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
