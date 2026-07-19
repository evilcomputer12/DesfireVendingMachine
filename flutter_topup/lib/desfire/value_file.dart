/// Encoding helpers for MIFARE DESFire *value files*.
///
/// A value file stores a single 32-bit **signed little-endian** integer along
/// with a lower limit, an upper limit and an optional limited-credit budget.
/// This project treats the stored integer as a euro amount in **cents**, so a
/// balance of 12.50 EUR is the integer 1250.
///
/// The single most important semantic of value files: `Credit`, `Debit` and
/// `LimitedCredit` do **not** change the stored value on their own. They stage
/// a delta inside the card's transaction buffer, and the change only becomes
/// permanent when `CommitTransaction` succeeds. If the field is lost, or
/// `AbortTransaction` is sent, the staged delta is discarded and the balance is
/// exactly what it was before. See [DesfireCard.credit] for how this is
/// enforced in code.
library;

import 'dart:typed_data';

import 'desfire_exceptions.dart';

/// Largest value a DESFire value file can hold.
const int kValueFileMax = 2147483647;

/// Smallest value a DESFire value file can hold.
const int kValueFileMin = -2147483648;

/// Encodes a signed 32-bit value as 4 little-endian bytes.
///
/// Throws [InvalidParameterException] if [value] does not fit in 32 bits.
Uint8List encodeValue(int value) {
  if (value < kValueFileMin || value > kValueFileMax) {
    throw InvalidParameterException(
      'Value $value does not fit in a signed 32-bit value file',
    );
  }
  final out = Uint8List(4);
  ByteData.sublistView(out).setInt32(0, value, Endian.little);
  return out;
}

/// Decodes 4 little-endian bytes as a signed 32-bit value.
///
/// [offset] selects where in [bytes] the value starts, which is handy when
/// decoding a decrypted-and-padded `GetValue` response block.
int decodeValue(Uint8List bytes, [int offset = 0]) {
  if (bytes.length < offset + 4) {
    throw const ResponseTooShortException('Value file payload needs 4 bytes.');
  }
  return ByteData.sublistView(
    bytes,
    offset,
    offset + 4,
  ).getInt32(0, Endian.little);
}

/// Communication mode byte used in file creation and file settings.
class CommMode {
  const CommMode._();

  /// Plain: no MAC, no encryption.
  static const int plain = 0x00;

  /// MACed: cleartext payload with an appended CMAC.
  static const int maced = 0x01;

  /// Full: AES-CBC encrypted payload with an appended CMAC. This is what the
  /// reference C library uses for its data file, and what this app uses for
  /// the value file.
  static const int full = 0x03;
}

/// DESFire 16-bit access-rights word.
///
/// Layout (matching the `accessRights` argument of `df_create_std_data_file`,
/// which is transmitted little-endian):
///
/// ```text
/// bits 15..12  read
/// bits 11..8   write
/// bits  7..4   readWrite
/// bits  3..0   changeAccessRights
/// ```
///
/// A nibble holds a key number 0..13, or [freeAccess] (0xE) / [noAccess] (0xF).
///
/// For a value file the roles map onto the commands like this:
/// * `read` — `GetValue`
/// * `write` — `Debit` and `LimitedCredit`
/// * `readWrite` — `GetValue`, `Credit`, `Debit`, `LimitedCredit`
/// * `changeAccessRights` — `ChangeFileSettings`
class AccessRights {
  const AccessRights({
    required this.read,
    required this.write,
    required this.readWrite,
    required this.changeAccessRights,
  });

  /// Builds access rights where every role is served by the same key.
  const AccessRights.singleKey(int keyNo)
    : read = keyNo,
      write = keyNo,
      readWrite = keyNo,
      changeAccessRights = keyNo;

  /// Decodes a packed 16-bit access-rights word.
  factory AccessRights.fromWord(int word) => AccessRights(
    read: (word >> 12) & 0x0F,
    write: (word >> 8) & 0x0F,
    readWrite: (word >> 4) & 0x0F,
    changeAccessRights: word & 0x0F,
  );

  /// Nibble meaning "any key, no authentication needed".
  static const int freeAccess = 0x0E;

