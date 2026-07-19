/// Bench-test configuration: application id, file numbers and **hardcoded key
/// material**.
///
/// ============================ READ THIS =============================
/// The AES keys below are the fixed development keys from
/// `nucleof411re/Core/Src/desfiire.c`. They are compiled into the APK in
/// plaintext. Anyone who can pull the APK off a phone can read them, and with
/// them can mint unlimited balance on every card in the fleet, because the
/// same key is on every card.
///
/// This is acceptable for a bench rig with test cards. It is not acceptable
/// for anything that represents real money. See README.md for what a real
/// deployment does instead (key diversification + a backend HSM/SAM that
/// performs the credit, with the phone acting only as an NFC pipe).
/// ====================================================================
library;

import 'dart:typed_data';

import 'desfire/value_file.dart';

/// DESFire configuration for the vending-machine application.
class AppConfig {
  const AppConfig._();

  /// Application ID, from `DESFIRE_APP_ID` in `desfiire.c`.
  static const int applicationId = 0x010203;

  /// Application master key number. Used for provisioning (`CreateValueFile`).
  static const int masterKeyNo = 0;

  /// Application user key number, from `app->df.userKeyNo` in `desfiire.c`.
  /// Used for everyday balance operations.
  static const int userKeyNo = 2;

  /// Value file number holding the balance.
  ///
  /// File 0x02 is already used by the reference firmware for its standard data
  /// file, so the value file gets its own number.
  static const int valueFileNo = 0x01;

  /// Lowest balance the card will hold, in cents.
  static const int lowerLimitCents = 0;

  /// Highest balance the card will hold, in cents (1000.00 EUR).
  static const int upperLimitCents = 100000;

  /// Number of keys the application is created with.
  ///
  /// Same expression as `df_setup_desfire`:
  /// `numKeys = (userKeyNo == 0) ? 1 : userKeyNo + 1`. With [userKeyNo] of 2
  /// that is 3 keys (0, 1, 2) — key 1 is unused but has to exist for key 2 to
  /// be addressable.
  static int get numApplicationKeys => userKeyNo == 0 ? 1 : userKeyNo + 1;

  /// PICC (card-level) master key, `DESFIRE_PICC_KEY` in `desfiire.c`: the
  /// factory default of 16 zero bytes.
  ///
  /// Used **read-only**, to authenticate at card level so that
  /// `CreateApplication` is permitted. Provisioning never calls `ChangeKey`
  /// against key 0 at PICC level: leaving the card master key at its factory
  /// value means this app can never lock anybody out of their own card, and
  /// means a card it touched can still be re-personalised by any other tool.
  static Uint8List get piccMasterKey => Uint8List(16);

  /// Whether tapping an unprovisioned card may skip the confirmation dialog.
  ///
  /// Provisioning is **never** silent or automatic regardless of this flag: the
  /// user always has to press "Set up this card". What this flag removes is
  /// only the extra "are you sure" step, for bench sessions where somebody is
  /// personalising a stack of known-blank cards.
  ///
  /// Default `false`, and it must stay `false` in anything a member of the
  /// public taps, because the card on the reader may well be an office badge
  /// or a transit card that merely happens to be DESFire. Writing a new
  /// application onto one of those without being asked is not recoverable by
  /// this app.
  static const bool autoProvision = false;

  /// BENCH KEY — application master key, `DESFIRE_APP_MASTER_KEY` (16 x 0x11).
  static Uint8List get appMasterKey =>
      Uint8List.fromList(List<int>.filled(16, 0x11));

  /// BENCH KEY — application user key, `DESFIRE_APP_USER_KEY` (16 x 0x22).
  static Uint8List get appUserKey =>
      Uint8List.fromList(List<int>.filled(16, 0x22));

  /// Settings used when provisioning a fresh value file.
  static ValueFileSettings get valueFileSettings => const ValueFileSettings(
    fileNo: valueFileNo,
    commMode: CommMode.full,
    accessRights: AccessRights.singleKey(userKeyNo),
    lowerLimit: lowerLimitCents,
    upperLimit: upperLimitCents,
    initialValue: 0,
    limitedCreditEnabled: false,
  );

  /// Preset top-up amounts offered in the UI, in cents.
  static const List<int> presetAmountsCents = [500, 1000, 2000];
}
