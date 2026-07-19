import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_topup/desfire/desfire_crypto.dart';
import 'package:flutter_topup/desfire/desfire_exceptions.dart';
import 'package:flutter_topup/desfire/value_file.dart';

void main() {
  group('value encoding (4-byte signed little-endian)', () {
    test('encodes small positive values', () {
      expect(encodeValue(0), fromHex('00000000'));
      expect(encodeValue(1), fromHex('01000000'));
      expect(encodeValue(500), fromHex('f4010000'));
      expect(encodeValue(1000), fromHex('e8030000'));
      expect(encodeValue(2000), fromHex('d0070000'));
    });

    test('encodes values that span multiple bytes', () {
      expect(encodeValue(100000), fromHex('a0860100'));
      expect(encodeValue(0x01020304), fromHex('04030201'));
    });

    test('encodes negative values in two-complement form', () {
      expect(encodeValue(-1), fromHex('ffffffff'));
      expect(encodeValue(-500), fromHex('0cfeffff'));
    });

    test('encodes the extremes of the 32-bit range', () {
      expect(encodeValue(kValueFileMax), fromHex('ffffff7f'));
      expect(encodeValue(kValueFileMin), fromHex('00000080'));
    });

    test('rejects values outside the 32-bit range', () {
      expect(
        () => encodeValue(kValueFileMax + 1),
        throwsA(isA<InvalidParameterException>()),
      );
      expect(
        () => encodeValue(kValueFileMin - 1),
        throwsA(isA<InvalidParameterException>()),
      );
    });
  });

  group('value decoding', () {
    test('decodes what encode produced', () {
      for (final value in [
        0,
        1,
        -1,
        500,
        1000,
        100000,
        -250000,
        kValueFileMax,
        kValueFileMin,
      ]) {
        expect(decodeValue(encodeValue(value)), value, reason: '$value');
      }
    });

    test('reads from an offset inside a larger buffer', () {
      final buffer = Uint8List.fromList([0xAA, 0xBB, ...encodeValue(1234)]);
      expect(decodeValue(buffer, 2), 1234);
    });

    test('decodes a padded GetValue block, ignoring the padding', () {
      // What the card returns for CommMode.Full: value + ISO 7816-4 padding.
      final block = padIso7816(encodeValue(4200));
      expect(block, hasLength(16));
      expect(decodeValue(block), 4200);
    });

    test('throws when there are fewer than 4 bytes', () {
      expect(
        () => decodeValue(fromHex('010203')),
        throwsA(isA<ResponseTooShortException>()),
      );
    });
  });

  group('AccessRights', () {
    test('singleKey packs the same key into every nibble', () {
      expect(const AccessRights.singleKey(2).toWord(), 0x2222);
    });

    test('packs each role into its own nibble', () {
      const rights = AccessRights(
        read: 0x2,
        write: 0x2,
        readWrite: 0x2,
        changeAccessRights: 0x0,
      );
      // Matches DESFIRE_ACCESS_RIGHTS = 0x2220 from desfiire.c.
      expect(rights.toWord(), 0x2220);
    });

    test('serialises little-endian, matching df_create_std_data_file', () {
      const rights = AccessRights(
        read: 0x2,
        write: 0x2,
        readWrite: 0x2,
        changeAccessRights: 0x0,
      );
      expect(rights.toBytes(), fromHex('2022'));
    });

    test('round-trips through fromWord', () {
      const original = AccessRights(
        read: 1,
        write: 2,
        readWrite: 3,
        changeAccessRights: AccessRights.freeAccess,
      );
      final decoded = AccessRights.fromWord(original.toWord());
      expect(decoded.read, 1);
      expect(decoded.write, 2);
      expect(decoded.readWrite, 3);
      expect(decoded.changeAccessRights, AccessRights.freeAccess);
    });
  });

  group('ValueFileSettings (CreateValueFile payload)', () {
    test('serialises the documented 17-byte layout', () {
      const settings = ValueFileSettings(
        fileNo: 0x01,
        commMode: CommMode.full,
        accessRights: AccessRights.singleKey(2),
        lowerLimit: 0,
        upperLimit: 100000,
        initialValue: 0,
        limitedCreditEnabled: false,
      );

      final bytes = settings.toBytes();
      expect(bytes, hasLength(17));
      expect(bytes[0], 0x01, reason: 'fileNo');
      expect(bytes[1], 0x03, reason: 'commMode Full');
      expect(bytes.sublist(2, 4), fromHex('2222'), reason: 'accessRights LE');
      expect(bytes.sublist(4, 8), fromHex('00000000'), reason: 'lowerLimit');
      expect(bytes.sublist(8, 12), fromHex('a0860100'), reason: 'upperLimit');
      expect(bytes.sublist(12, 16), fromHex('00000000'), reason: 'initial');
      expect(bytes[16], 0x00, reason: 'limitedCreditEnabled');
    });

    test('sets the limited-credit flag when enabled', () {
      const settings = ValueFileSettings(
        fileNo: 0x01,
        lowerLimit: 0,
        upperLimit: 1000,
        initialValue: 250,
        limitedCreditEnabled: true,
      );
      final bytes = settings.toBytes();
      expect(bytes[16], 0x01);
      expect(bytes.sublist(12, 16), fromHex('fa000000'));
    });

    test('rejects a lower limit above the upper limit', () {
      const settings = ValueFileSettings(
        fileNo: 1,
        lowerLimit: 100,
        upperLimit: 50,
        initialValue: 0,
      );
      expect(settings.toBytes, throwsA(isA<InvalidParameterException>()));
    });
  });

  group('formatCents', () {
    test('formats whole and fractional euros', () {
      expect(formatCents(0), '0.00');
      expect(formatCents(5), '0.05');
      expect(formatCents(50), '0.50');
      expect(formatCents(500), '5.00');
      expect(formatCents(1234), '12.34');
      expect(formatCents(100000), '1000.00');
    });

    test('formats negative amounts', () {
      expect(formatCents(-250), '-2.50');
    });
  });

  group('parseEurosToCents', () {
    test('parses the accepted forms', () {
      expect(parseEurosToCents('5'), 500);
      expect(parseEurosToCents('5.5'), 550);
      expect(parseEurosToCents('5.50'), 550);
      expect(parseEurosToCents('5,50'), 550);
      expect(parseEurosToCents('  7.25 '), 725);
      expect(parseEurosToCents('0'), 0);
      expect(parseEurosToCents('0.01'), 1);
    });

    test('rejects malformed input', () {
      expect(parseEurosToCents(''), isNull);
      expect(parseEurosToCents('   '), isNull);
      expect(parseEurosToCents('abc'), isNull);
      expect(parseEurosToCents('5.'), isNull);
      expect(parseEurosToCents('5.123'), isNull);
      expect(parseEurosToCents('-5'), isNull);
      expect(parseEurosToCents('5.5.5'), isNull);
    });

    test('round-trips against formatCents', () {
      for (final cents in [0, 1, 99, 500, 1050, 99999]) {
        expect(parseEurosToCents(formatCents(cents)), cents);
      }
    });
  });

  group('status decoding', () {
    test('names the statuses the value-file path can hit', () {
      expect(describeDesfireStatus(0x00), 'OK');
      expect(describeDesfireStatus(0xBE), contains('Boundary'));
      expect(describeDesfireStatus(0x9D), contains('Permission denied'));
      expect(describeDesfireStatus(0xAE), contains('Authentication'));
      expect(describeDesfireStatus(0xF0), contains('file not found'));
      expect(describeDesfireStatus(0xDE), contains('Duplicate'));
      expect(describeDesfireStatus(0x55), 'Unknown card status');
    });

    test('CardStatusException renders the status in hex', () {
      final exception = CardStatusException(0xBE, command: 0x0C);
      expect(exception.statusHex, '0xBE');
      expect(exception.toString(), contains('0xBE'));
      expect(exception.toString(), contains('0x0C'));
    });

    test('InsufficientFundsException carries the amounts', () {
      const exception = InsufficientFundsException(
        balance: 100,
        requested: 500,
      );
      expect(exception.toString(), contains('balance=100'));
      expect(exception.toString(), contains('requested=500'));
    });
  });
}
