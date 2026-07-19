/// Application-level card operations, wiring [AppConfig] to [DesfireCard].
library;

import 'dart:typed_data';

import '../app_config.dart';
import '../desfire/desfire.dart';
import 'nfc_service.dart';

/// Snapshot of a card read.
class CardSnapshot {
  const CardSnapshot({required this.balanceCents, this.uid, this.product});

  /// Balance stored in the value file, in cents.
  final int balanceCents;

  /// Card UID, when Android reported one.
  final Uint8List? uid;

  /// Human-readable product name, when [CardGateway.readBalance] was asked to
  /// identify the card.
  final String? product;

  /// UID rendered as uppercase hex, or null.
  String? get uidHex => uid == null ? null : toHex(uid!);
}

/// Everyday card operations for the top-up app.
class CardGateway {
  CardGateway({NfcService? nfc}) : _nfc = nfc ?? NfcService();

  final NfcService _nfc;

  /// Whether NFC is present and enabled on this device.
  Future<NfcAvailability> checkAvailability() => _nfc.checkAvailability();

  /// Cancels an in-flight scan.
  Future<void> cancel() => _nfc.cancel();

  /// Selects the application, authenticates with the user key, and reads the
  /// value file.
  Future<CardSnapshot> readBalance({
    bool identify = false,
    DesfireLogger? logger,
  }) {
    return _nfc.run<CardSnapshot>(logger: logger, (card, uid) async {
      String? product;
      if (identify) {
        product = (await card.getVersion()).name;
      }
      await card.selectApplication(AppConfig.applicationId);
      await card.authenticateEv2First(
        AppConfig.userKeyNo,
        AppConfig.appUserKey,
      );
      final balance = await card.getValue(AppConfig.valueFileNo);
      return CardSnapshot(balanceCents: balance, uid: uid, product: product);
    });
  }

  /// Credits [amountCents] and commits the transaction.
  ///
  /// The returned [TopUpResult] carries the balance read back from the card
  /// *after* `CommitTransaction`, so it reflects money that is actually on the
  /// card rather than a staged delta.
  Future<TopUpResult> topUp(int amountCents, {DesfireLogger? logger}) {
    return _nfc.run<TopUpResult>(logger: logger, (card, uid) async {
      await card.selectApplication(AppConfig.applicationId);
      await card.authenticateEv2First(
        AppConfig.userKeyNo,
        AppConfig.appUserKey,
      );
      return card.topUp(
        AppConfig.valueFileNo,
        amountCents,
        upperLimit: AppConfig.upperLimitCents,
      );
    });
  }

  /// Debits [amountCents] and commits. Used by the vending side; exposed here
  /// so the same protocol path is exercised from one place.
  Future<TopUpResult> spend(int amountCents, {DesfireLogger? logger}) {
    return _nfc.run<TopUpResult>(logger: logger, (card, uid) async {
      await card.selectApplication(AppConfig.applicationId);
      await card.authenticateEv2First(
        AppConfig.userKeyNo,
        AppConfig.appUserKey,
      );
      return card.spend(
        AppConfig.valueFileNo,
        amountCents,
        lowerLimit: AppConfig.lowerLimitCents,
      );
    });
  }

  /// One-time provisioning: creates the value file using the application
  /// master key. Safe to re-run; an existing file is left alone.
  Future<void> provisionValueFile({DesfireLogger? logger}) {
    return _nfc.run<void>(logger: logger, (card, uid) async {
      await card.selectApplication(AppConfig.applicationId);
      await card.authenticateEv2First(
        AppConfig.masterKeyNo,
        AppConfig.appMasterKey,
      );
      await card.createValueFile(AppConfig.valueFileSettings);
    });
  }
}
