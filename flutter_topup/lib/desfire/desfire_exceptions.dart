/// Typed exceptions mirroring the `DFStatus` enum from `desfire_cmd.h`.
library;

/// Base class for every DESFire-layer failure.
sealed class DesfireException implements Exception {
  const DesfireException(this.message);

  /// Human-readable description, safe to surface in the UI.
  final String message;

  @override
  String toString() => '$runtimeType: $message';
}

/// `DF_ERR_NO_CARD` — no card in the RF field.
class NoCardException extends DesfireException {
  const NoCardException([super.message = 'No card in the field.']);
}

/// `DF_ERR_TRANSCEIVE` — RF or link error; the card stopped responding.
class TransceiveException extends DesfireException {
  const TransceiveException([
    super.message =
        'Radio link error — the card moved away. Hold it still and retry.',
  ]);
}

/// `DF_ERR_AUTH` — authentication failed (wrong key, wrong key number, or the
/// card rejected the `RndA` echo).
class AuthenticationFailedException extends DesfireException {
  const AuthenticationFailedException([
    super.message = 'Authentication failed — wrong key or key number.',
  ]);
}

/// `DF_ERR_LEN` — the card returned fewer bytes than the command requires.
class ResponseTooShortException extends DesfireException {
  const ResponseTooShortException([
    super.message = 'Card response was too short.',
  ]);
}

/// `DF_ERR_CMAC` — the CMAC on a response did not verify. Either the session
/// keys diverged or the exchange was tampered with. Never treat the payload of
/// such a response as trustworthy.
class CmacMismatchException extends DesfireException {
  const CmacMismatchException([
    super.message =
        'Response CMAC did not verify — session desynchronised or tampered.',
  ]);
}

/// `DF_ERR_PARAM` — a caller-side argument was invalid.
class InvalidParameterException extends DesfireException {
  const InvalidParameterException(super.message);
}

/// Raised before touching the card when a debit would drive the value file
/// below its configured lower limit.
class InsufficientFundsException extends DesfireException {
  const InsufficientFundsException({
    required this.balance,
    required this.requested,
    String? message,
  }) : super(message ?? 'Insufficient funds on card.');

  /// Balance currently stored in the value file, in minor units (cents).
  final int balance;

  /// Amount the caller tried to debit, in minor units (cents).
  final int requested;

  @override
  String toString() =>
      'InsufficientFundsException: $message (balance=$balance, '
      'requested=$requested)';
}

/// Raised when a credit would push the value file past its upper limit, or
/// when the card reports a boundary error for the same reason.
class LimitExceededException extends DesfireException {
  const LimitExceededException({
    required this.balance,
    required this.requested,
    required this.upperLimit,
    String? message,
  }) : super(message ?? 'Top-up would exceed the card limit.');

  /// Balance currently stored in the value file, in minor units (cents).
  final int balance;

  /// Amount the caller tried to credit, in minor units (cents).
  final int requested;

  /// Configured upper limit of the value file, in minor units (cents).
  final int upperLimit;

  @override
  String toString() =>
      'LimitExceededException: $message (balance=$balance, '
      'requested=$requested, upperLimit=$upperLimit)';
}

/// `DF_ERR_CMD` — the card returned a non-OK DESFire status byte (SW2).
class CardStatusException extends DesfireException {
  CardStatusException(this.status, {this.command})
    : super(describeDesfireStatus(status));

  /// The DESFire status byte (SW2 of the ISO-wrapped response).
  final int status;

  /// The DESFire command byte that produced the status, when known.
  final int? command;

  /// Uppercase hex rendering of [status], e.g. `0x9D`.
  String get statusHex =>
      '0x${status.toRadixString(16).padLeft(2, '0').toUpperCase()}';

  @override
  String toString() {
    final cmd = command == null
        ? ''
        : ' during command 0x'
              '${command!.toRadixString(16).padLeft(2, '0').toUpperCase()}';
    return 'CardStatusException: $message ($statusHex)$cmd';
  }
}

/// Raised when the ISO 7816 framing is wrong — SW1 was not `0x91`.
class FramingException extends DesfireException {
  FramingException(this.sw1)
    : super(
        'Bad APDU framing: SW1=0x'
        '${sw1.toRadixString(16).padLeft(2, '0').toUpperCase()} '
        '(expected 0x91). Is this a DESFire card?',
      );

  /// The SW1 byte the card returned.
  final int sw1;
}

/// Decodes a DESFire status byte (SW2) into a human-readable string.
///
/// Superset of `df_sw_describe` in `desfire_cmd.c`, extended with the status
/// codes the value-file and transaction commands can return.
String describeDesfireStatus(int sw) {
  switch (sw) {
    case 0x00:
      return 'OK';
    case 0x0C:
      return 'No changes made';
    case 0x0E:
      return 'Insufficient non-volatile memory on card';
    case 0x1C:
      return 'Command not allowed in this state';
    case 0x1E:
      return 'Integrity error (CRC or padding wrong)';
    case 0x40:
      return 'No such key';
    case 0x6D:
      return 'INS not supported';
    case 0x6E:
      return 'CLA not supported';
    case 0x7E:
      return 'Length error in command';
    case 0x9D:
      return 'Permission denied — the authenticated key may not do this';
    case 0x9E:
      return 'Invalid file settings or parameter value';
    case 0xA0:
      return 'Requested application not found';
    case 0xA1:
      return 'Unrecoverable error within the application';
    case 0xAE:
      return 'Authentication error';
    case 0xAF:
      return 'Additional frame expected';
    case 0xBE:
      return 'Boundary error — value would leave the configured limits';
    case 0xC1:
      return 'Unrecoverable error within the card';
    case 0xCA:
      return 'Previous command was not fully completed';
    case 0xCD:
      return 'Card was disabled or removed';
    case 0xCE:
      return 'Too many applications or files';
    case 0xDE:
      return 'Duplicate — application or file already exists';
    case 0xEE:
      return 'Could not complete non-volatile write';
    case 0xF0:
      return 'Requested file not found';
    case 0xF1:
      return 'Unrecoverable error within the file';
    default:
      return 'Unknown card status';
  }
}
