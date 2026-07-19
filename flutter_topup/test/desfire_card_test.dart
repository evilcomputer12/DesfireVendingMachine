import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_topup/desfire/desfire.dart';

import 'fake_card.dart';

void main() {
  final userKey = Uint8List.fromList(List<int>.filled(16, 0x22));
  final rndA = Uint8List.fromList(List<int>.generate(16, (i) => 0x10 + i));
  const aid = 0x010203;
  const fileNo = 0x01;

  FakeDesfireCard newFakeCard({int balance = 0, int upperLimit = 100000}) {
    return FakeDesfireCard(
      applicationId: aid,
      keys: {2: userKey},
      fileNo: fileNo,
      initialBalance: balance,
      upperLimit: upperLimit,
    );
  }

  DesfireCard clientFor(FakeDesfireCard fake) =>
      DesfireCard(fake, randomSource: FixedRandomSource(rndA));

  group('buildApdu', () {
    test('wraps a parameterless command as 90 CMD 00 00 00', () {
      expect(buildApdu(0xC7), fromHex('90c7000000'));
    });

    test('wraps a command with data as 90 CMD 00 00 Lc DATA 00', () {
      expect(buildApdu(0x5A, fromHex('030201')), fromHex('905a00000303020100'));
    });

    test('SelectApplication carries the AID little-endian', () {
      final data = Uint8List.fromList([
        aid & 0xFF,
        (aid >> 8) & 0xFF,
        (aid >> 16) & 0xFF,
      ]);
      expect(data, fromHex('030201'));
      expect(
        buildApdu(DesfireCommand.selectApplication, data),
        fromHex('905a00000303020100'),
      );
    });

    test('rejects a payload longer than a short APDU allows', () {
      expect(
        () => buildApdu(0x3D, Uint8List(256)),
        throwsA(isA<InvalidParameterException>()),
      );
    });
  });

  group('parseResponse', () {
    test('splits body from SW1/SW2', () {
      final parsed = parseResponse(fromHex('a1b2c39100'));
      expect(parsed.body, fromHex('a1b2c3'));
      expect(parsed.status, 0x00);
    });

    test('reads a bare status word', () {
      final parsed = parseResponse(fromHex('91af'));
      expect(parsed.body, isEmpty);
      expect(parsed.status, 0xAF);
    });

    test('rejects a response shorter than SW1+SW2', () {
      expect(
        () => parseResponse(fromHex('91')),
        throwsA(isA<ResponseTooShortException>()),
      );
    });

    test('rejects non-DESFire framing', () {
      expect(
        () => parseResponse(fromHex('9000')),
        throwsA(isA<FramingException>()),
      );
    });
  });

  group('command byte constants', () {
    test('match the DESFire specification', () {
      expect(DesfireCommand.authenticateEv2First, 0x71);
      expect(DesfireCommand.selectApplication, 0x5A);
      expect(DesfireCommand.createValueFile, 0xCC);
      expect(DesfireCommand.getValue, 0x6C);
      expect(DesfireCommand.credit, 0x0C);
      expect(DesfireCommand.debit, 0xDC);
      expect(DesfireCommand.limitedCredit, 0x1C);
      expect(DesfireCommand.commitTransaction, 0xC7);
      expect(DesfireCommand.abortTransaction, 0xA7);
    });
  });

  group('selectApplication', () {
    test('selects a known AID', () async {
      final fake = newFakeCard();
      final card = clientFor(fake);
      await card.selectApplication(aid);
      expect(fake.sentApdus.single, fromHex('905a00000303020100'));
    });

    test('reports the card status for an unknown AID', () async {
      final card = clientFor(newFakeCard());
      await expectLater(
        card.selectApplication(0x999999),
        throwsA(
          isA<CardStatusException>().having((e) => e.status, 'status', 0xA0),
        ),
      );
    });

    test('drops any existing session', () async {
      final fake = newFakeCard();
      final card = clientFor(fake);
      await card.selectApplication(aid);
      await card.authenticateEv2First(2, userKey);
      expect(card.isAuthenticated, isTrue);
      await card.selectApplication(aid);
      expect(card.isAuthenticated, isFalse);
    });
  });

  group('authenticateEv2First', () {
    test('completes the handshake and installs a session', () async {
      final fake = newFakeCard();
      final card = clientFor(fake);
      await card.selectApplication(aid);
      final session = await card.authenticateEv2First(2, userKey);

      expect(card.isAuthenticated, isTrue);
      expect(session.authKeyNo, 2);
      expect(session.cmdCounter, 0);
      expect(session.ti, fromHex('deadbeef'));
      // The keys the client derived must match the C-generated vectors for
      // this key / RndA / RndB triple.
      expect(session.sessKeyEnc, fromHex('1130b90c1aac6ef7528d9088d24d67d3'));
      expect(session.sessKeyMac, fromHex('569031dca11d038e1b12e50092c9e030'));
    });

    test('sends keyNo and the LenCap byte in step 1', () async {
      final fake = newFakeCard();
      final card = clientFor(fake);
      await card.selectApplication(aid);
      await card.authenticateEv2First(2, userKey);
      expect(fake.sentApdus[1], fromHex('9071000002020000'));
    });

    test('fails on the wrong key', () async {
      final fake = newFakeCard();
      final card = clientFor(fake);
      await card.selectApplication(aid);
      await expectLater(
        card.authenticateEv2First(2, Uint8List(16)),
        throwsA(isA<AuthenticationFailedException>()),
      );
      expect(card.isAuthenticated, isFalse);
    });

    test('fails on an unknown key number', () async {
      final card = clientFor(newFakeCard());
      await card.selectApplication(aid);
      await expectLater(
        card.authenticateEv2First(7, userKey),
        throwsA(isA<AuthenticationFailedException>()),
      );
    });

    test('rejects a key that is not 16 bytes', () async {
      final card = clientFor(newFakeCard());
      await expectLater(
        card.authenticateEv2First(2, Uint8List(8)),
        throwsA(isA<InvalidParameterException>()),
      );
    });
  });

  group('getValue', () {
    Future<DesfireCard> authenticated(FakeDesfireCard fake) async {
      final card = clientFor(fake);
      await card.selectApplication(aid);
      await card.authenticateEv2First(2, userKey);
      return card;
    }

    test('reads the committed balance', () async {
      final fake = newFakeCard(balance: 1250);
      final card = await authenticated(fake);
      expect(await card.getValue(fileNo), 1250);
    });

    test('reads zero', () async {
      final card = await authenticated(newFakeCard());
      expect(await card.getValue(fileNo), 0);
    });

    test('sends fileNo plus an 8-byte CMAC', () async {
      final fake = newFakeCard(balance: 100);
      final card = await authenticated(fake);
      await card.getValue(fileNo);
      final apdu = fake.sentApdus.last;
      expect(apdu[1], DesfireCommand.getValue);
      expect(apdu[4], 9, reason: 'Lc = fileNo + MAC');
      expect(apdu[5], fileNo);
    });

    test('advances the command counter', () async {
      final fake = newFakeCard(balance: 100);
      final card = await authenticated(fake);
      expect(card.session!.cmdCounter, 0);
      await card.getValue(fileNo);
      expect(card.session!.cmdCounter, 1);
      await card.getValue(fileNo);
      expect(card.session!.cmdCounter, 2);
      expect(fake.commandCounter, card.session!.cmdCounter);
    });

    test('rejects a response whose CMAC does not verify', () async {
      final fake = newFakeCard(balance: 100);
      final card = await authenticated(fake);
      fake.corruptResponseMacFor.add(DesfireCommand.getValue);
      await expectLater(
        card.getValue(fileNo),
        throwsA(isA<CmacMismatchException>()),
      );
    });

    test('surfaces a file-not-found status', () async {
      final card = await authenticated(newFakeCard());
      await expectLater(
        card.getValue(0x09),
        throwsA(
          isA<CardStatusException>().having((e) => e.status, 'status', 0xF0),
        ),
      );
    });

    test('requires authentication', () async {
      final card = clientFor(newFakeCard());
      await card.selectApplication(aid);
      await expectLater(
        card.getValue(fileNo),
        throwsA(isA<AuthenticationFailedException>()),
      );
    });
  });

  group('credit / commit semantics', () {
    Future<(FakeDesfireCard, DesfireCard)> session({int balance = 0}) async {
      final fake = newFakeCard(balance: balance);
      final card = clientFor(fake);
      await card.selectApplication(aid);
      await card.authenticateEv2First(2, userKey);
      return (fake, card);
    }

    test('Credit alone does NOT change the balance', () async {
      final (fake, card) = await session(balance: 1000);
      await card.credit(fileNo, 500);

      expect(fake.hasOpenTransaction, isTrue);
      expect(
        fake.committedBalance,
        1000,
        reason: 'Credit only stages a delta; nothing is persisted yet',
      );
    });

    test('CommitTransaction is what makes the credit permanent', () async {
      final (fake, card) = await session(balance: 1000);
      await card.credit(fileNo, 500);
      await card.commitTransaction();

      expect(fake.hasOpenTransaction, isFalse);
      expect(fake.committedBalance, 1500);
    });

    test('losing the field before commit discards the credit', () async {
      final (fake, card) = await session(balance: 1000);
      await card.credit(fileNo, 500);
      fake.removeFromField();

      expect(fake.committedBalance, 1000);
    });

    test('AbortTransaction discards the credit', () async {
      final (fake, card) = await session(balance: 1000);
      await card.credit(fileNo, 500);
      await card.abortTransaction();

      expect(fake.hasOpenTransaction, isFalse);
      expect(fake.committedBalance, 1000);
    });

    test('Debit alone does not change the balance either', () async {
      final (fake, card) = await session(balance: 1000);
      await card.debit(fileNo, 300);
      expect(fake.committedBalance, 1000);
      await card.commitTransaction();
      expect(fake.committedBalance, 700);
    });

    test('several staged deltas commit together', () async {
      final (fake, card) = await session(balance: 1000);
      await card.credit(fileNo, 500);
      await card.credit(fileNo, 250);
      expect(fake.committedBalance, 1000);
      await card.commitTransaction();
      expect(fake.committedBalance, 1750);
    });

    test('Credit sends fileNo, a 16-byte cryptogram and a CMAC', () async {
      final (fake, card) = await session(balance: 0);
      await card.credit(fileNo, 500);
      final apdu = fake.sentApdus.last;
      expect(apdu[1], DesfireCommand.credit);
      expect(apdu[4], 25, reason: 'Lc = 1 + 16 + 8');
      expect(apdu[5], fileNo);
      // Encrypted amount must not appear in the clear anywhere in the APDU.
      expect(
        _containsSubsequence(apdu, encodeValue(500)),
        isFalse,
        reason: 'CommMode.Full must not leak the plaintext amount',
      );
    });

    test('the cryptogram matches the C-derived vector', () async {
      final (fake, card) = await session(balance: 0);
      // Counter is 0 for the getValue, so the credit runs at counter 1 —
      // matching the vector generated from desfire_crypto.c.
      await card.getValue(fileNo);
      await card.credit(fileNo, 500);
      final apdu = fake.sentApdus.last;
      expect(
        Uint8List.sublistView(apdu, 6, 22),
        fromHex('fde1686ac5b681c54c3557113fa88fa6'),
      );
      expect(Uint8List.sublistView(apdu, 22, 30), fromHex('e2a01421d6d0ab83'));
    });

    test('rejects a non-positive credit before touching the card', () async {
      final (fake, card) = await session();
      final before = fake.sentApdus.length;
      await expectLater(
        card.credit(fileNo, 0),
        throwsA(isA<InvalidParameterException>()),
      );
      await expectLater(
        card.credit(fileNo, -100),
        throwsA(isA<InvalidParameterException>()),
      );
      expect(fake.sentApdus.length, before);
    });

    test('a boundary error invalidates the session', () async {
      final fake = newFakeCard(balance: 99000, upperLimit: 100000);
      final card = clientFor(fake);
      await card.selectApplication(aid);
      await card.authenticateEv2First(2, userKey);

      await expectLater(
        card.credit(fileNo, 5000),
        throwsA(
          isA<CardStatusException>().having((e) => e.status, 'status', 0xBE),
        ),
      );
      expect(card.isAuthenticated, isFalse);
    });

    test('a corrupted commit MAC is reported, not silently accepted', () async {
      final (fake, card) = await session(balance: 1000);
      await card.credit(fileNo, 500);
      fake.corruptResponseMacFor.add(DesfireCommand.commitTransaction);
      await expectLater(
        card.commitTransaction(),
        throwsA(isA<CmacMismatchException>()),
      );
    });
  });

  group('topUp (read, credit, commit, verify)', () {
    Future<(FakeDesfireCard, DesfireCard)> session({int balance = 0}) async {
      final fake = newFakeCard(balance: balance);
      final card = clientFor(fake);
      await card.selectApplication(aid);
      await card.authenticateEv2First(2, userKey);
      return (fake, card);
    }

    test('returns before and after balances read from the card', () async {
      final (fake, card) = await session(balance: 1000);
      final result = await card.topUp(fileNo, 500);

      expect(result.previousBalance, 1000);
      expect(result.amount, 500);
      expect(result.newBalance, 1500);
      expect(fake.committedBalance, 1500);
      expect(fake.hasOpenTransaction, isFalse);
    });

    test('the reported new balance is read back after the commit', () async {
      final (fake, card) = await session(balance: 0);
      final result = await card.topUp(fileNo, 2000);
      expect(result.newBalance, fake.committedBalance);
    });

    test(
      'refuses to exceed the upper limit without touching the card',
      () async {
        final (fake, card) = await session(balance: 99000);
        await expectLater(
          card.topUp(fileNo, 5000, upperLimit: 100000),
          throwsA(
            isA<LimitExceededException>()
                .having((e) => e.balance, 'balance', 99000)
                .having((e) => e.requested, 'requested', 5000),
          ),
        );
        expect(fake.committedBalance, 99000);
        expect(fake.hasOpenTransaction, isFalse);
      },
    );

    test('a link failure mid-credit leaves the balance untouched', () async {
      final (fake, card) = await session(balance: 1000);
      fake.forcedStatus[DesfireCommand.credit] = 0x9D;
      await expectLater(
        card.topUp(fileNo, 500),
        throwsA(isA<CardStatusException>()),
      );
      expect(fake.committedBalance, 1000);
    });

    test('consecutive top-ups accumulate', () async {
      final (fake, card) = await session(balance: 0);
      await card.topUp(fileNo, 500);
      await card.topUp(fileNo, 1000);
      await card.topUp(fileNo, 2000);
      expect(fake.committedBalance, 3500);
    });

    test('rejects a non-positive amount', () async {
      final (_, card) = await session(balance: 1000);
      await expectLater(
        card.topUp(fileNo, 0),
        throwsA(isA<InvalidParameterException>()),
      );
    });
  });

  group('spend (read, debit, commit, verify)', () {
    Future<(FakeDesfireCard, DesfireCard)> session({int balance = 0}) async {
      final fake = newFakeCard(balance: balance);
      final card = clientFor(fake);
      await card.selectApplication(aid);
      await card.authenticateEv2First(2, userKey);
      return (fake, card);
    }

    test('debits and commits', () async {
      final (fake, card) = await session(balance: 1000);
      final result = await card.spend(fileNo, 250);
      expect(result.previousBalance, 1000);
      expect(result.amount, -250);
      expect(result.newBalance, 750);
      expect(fake.committedBalance, 750);
    });

    test('refuses to go below the lower limit', () async {
      final (fake, card) = await session(balance: 100);
      await expectLater(
        card.spend(fileNo, 500),
        throwsA(
          isA<InsufficientFundsException>()
              .having((e) => e.balance, 'balance', 100)
              .having((e) => e.requested, 'requested', 500),
        ),
      );
      expect(fake.committedBalance, 100);
    });

    test('spending the entire balance is allowed', () async {
      final (fake, card) = await session(balance: 500);
      final result = await card.spend(fileNo, 500);
      expect(result.newBalance, 0);
      expect(fake.committedBalance, 0);
    });
  });

  group('createValueFile', () {
    test('sends the 17-byte payload plus a CMAC', () async {
      final fake = FakeDesfireCard(
        applicationId: aid,
        keys: {0: Uint8List.fromList(List<int>.filled(16, 0x11))},
        fileNo: fileNo,
      );
      final card = clientFor(fake);
      await card.selectApplication(aid);
      await card.authenticateEv2First(
        0,
        Uint8List.fromList(List<int>.filled(16, 0x11)),
      );

      // The fake card does not implement CreateValueFile, so it answers
      // "INS not supported"; what matters here is the framing we emitted.
      await expectLater(
        card.createValueFile(
          const ValueFileSettings(
            fileNo: fileNo,
            lowerLimit: 0,
            upperLimit: 100000,
            initialValue: 0,
          ),
        ),
        throwsA(isA<CardStatusException>()),
      );

      final apdu = fake.sentApdus.last;
      expect(apdu[1], DesfireCommand.createValueFile);
      expect(apdu[4], 25, reason: 'Lc = 17 settings bytes + 8 MAC bytes');
      expect(apdu[5], fileNo);
      expect(apdu[6], CommMode.full);
    });
  });

  group('transport failures', () {
    test('a dead link surfaces as TransceiveException', () async {
      final card = DesfireCard(DeadTransceiver());
      await expectLater(
        card.selectApplication(aid),
        throwsA(isA<TransceiveException>()),
      );
    });
  });
}

bool _containsSubsequence(Uint8List haystack, Uint8List needle) {
  if (needle.isEmpty || needle.length > haystack.length) return false;
  for (var i = 0; i <= haystack.length - needle.length; i++) {
    var matched = true;
    for (var j = 0; j < needle.length; j++) {
      if (haystack[i + j] != needle[j]) {
        matched = false;
        break;
      }
    }
    if (matched) return true;
  }
  return false;
}