  /// Nibble meaning "never allowed".
  static const int noAccess = 0x0F;

  /// Key number allowed to read (`GetValue`).
  final int read;

  /// Key number allowed to write (`Debit`, `LimitedCredit`).
  final int write;

  /// Key number allowed full read/write (`Credit` included).
  final int readWrite;

  /// Key number allowed to change the file settings.
  final int changeAccessRights;

  /// Packs the rights into the 16-bit word the card expects.
  int toWord() =>
      ((read & 0x0F) << 12) |
      ((write & 0x0F) << 8) |
      ((readWrite & 0x0F) << 4) |
      (changeAccessRights & 0x0F);

  /// The word as it appears on the wire: little-endian, 2 bytes.
  Uint8List toBytes() {
    final word = toWord();
    return Uint8List.fromList([word & 0xFF, (word >> 8) & 0xFF]);
  }

  @override
  String toString() =>
      'AccessRights(0x${toWord().toRadixString(16).padLeft(4, '0').toUpperCase()})';
}

/// Parameters for `CreateValueFile` (0xCC).
///
/// Wire layout of the command data, 17 bytes:
///
/// ```text
/// [0]      fileNo
/// [1]      commSettings
/// [2..3]   accessRights            (little-endian)
/// [4..7]   lowerLimit              (signed, little-endian)
/// [8..11]  upperLimit              (signed, little-endian)
/// [12..15] initialValue            (signed, little-endian)
/// [16]     limitedCreditEnabled    (0x00 or 0x01)
/// ```
class ValueFileSettings {
  const ValueFileSettings({
    required this.fileNo,
    required this.lowerLimit,
    required this.upperLimit,
    required this.initialValue,
    this.commMode = CommMode.full,
    this.accessRights = const AccessRights.singleKey(2),
    this.limitedCreditEnabled = false,
  });

  /// File number inside the selected application.
  final int fileNo;

  /// Communication mode, see [CommMode].
  final int commMode;

  /// Access rights word.
  final AccessRights accessRights;

  /// Lowest balance the card will allow, in cents. Debits that would go below
  /// this are rejected by the card with status `0xBE` (boundary error).
  final int lowerLimit;

  /// Highest balance the card will allow, in cents.
  final int upperLimit;

  /// Balance the file starts with, in cents.
  final int initialValue;

  /// Whether `LimitedCredit` (0x1C) is permitted on this file.
  final bool limitedCreditEnabled;

  /// Serialises the settings into the 17-byte `CreateValueFile` payload.
  Uint8List toBytes() {
    if (lowerLimit > upperLimit) {
      throw const InvalidParameterException(
        'lowerLimit must not exceed upperLimit',
      );
    }
    final out = Uint8List(17);
    out[0] = fileNo & 0xFF;
    out[1] = commMode & 0xFF;
    out.setRange(2, 4, accessRights.toBytes());
    out.setRange(4, 8, encodeValue(lowerLimit));
    out.setRange(8, 12, encodeValue(upperLimit));
    out.setRange(12, 16, encodeValue(initialValue));
    out[16] = limitedCreditEnabled ? 0x01 : 0x00;
    return out;
  }
}

/// Formats a cent amount as a euro string, e.g. `1250` -> `12.50`.
String formatCents(int cents) {
  final negative = cents < 0;
  final abs = cents.abs();
  final major = abs ~/ 100;
  final minor = (abs % 100).toString().padLeft(2, '0');
  return '${negative ? '-' : ''}$major.$minor';
}

/// Parses a user-entered euro amount into cents.
///
/// Accepts `5`, `5.5`, `5,50`, ` 5.50 `. Returns null when the input is not a
/// valid non-negative amount with at most two decimal places.
int? parseEurosToCents(String input) {
  final text = input.trim().replaceAll(',', '.');
  if (text.isEmpty) return null;
  if (!RegExp(r'^\d+(\.\d{1,2})?$').hasMatch(text)) return null;
  final parts = text.split('.');
  final major = int.tryParse(parts[0]);
  if (major == null) return null;
  final minor = parts.length > 1 ? int.parse(parts[1].padRight(2, '0')) : 0;
  final total = major * 100 + minor;
  if (total > kValueFileMax) return null;
  return total;
}
