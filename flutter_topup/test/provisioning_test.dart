import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_topup/app_config.dart';
import 'package:flutter_topup/desfire/desfire.dart';
import 'package:flutter_topup/nfc/card_gateway.dart';

import 'fake_card.dart';

void main() {
  final zeros = Uint8List(16);
  final masterKey = AppConfig.appMasterKey;
  final userKey = AppConfig.appUserKey;

  FakeDesfireCard blankCard() => FakeDesfireCard.blank(
    applicationId: AppConfig.applicationId,
    fileNo: AppConfig.valueFileNo,
    upperLimit: AppConfig.upperLimitCents,
  );

  FakeDesfireCard provisionedCard({int balance = 0, bool withFile = true}) =>
      FakeDesfireCard(
        applicationId: AppConfig.applicationId,
        keys: {0: masterKey, 1: zeros, 2: userKey},
        fileNo: AppConfig.valueFileNo,
        initialBalance: balance,
        upperLimit: AppConfig.upperLimitCents,
        valueFileExists: withFile,
      );

  (CardGateway, FakeNfcService) gatewayFor(FakeDesfireCard fake) {
    final nfc = FakeNfcService(fake);
    return (CardGateway(nfc: nfc), nfc);
  }

  group('safety: FormatPICC must not exist', () {
    test(
      '0xFC never reaches the wire during a full provisioning run',
      () async {
        final fake = blankCard();
        final (gateway, _) = gatewayFor(fake);
        await gateway.provisionCard();

        expect(
          fake.sentCommands,
          isNot(contains(0xFC)),
          reason:
              'FormatPICC erases every application on the card. Provisioning '
              'must be strictly additive.',
        );
        expect(fake.sentCommands, isNotEmpty);
      },
    );

    test('0xFC never reaches the wire on the everyday paths', () async {
      final fake = provisionedCard(balance: 1000);
      final (gateway, _) = gatewayFor(fake);
      await gateway.readBalance();
      await gateway.topUp(500);
      await gateway.spend(250);
      await gateway.provisionCard();

      expect(fake.sentCommands, isNot(contains(0xFC)));
    });

    test('no source file in lib/ mentions the FormatPICC command byte', () {
      // The wire-level assertions above only cover the paths the tests drive.
      // This one covers every path, including ones nobody has written yet: if
      // somebody ports df_format_picc or df_full_format across from the C,
      // this fails.
      final offenders = <String>[];
      final formatPicc = RegExp(
        r'0xFC\b|\bformatPicc\b|\bformatPICC\b|FormatPICC\s*\(',
        caseSensitive: false,
      );
      for (final entity in Directory('lib').listSync(recursive: true)) {
        if (entity is! File || !entity.path.endsWith('.dart')) continue;
        for (final line in entity.readAsLinesSync()) {
          // Prose explaining why 0xFC is absent is the point, not a violation.
          final code = line.trim();
          if (code.startsWith('//') || code.startsWith('///')) continue;
          if (formatPicc.hasMatch(line)) {
            offenders.add('${entity.path}: $line');
          }
        }
      }
      expect(
        offenders,
        isEmpty,
        reason:
            'FormatPICC (0xFC) erases the whole card. It must never be '
            'implemented, called or exposed by this app.',
      );
    });

    test('the PICC master key is configured but never changed', () async {
      final fake = blankCard();
      final (gateway, _) = gatewayFor(fake);
      await gateway.provisionCard();

      // Every ChangeKey happened inside the application, after the app was
      // selected; the card-level key is untouched.
      expect(fake.piccMasterKey, zeros);
      expect(AppConfig.piccMasterKey, zeros);
    });

    test(
      'provisioning is never automatic: autoProvision defaults to false',
      () {
        expect(AppConfig.autoProvision, isFalse);
      },
    );
  });

  group('provisioning a blank card', () {
    test('creates the application, the keys and the value file', () async {
      final fake = blankCard();
      final (gateway, _) = gatewayFor(fake);

      final snapshot = await gateway.provisionCard();

      expect(snapshot.balanceCents, 0);
      expect(fake.applicationExists, isTrue);
      expect(fake.valueFileExists, isTrue);
      expect(fake.keys[0], masterKey);
      expect(fake.keys[2], userKey);
    });

    test('CreateApplication carries the C\'s exact payload', () async {
      final fake = blankCard();
      final (gateway, _) = gatewayFor(fake);
      await gateway.provisionCard();

      expect(fake.createdApplications, hasLength(1));
      final created = fake.createdApplications.single;
      expect(created.aid, AppConfig.applicationId);
      expect(created.keySettings, 0xEF);
      expect(created.numKeys, 3, reason: 'userKeyNo 2 => userKeyNo + 1 keys');
      expect(created.aesKeys, isTrue, reason: 'bit 7 selects AES app keys');

      // 90 CA 00 00 05 [aid_lo aid_mid aid_hi EF 83] 00 — plain, no CMAC,
      // because legacy PICC authentication installs no EV2 session.
      final apdu = fake.sentApdus.firstWhere(
        (a) => a[1] == DesfireCommand.createApplication,
      );
      expect(apdu, fromHex('90ca000005030201ef8300'));
    });

    test('changes the user key first, then the master key', () async {
      final fake = blankCard();
      final (gateway, _) = gatewayFor(fake);
      await gateway.provisionCard();

      expect(
        fake.changedKeys,
        [AppConfig.userKeyNo, AppConfig.masterKeyNo],
        reason:
            'df_setup_desfire changes the user key while the master key is '
            'still the default, so a failure leaves a recoverable card.',
      );
    });

    test('authenticates at PICC level before creating the application', () {
      final fake = blankCard();
      final (gateway, _) = gatewayFor(fake);
      return gateway.provisionCard().then((_) {
        final commands = fake.sentCommands;
        final auth = commands.indexOf(DesfireCommand.authenticateLegacy);
        final create = commands.indexOf(DesfireCommand.createApplication);
        expect(auth, isNonNegative);
        expect(create, greaterThan(auth));
      });
    });

    test('reports each step through the progress callback', () async {
      final fake = blankCard();
      final (gateway, _) = gatewayFor(fake);
      final steps = <ProvisioningStep>[];
      await gateway.provisionCard(onStep: steps.add);

      expect(steps.first, ProvisioningStep.probe);
      expect(steps.last, ProvisioningStep.verify);
      expect(steps, contains(ProvisioningStep.createApplication));
      expect(steps, contains(ProvisioningStep.changeUserKey));
      expect(steps, contains(ProvisioningStep.changeMasterKey));
      expect(steps, contains(ProvisioningStep.createValueFile));
    });

    test('the provisioned card can then be topped up', () async {
      final fake = blankCard();
      final (gateway, _) = gatewayFor(fake);
      await gateway.provisionCard();

      final result = await gateway.topUp(1500);
      expect(result.newBalance, 1500);
      expect(fake.committedBalance, 1500);
    });
  });

  group('provisioning is idempotent', () {
    test('a fully provisioned card is left alone', () async {
      final fake = provisionedCard(balance: 2500);
      final (gateway, _) = gatewayFor(fake);

      final snapshot = await gateway.provisionCard();

      expect(snapshot.balanceCents, 2500, reason: 'the balance is not reset');
      expect(fake.createdApplications, isEmpty);
      expect(fake.changedKeys, isEmpty);
      expect(fake.keys[0], masterKey);
      expect(fake.keys[2], userKey);
    });

    test('running it twice on a blank card is safe', () async {
      final fake = blankCard();
      final (gateway, _) = gatewayFor(fake);
      await gateway.provisionCard();
      fake.createdApplications.clear();
      fake.changedKeys.clear();

      final snapshot = await gateway.provisionCard();
      expect(snapshot.balanceCents, 0);
      expect(fake.createdApplications, isEmpty);
      expect(fake.changedKeys, isEmpty);
    });

    test('a duplicate CreateApplication (0xDE) counts as success', () async {
      // The application is present but the probe is forced to miss it, so the
      // run goes down the create path and the card answers DUPLICATE_ERROR.
      final fake = provisionedCard();
      fake.forcedStatus[DesfireCommand.selectApplication] = 0xA0;
      final (gateway, _) = gatewayFor(fake);

      await gateway.provisionCard();

      expect(fake.createdApplications, hasLength(1));
      expect(fake.applicationExists, isTrue);
      expect(fake.keys[0], masterKey, reason: 'existing keys are not reset');
    });

    test('an app without its value file gets only the file', () async {
      final fake = provisionedCard(withFile: false);
      final (gateway, _) = gatewayFor(fake);

      final snapshot = await gateway.provisionCard();

      expect(fake.valueFileExists, isTrue);
      expect(snapshot.balanceCents, 0);
      expect(fake.createdApplications, isEmpty);
      expect(fake.changedKeys, isEmpty);
      expect(fake.sentCommands, contains(DesfireCommand.createValueFile));
      expect(
        fake.sentCommands,
        isNot(contains(DesfireCommand.authenticateLegacy)),
        reason: 'no reason to drop to PICC level when the app already exists',
      );
    });
  });

  group('failures name the step that failed', () {
    Future<ProvisioningFailedException> failureFrom(
      FakeDesfireCard fake,
    ) async {
      final (gateway, _) = gatewayFor(fake);
      try {
        await gateway.provisionCard();
        fail('provisionCard should have thrown');
      } on ProvisioningFailedException catch (e) {
        return e;
      }
    }

    test('a refused CreateApplication', () async {
      final fake = blankCard();
      fake.forcedStatus[DesfireCommand.createApplication] = 0x9D;
      final failure = await failureFrom(fake);

      expect(failure.step, ProvisioningStep.createApplication);
      expect(failure.message, contains('Creating the application'));
      expect(fake.applicationExists, isFalse);
    });

    test('a card whose PICC master key is not the factory one', () async {
      // Somebody else's card: it is a perfectly good DESFire, it just does not
      // answer to the key this app knows, whichever handshake is tried.
      final fake = FakeDesfireCard.blank(
        applicationId: AppConfig.applicationId,
        fileNo: AppConfig.valueFileNo,
        piccMasterKey: Uint8List.fromList(List<int>.filled(16, 0x42)),
      );
      final failure = await failureFrom(fake);

      expect(failure.step, ProvisioningStep.authenticatePicc);
      expect(failure.message, contains('card master key'));
      expect(fake.applicationExists, isFalse, reason: 'nothing was written');
      // All four constructions from _auth_picc_factory were tried.
      expect(
        fake.sentCommands.where((c) => c == DesfireCommand.authenticateLegacy),
        hasLength(2),
      );
      expect(fake.sentCommands, contains(DesfireCommand.authenticateIso));
      expect(fake.sentCommands, contains(DesfireCommand.authenticateEv2First));
    });

    test('a refused ChangeKey', () async {
      final fake = blankCard();
      fake.forcedStatus[DesfireCommand.changeKey] = 0x1E;
      final failure = await failureFrom(fake);

      expect(failure.step, ProvisioningStep.changeUserKey);
      expect(failure.message, contains('Writing the user key'));
    });

    test('the card being pulled away mid-run', () async {
      final fake = blankCard();
      final (gateway, _) = gatewayFor(fake);
      // A dead link during the very first exchange.
      final deadGateway = CardGateway(nfc: FakeNfcService(DeadTransceiver()));
      await expectLater(
        deadGateway.provisionCard(),
        throwsA(
          isA<ProvisioningFailedException>()
              .having((e) => e.step, 'step', ProvisioningStep.probe)
              .having((e) => e.cause, 'cause', isA<TransceiveException>()),
        ),
      );
      expect(fake.sentApdus, isEmpty);
      expect(gateway, isNotNull);
    });

    test('every step has a human-readable label', () {
      for (final step in ProvisioningStep.values) {
        expect(step.label, isNotEmpty);
        expect(step.label, isNot(contains('_')));
      }
    });
  });

  group('unprovisioned cards on the everyday paths', () {
    test('readBalance surfaces CardNotProvisionedException', () async {
      final (gateway, _) = gatewayFor(blankCard());
      await expectLater(
        gateway.readBalance(),
        throwsA(
          isA<CardNotProvisionedException>().having(
            (e) => e.applicationId,
            'applicationId',
            AppConfig.applicationId,
          ),
        ),
      );
    });

    test('topUp surfaces CardNotProvisionedException', () async {
      final (gateway, _) = gatewayFor(blankCard());
      await expectLater(
        gateway.topUp(500),
        throwsA(isA<CardNotProvisionedException>()),
      );
    });

    test('spend surfaces CardNotProvisionedException', () async {
      final (gateway, _) = gatewayFor(blankCard());
      await expectLater(
        gateway.spend(500),
        throwsA(isA<CardNotProvisionedException>()),
      );
    });

    test('the message does not read like a fault', () {
      const e = CardNotProvisionedException(
        applicationId: AppConfig.applicationId,
      );
      expect(e.message, contains('not have the top-up application'));
      expect(e.applicationIdHex, '0x010203');
    });
  });

  group('PICC key type fallbacks (mirrors _auth_picc_factory)', () {
    Future<void> expectProvisions(FakeDesfireCard fake) async {
      final (gateway, _) = gatewayFor(fake);
      final snapshot = await gateway.provisionCard();
      expect(snapshot.balanceCents, 0);
      expect(fake.applicationExists, isTrue);
    }

    test('a card whose PICC key is 2K3DES (0x0A)', () async {
      await expectProvisions(
        FakeDesfireCard.blank(
          applicationId: AppConfig.applicationId,
          fileNo: AppConfig.valueFileNo,
          piccKeyType: PiccKeyType.legacy2k3des,
        ),
      );
    });

    test('a card whose PICC key is 3K3DES (0x1A)', () async {
      await expectProvisions(
        FakeDesfireCard.blank(
          applicationId: AppConfig.applicationId,
          fileNo: AppConfig.valueFileNo,
          piccKeyType: PiccKeyType.iso3k3des,
        ),
      );
    });

    test('a card whose PICC key is AES (0x71)', () async {
      await expectProvisions(
        FakeDesfireCard.blank(
          applicationId: AppConfig.applicationId,
          fileNo: AppConfig.valueFileNo,
          piccKeyType: PiccKeyType.aes,
        ),
      );
    });

    test(
      'an AES PICC key leaves a session, so CreateApplication is MACed',
      () async {
        final fake = FakeDesfireCard.blank(
          applicationId: AppConfig.applicationId,
          fileNo: AppConfig.valueFileNo,
          piccKeyType: PiccKeyType.aes,
        );
        final (gateway, _) = gatewayFor(fake);
        await gateway.provisionCard();

        final apdu = fake.sentApdus.firstWhere(
          (a) => a[1] == DesfireCommand.createApplication,
        );
        expect(apdu[4], 13, reason: 'Lc = 5 payload bytes + 8 CMAC bytes');
      },
    );

    test(
      'a legacy auth leaves no session, so CreateApplication is plain',
      () async {
        final fake = blankCard();
        final (gateway, _) = gatewayFor(fake);
        await gateway.provisionCard();

        final apdu = fake.sentApdus.firstWhere(
          (a) => a[1] == DesfireCommand.createApplication,
        );
        expect(apdu[4], 5, reason: 'Lc = 5 payload bytes, no CMAC');
      },
    );
  });

  group('legacy authentication against the simulated card', () {
    test('emits the token the C produces for the factory key', () async {
      final fake = FakeDesfireCard.blank(
        applicationId: AppConfig.applicationId,
        fileNo: AppConfig.valueFileNo,
        legacyRndB: fromHex('a0a1a2a3a4a5a6a7'),
      );
      final card = DesfireCard(
        fake,
        randomSource: FixedRandomSource(fromHex('0102030405060708')),
        nvWriteDelay: Duration.zero,
      );
      await card.selectApplication(kPiccApplicationId);
      await card.authenticateLegacy(0, Uint8List(16));

      // Step 1 challenge and step 2 token, both pinned to the C generator.
      expect(fake.sentApdus[1], fromHex('900a0000010000'));
      expect(
        fake.sentApdus[2],
        fromHex('90af000010d1bbc36c61bb2f50a0c7f19fec28d49300'),
      );
    });

    test('installs no session — legacy auth has no TI or counter', () async {
      final fake = blankCard();
      final card = DesfireCard(fake, nvWriteDelay: Duration.zero);
      await card.selectApplication(kPiccApplicationId);
      await card.authenticateLegacy(0, Uint8List(16));

      expect(card.isAuthenticated, isFalse);
      expect(card.session, isNull);
      expect(fake.isPiccAuthenticated, isTrue);
    });

    test('a card expecting the D40 direction rejects the ISO token', () async {
      final key = fromHex('00112233445566778899aabbccddeeff');
      final fake = FakeDesfireCard.blank(
        applicationId: AppConfig.applicationId,
        fileNo: AppConfig.valueFileNo,
        piccMasterKey: key,
      );
      final card = DesfireCard(fake, nvWriteDelay: Duration.zero);
      await card.selectApplication(kPiccApplicationId);

      await expectLater(
        card.authenticateLegacy(0, key, cbcSendDecrypt: false),
        throwsA(isA<AuthenticationFailedException>()),
      );

      await card.selectApplication(kPiccApplicationId);
      await card.authenticateLegacy(0, key);
      expect(fake.isPiccAuthenticated, isTrue);
    });

    test('a card expecting the ISO direction rejects the D40 token', () async {
      final key = fromHex('00112233445566778899aabbccddeeff');
      final fake = FakeDesfireCard.blank(
        applicationId: AppConfig.applicationId,
        fileNo: AppConfig.valueFileNo,
        piccMasterKey: key,
        legacyAuthMode: LegacyAuthMode.cbcEncrypt,
      );
      final card = DesfireCard(fake, nvWriteDelay: Duration.zero);
      await card.selectApplication(kPiccApplicationId);

      await expectLater(
        card.authenticateLegacy(0, key),
        throwsA(isA<AuthenticationFailedException>()),
      );

      await card.selectApplication(kPiccApplicationId);
      await card.authenticateLegacy(0, key, cbcSendDecrypt: false);
      expect(fake.isPiccAuthenticated, isTrue);
    });

    test('the wrong key is refused', () async {
      final fake = blankCard();
      final card = DesfireCard(fake, nvWriteDelay: Duration.zero);
      await card.selectApplication(kPiccApplicationId);
      await expectLater(
        card.authenticateLegacy(
          0,
          Uint8List.fromList(List<int>.filled(16, 0x42)),
        ),
        throwsA(isA<AuthenticationFailedException>()),
      );
    });
  });

  group('ChangeKey cryptogram', () {
    test(
      'matches the C plaintext when changing the authenticated key',
      () async {
        final fake = FakeDesfireCard(
          applicationId: AppConfig.applicationId,
          keys: {0: zeros, 1: zeros, 2: zeros},
          fileNo: AppConfig.valueFileNo,
        );
        final card = DesfireCard(fake, nvWriteDelay: Duration.zero);
        await card.selectApplication(AppConfig.applicationId);
        final session = await card.authenticateEv2First(2, zeros);
        final iv = session.commandIv;

        await card.changeKey(keyNo: 2, oldKey: zeros, newKey: userKey);

        final apdu = fake.sentApdus.last;
        expect(apdu[1], DesfireCommand.changeKey);
        expect(apdu[4], 41, reason: 'Lc = 1 keyNo + 32 cryptogram + 8 CMAC');
        expect(apdu[5], 2);

        final cryptogram = Uint8List.sublistView(apdu, 6, 38);
        final plain = aesCbcDecrypt(session.sessKeyEnc, iv, cryptogram);
        // df_change_key, changing_auth branch: newKey || keyVersion || 0x80 pad.
        expect(
          plain,
          fromHex(
            '2222222222222222222222222222222201800000000000000000000000000000',
          ),
        );
        expect(fake.keys[2], userKey);
        expect(card.isAuthenticated, isFalse, reason: 'card drops the session');
      },
    );

    test('matches the C plaintext when changing a different key', () async {
      final fake = FakeDesfireCard(
        applicationId: AppConfig.applicationId,
        keys: {0: zeros, 1: zeros, 2: zeros},
        fileNo: AppConfig.valueFileNo,
      );
      final card = DesfireCard(fake, nvWriteDelay: Duration.zero);
      await card.selectApplication(AppConfig.applicationId);
      final session = await card.authenticateEv2First(0, zeros);
      final iv = session.commandIv;

      await card.changeKey(keyNo: 2, oldKey: zeros, newKey: userKey);

      final apdu = fake.sentApdus.last;
      final cryptogram = Uint8List.sublistView(apdu, 6, 38);
      final plain = aesCbcDecrypt(session.sessKeyEnc, iv, cryptogram);
      // df_change_key, other-key branch:
      // (newKey XOR oldKey) || CRC32(newKey) LE || keyVersion || 0x80 pad.
      expect(
        plain,
        fromHex(
          '22222222222222222222222222222222f14ad1c0018000000000000000000000',
        ),
      );
      expect(fake.keys[2], userKey);
      expect(
        card.isAuthenticated,
        isTrue,
        reason: 'changing another key leaves the session alive',
      );
    });

    test('a rejected ChangeKey invalidates the session', () async {
      final fake = FakeDesfireCard(
        applicationId: AppConfig.applicationId,
        keys: {0: zeros, 2: zeros},
        fileNo: AppConfig.valueFileNo,
      );
      final card = DesfireCard(fake, nvWriteDelay: Duration.zero);
      await card.selectApplication(AppConfig.applicationId);
      await card.authenticateEv2First(0, zeros);
      fake.forcedStatus[DesfireCommand.changeKey] = 0x1E;

      await expectLater(
        card.changeKey(keyNo: 0, oldKey: zeros, newKey: masterKey),
        throwsA(isA<CardStatusException>()),
      );
      expect(card.isAuthenticated, isFalse);
    });
  });

  group('CreateApplication', () {
    test('rejects an impossible key count before touching the card', () async {
      final fake = blankCard();
      final card = DesfireCard(fake, nvWriteDelay: Duration.zero);
      await expectLater(
        card.createApplication(AppConfig.applicationId, 0),
        throwsA(isA<InvalidParameterException>()),
      );
      await expectLater(
        card.createApplication(AppConfig.applicationId, 15),
        throwsA(isA<InvalidParameterException>()),
      );
      expect(fake.sentApdus, isEmpty);
    });

    test('reports whether the application already existed', () async {
      final fake = blankCard();
      final card = DesfireCard(fake, nvWriteDelay: Duration.zero);
      await card.selectApplication(kPiccApplicationId);
      await card.authenticatePiccFactory(AppConfig.piccMasterKey);

      expect(await card.createApplication(AppConfig.applicationId, 3), isFalse);
      expect(await card.createApplication(AppConfig.applicationId, 3), isTrue);
    });
  });
}
