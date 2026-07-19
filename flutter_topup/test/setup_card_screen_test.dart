import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_topup/app_config.dart';
import 'package:flutter_topup/desfire/desfire.dart';
import 'package:flutter_topup/nfc/card_gateway.dart';
import 'package:flutter_topup/ui/setup_card_screen.dart';
import 'package:flutter_topup/ui/theme.dart';

import 'fake_card.dart';

/// Widget tests for the provisioning UI.
///
/// The point of most of these is the safety property, not the pixels: a card
/// must not be written to unless the user asked for it twice, and a failure
/// must name the step it failed at rather than showing a status byte.
void main() {
  FakeDesfireCard blankCard() => FakeDesfireCard.blank(
    applicationId: AppConfig.applicationId,
    fileNo: AppConfig.valueFileNo,
    upperLimit: AppConfig.upperLimitCents,
  );

  /// The setup screen is a tall scrolling explanation, and the default
  /// 800x600 test surface leaves the action button off-screen where a lazy
  /// ListView never builds it. Use a phone-shaped viewport instead.
  void useTallViewport(WidgetTester tester) {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
  }

  Future<void> pumpSetup(WidgetTester tester, FakeDesfireCard fake) async {
    useTallViewport(tester);
    await tester.pumpWidget(
      MaterialApp(
        theme: buildKioskTheme(),
        home: SetupCardScreen(gateway: CardGateway(nfc: FakeNfcService(fake))),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('opens on an explanation, having written nothing', (
    tester,
  ) async {
    final fake = blankCard();
    await pumpSetup(tester, fake);

    expect(find.text('This card is not set up'), findsOneWidget);
    expect(find.text('Set up this card'), findsOneWidget);
    expect(fake.sentApdus, isEmpty, reason: 'the card has not been touched');
    expect(fake.applicationExists, isFalse);
  });

  testWidgets('names what will happen before doing it', (tester) async {
    final fake = blankCard();
    await pumpSetup(tester, fake);
    await tester.tap(find.text('Set up this card'));
    await tester.pumpAndSettle();

    expect(find.text('Set up this card?'), findsOneWidget);
    final inDialog = find.descendant(
      of: find.byType(AlertDialog),
      matching: find.textContaining('will be created on the card'),
    );
    expect(
      inDialog,
      findsOneWidget,
      reason: 'the dialog has to say an application gets created',
    );
    expect(
      find.descendant(
        of: find.byType(AlertDialog),
        matching: find.textContaining('cannot undo it'),
      ),
      findsOneWidget,
      reason: 'the dialog has to say it is irreversible',
    );
    expect(fake.sentApdus, isEmpty);
  });

  testWidgets('cancelling the confirmation writes nothing', (tester) async {
    final fake = blankCard();
    await pumpSetup(tester, fake);
    await tester.tap(find.text('Set up this card'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();

    expect(fake.sentApdus, isEmpty);
    expect(fake.applicationExists, isFalse);
    expect(find.text('This card is not set up'), findsOneWidget);
  });

  testWidgets('confirming provisions the card and shows the balance', (
    tester,
  ) async {
    final fake = blankCard();
    await pumpSetup(tester, fake);
    await tester.tap(find.text('Set up this card'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Yes, set it up'));
    await tester.pumpAndSettle();

    expect(find.text('Card is ready'), findsOneWidget);
    expect(find.text('€0.00'), findsOneWidget);
    expect(fake.applicationExists, isTrue);
    expect(fake.valueFileExists, isTrue);
    expect(fake.sentCommands, isNot(contains(0xFC)));
  });

  testWidgets('a failure names the step that failed', (tester) async {
    final fake = blankCard();
    fake.forcedStatus[DesfireCommand.createApplication] = 0x9D;
    await pumpSetup(tester, fake);
    await tester.tap(find.text('Set up this card'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Yes, set it up'));
    await tester.pumpAndSettle();

    expect(
      find.text('${ProvisioningStep.createApplication.label} failed'),
      findsOneWidget,
    );
    expect(find.text('Try again'), findsOneWidget);
    expect(fake.applicationExists, isFalse);
  });

  testWidgets('retrying after a failure completes the setup', (tester) async {
    final fake = blankCard();
    fake.forcedStatus[DesfireCommand.changeKey] = 0x1E;
    await pumpSetup(tester, fake);
    await tester.tap(find.text('Set up this card'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Yes, set it up'));
    await tester.pumpAndSettle();

    expect(
      find.text('${ProvisioningStep.changeUserKey.label} failed'),
      findsOneWidget,
    );

    // The forced status was one-shot, so the retry gets through. No second
    // confirmation: the user already agreed to set this card up.
    await tester.tap(find.text('Try again'));
    await tester.pumpAndSettle();

    expect(find.text('Card is ready'), findsOneWidget);
    expect(fake.keys[0], AppConfig.appMasterKey);
    expect(fake.keys[2], AppConfig.appUserKey);
  });

  testWidgets('"Not now" leaves without touching the card', (tester) async {
    final fake = blankCard();
    useTallViewport(tester);
    await tester.pumpWidget(
      MaterialApp(
        theme: buildKioskTheme(),
        home: Builder(
          builder: (context) => Scaffold(
            body: ElevatedButton(
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute<CardSnapshot>(
                  builder: (_) => SetupCardScreen(
                    gateway: CardGateway(nfc: FakeNfcService(fake)),
                  ),
                ),
              ),
              child: const Text('open'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Not now'));
    await tester.pumpAndSettle();

    expect(find.text('open'), findsOneWidget);
    expect(fake.sentApdus, isEmpty);
  });

  testWidgets('an already-provisioned card is reported, not re-keyed', (
    tester,
  ) async {
    final fake = FakeDesfireCard(
      applicationId: AppConfig.applicationId,
      keys: {
        0: AppConfig.appMasterKey,
        1: Uint8List(16),
        2: AppConfig.appUserKey,
      },
      fileNo: AppConfig.valueFileNo,
      initialBalance: 1250,
      upperLimit: AppConfig.upperLimitCents,
    );
    await pumpSetup(tester, fake);
    await tester.tap(find.text('Set up this card'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Yes, set it up'));
    await tester.pumpAndSettle();

    expect(find.text('Card is ready'), findsOneWidget);
    expect(find.text('€12.50'), findsOneWidget);
    expect(fake.changedKeys, isEmpty);
    expect(fake.createdApplications, isEmpty);
  });
}
