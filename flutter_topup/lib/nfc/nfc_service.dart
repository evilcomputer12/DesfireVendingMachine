/// Runs a single DESFire operation against the next card that gets tapped.
///
/// The service owns the `nfc_manager` polling session so the UI never has to.
/// A caller supplies a [CardOperation] — an async function that receives an
/// authenticated-capable [DesfireCard] and returns a result — and gets back a
/// future that completes when the card has been tapped and the operation has
/// run to completion.
library;

import 'dart:async';
import 'dart:typed_data';

import 'package:nfc_manager/nfc_manager.dart';

import '../desfire/desfire_card.dart';
import '../desfire/desfire_exceptions.dart';
import 'iso_dep_transceiver.dart';

export 'package:nfc_manager/nfc_manager.dart' show NfcAvailability;

/// Work to perform once a card is in the field.
typedef CardOperation<T> = Future<T> Function(DesfireCard card, Uint8List? uid);

/// Wraps `nfc_manager` polling into a one-shot, awaitable card operation.
class NfcService {
  /// Whether the device has NFC hardware and it is switched on.
  Future<NfcAvailability> checkAvailability() =>
      NfcManager.instance.checkAvailability();

  /// Waits for a card, runs [operation] against it, then stops the session.
  ///
  /// The returned future completes with the operation's value, or with a
  /// [DesfireException] describing what went wrong. Cancel by calling
  /// [cancel]; the future then completes with a [NoCardException].
  Future<T> run<T>(
    CardOperation<T> operation, {
    DesfireLogger? logger,
    Duration timeout = const Duration(seconds: 60),
  }) async {
    final completer = Completer<T>();

    Future<void> finish(FutureOr<void> Function() body) async {
      try {
        await body();
      } finally {
        unawaited(NfcManager.instance.stopSession().catchError((_) {}));
      }
    }

    await NfcManager.instance.startSession(
      pollingOptions: {NfcPollingOption.iso14443},
      onDiscovered: (NfcTag tag) async {
        if (completer.isCompleted) return;
        await finish(() async {
          final transceiver = IsoDepTransceiver.fromTag(tag);
          if (transceiver == null) {
            if (!completer.isCompleted) {
              completer.completeError(
                const TransceiveException(
                  'That tag is not an ISO 14443-4 card. Use a DESFire card.',
                ),
              );
            }
            return;
          }
          await transceiver.prepare();
          final card = DesfireCard(transceiver, logger: logger);
          try {
            final value = await operation(card, transceiver.uid);
            if (!completer.isCompleted) completer.complete(value);
          } catch (error, stack) {
            if (!completer.isCompleted) completer.completeError(error, stack);
          }
        });
      },
    );

    _active = completer;
    try {
      return await completer.future.timeout(
        timeout,
        onTimeout: () =>
            throw const NoCardException('No card was presented in time.'),
      );
    } finally {
      _active = null;
      unawaited(NfcManager.instance.stopSession().catchError((_) {}));
    }
  }

  Completer<Object?>? _active;

  /// Stops an in-flight [run], completing it with a [NoCardException].
  Future<void> cancel() async {
    final active = _active;
    if (active != null && !active.isCompleted) {
      active.completeError(const NoCardException('Scan cancelled.'));
    }
    await NfcManager.instance.stopSession().catchError((_) {});
  }
}
