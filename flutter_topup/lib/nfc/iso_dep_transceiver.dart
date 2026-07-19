/// `nfc_manager` glue: a [Transceiver] backed by Android IsoDep.
///
/// This is the only file in the app that knows NFC exists. Everything in
/// `lib/desfire/` talks to the abstract [Transceiver], which keeps the
/// protocol layer unit testable.
library;

import 'dart:typed_data';

import 'package:nfc_manager/nfc_manager.dart';
import 'package:nfc_manager/nfc_manager_android.dart';

import '../desfire/desfire_card.dart';
import '../desfire/desfire_exceptions.dart';

/// Sends APDUs over an Android IsoDep (ISO 14443-4) connection.
class IsoDepTransceiver implements Transceiver {
  IsoDepTransceiver(this._isoDep);

  /// Builds a transceiver for [tag], or returns null when the tag does not
  /// expose IsoDep (i.e. it is not an ISO 14443-4 card).
  static IsoDepTransceiver? fromTag(NfcTag tag) {
    final isoDep = IsoDepAndroid.from(tag);
    if (isoDep == null) return null;
    return IsoDepTransceiver(isoDep);
  }

  final IsoDepAndroid _isoDep;

  /// The card's UID, when Android exposed one.
  Uint8List? get uid => _isoDep.tag.id;

  /// Raises the IsoDep timeout. DESFire non-volatile writes (and especially
  /// `CommitTransaction`) can take longer than the platform default.
  Future<void> prepare({int timeoutMillis = 3000}) async {
    try {
      await _isoDep.setTimeout(timeoutMillis);
    } on Exception {
      // Not every Android device honours setTimeout; the default is usually
      // workable, so this is not fatal.
    }
  }

  @override
  Future<Uint8List> transceive(Uint8List apdu) async {
    try {
      return await _isoDep.transceive(apdu);
    } on Exception catch (e) {
      throw TransceiveException(
        'Lost contact with the card. Hold it flat against the phone and try '
        'again. ($e)',
      );
    }
  }
}
