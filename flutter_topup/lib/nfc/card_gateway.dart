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
  ///
  /// Assumes the application already exists and already carries this app's
  /// keys. Use [provisionCard] for a card that has never been touched.
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

  /// Full card provisioning: creates the application if it is not there,
  /// personalises its keys, creates the value file, and reads the balance back
  /// to prove the result works.
  ///
  /// This is a port of steps **2 to 7** of `df_setup_desfire` in the reference
  /// C library.
  ///
  /// ## Step 1 of that function is deliberately not here
  ///
  /// `df_setup_desfire` begins with `df_full_format`, which authenticates at
  /// PICC level and then sends `FormatPICC` (0xFC) — erasing every application
  /// and every file on the card, not just this app's. That is a reasonable
  /// first line in a bench script pointed at a card you know is blank. It is
  /// not reasonable in a top-up app, which gets pointed at whatever card the
  /// user puts on the reader: an office badge, a hotel key, a transit card.
  /// Those are all DESFire and all indistinguishable from a blank card until
  /// you look. So provisioning here is strictly *additive* — it creates one
  /// application alongside whatever else is on the card and touches nothing
  /// else. There is no `FormatPICC` anywhere in this codebase and there must
  /// not be one; `test/provisioning_test.dart` asserts that command byte 0xFC
  /// never reaches the wire.
  ///
  /// The PICC master key is likewise only ever *used*, never changed. Leaving
  /// it at its factory value means a card this app has touched can still be
  /// re-personalised or formatted by any other tool, so a mistaken tap is
  /// recoverable by someone.
  ///
  /// Idempotent throughout: `CreateApplication` treats `0xDE`
  /// (DUPLICATE_ERROR) as success, and each key is only written if the card
  /// still has the default all-zero key in that slot. Running this against a
  /// fully provisioned card does nothing but re-read the balance. Running it
  /// against a card whose application exists but whose value file is missing
  /// creates only the file.
  ///
  /// [onStep] is called as each step begins, for the progress UI. The whole
  /// run is many round-trips and the card has to stay in the field for all of
  /// them, which is why the UI has to say so.
  Future<CardSnapshot> provisionCard({
    DesfireLogger? logger,
    void Function(ProvisioningStep step)? onStep,
  }) {
    return _nfc.run<CardSnapshot>(logger: logger, (card, uid) async {
      final zeros = Uint8List(16);

      /// Runs one step, tagging any failure with the step that produced it.
      Future<T> step<T>(
        ProvisioningStep which,
        Future<T> Function() body,
      ) async {
        onStep?.call(which);
        try {
          return await body();
        } on ProvisioningFailedException {
          rethrow;
        } catch (error) {
          throw ProvisioningFailedException(which, error);
        }
      }

      /// Authenticates with the first key from [candidates] the card accepts,
      /// and reports which one that was. Used to work out how far a previous
      /// provisioning run got before it was interrupted.
      Future<Uint8List?> authWithFirstAccepted(
        int keyNo,
        List<Uint8List> candidates,
      ) async {
        for (final key in candidates) {
          try {
            await card.authenticateEv2First(keyNo, key);
            return key;
          } on AuthenticationFailedException {
            // Wrong key for this slot; try the next candidate. A re-select is
            // needed because a rejected authentication leaves the card with
            // no usable session state for the next attempt.
            await card.selectApplication(AppConfig.applicationId);
          }
        }
        return null;
      }

      final appExists = await step(ProvisioningStep.probe, () async {
        try {
          await card.selectApplication(AppConfig.applicationId);
          return true;
        } on CardNotProvisionedException {
          return false;
        }
      });

      if (!appExists) {
        await step(
          ProvisioningStep.selectPicc,
          () => card.selectApplication(kPiccApplicationId),
        );
        await step(
          ProvisioningStep.authenticatePicc,
          () => card.authenticatePiccFactory(AppConfig.piccMasterKey),
        );
        await step(
          ProvisioningStep.createApplication,
          () => card.createApplication(
            AppConfig.applicationId,
            AppConfig.numApplicationKeys,
          ),
        );
        await step(
          ProvisioningStep.selectApplication,
          () => card.selectApplication(AppConfig.applicationId),
        );
      }

      // Which master key does the application currently hold? A card that has
      // never been personalised still has the all-zero default.
      final masterKey = await step(
        ProvisioningStep.authenticateDefault,
        () async {
          final key = await authWithFirstAccepted(AppConfig.masterKeyNo, [
            zeros,
            AppConfig.appMasterKey,
          ]);
          if (key == null) {
            throw const AuthenticationFailedException(
              'The application on this card uses a master key this app does '
              'not know. It was not created by this app.',
            );
          }
          return key;
        },
      );
      final freshApplication = bytesEqual(masterKey, zeros);

      if (freshApplication) {
        // Steps 4-6 of df_setup_desfire. The re-select / re-authenticate
        // between key changes is load-bearing, not defensive: ChangeKey on the
        // key the session authenticated with makes the card drop the session,
        // so everything after it needs a fresh one.
        if (AppConfig.userKeyNo != AppConfig.masterKeyNo) {
          await step(ProvisioningStep.changeUserKey, () async {
            final current = await authWithFirstAccepted(AppConfig.userKeyNo, [
              zeros,
              AppConfig.appUserKey,
            ]);
            if (current == null) {
              throw const AuthenticationFailedException(
                'The user key slot on this card holds an unknown key.',
              );
            }
            if (!bytesEqual(current, zeros)) return; // already written
            await card.changeKey(
              keyNo: AppConfig.userKeyNo,
              oldKey: zeros,
              newKey: AppConfig.appUserKey,
            );
            // Prove the new key took before moving on.
            await card.selectApplication(AppConfig.applicationId);
            await card.authenticateEv2First(
              AppConfig.userKeyNo,
              AppConfig.appUserKey,
            );
          });
        }

        await step(ProvisioningStep.changeMasterKey, () async {
          await card.selectApplication(AppConfig.applicationId);
          await card.authenticateEv2First(AppConfig.masterKeyNo, zeros);
          await card.changeKey(
            keyNo: AppConfig.masterKeyNo,
            oldKey: zeros,
            newKey: AppConfig.appMasterKey,
          );
          await card.selectApplication(AppConfig.applicationId);
          await card.authenticateEv2First(
            AppConfig.masterKeyNo,
            AppConfig.appMasterKey,
          );
        });
      }

      // Step 7. The C creates a standard data file here; this app stores the
      // balance in a value file instead, so the settings come from AppConfig.
      await step(
        ProvisioningStep.createValueFile,
        () => card.createValueFile(AppConfig.valueFileSettings),
      );

      return step(ProvisioningStep.verify, () async {
        await card.selectApplication(AppConfig.applicationId);
        await card.authenticateEv2First(
          AppConfig.userKeyNo,
          AppConfig.appUserKey,
        );
        final balance = await card.getValue(AppConfig.valueFileNo);
        return CardSnapshot(balanceCents: balance, uid: uid);
      });
    });
  }
}
