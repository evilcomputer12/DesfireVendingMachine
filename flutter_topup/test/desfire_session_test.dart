import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_topup/desfire/desfire_crypto.dart';
import 'package:flutter_topup/desfire/desfire_session.dart';

/// Session-layer known-answer tests.
///
/// The expected MACs were produced by compiling the project's C library and
/// running `_calc_cmd_mac` / `_verify_resp_mac` with the same inputs, so these
/// assert byte-identical behaviour with the reference implementation.
void main() {
  // Session material derived (in C) from key = 16 x 0x22,
  // rndA = 10 11 .. 1F, rndB = A0 A1 .. AF.
  final sessKeyEnc = fromHex('1130b90c1aac6ef7528d9088d24d67d3');
  final sessKeyMac = fromHex('569031dca11d038e1b12e50092c9e030');
  final ti = fromHex('deadbeef');

  DesfireSession newSession({int counter = 0}) => DesfireSession(
    sessKeyEnc: sessKeyEnc,
    sessKeyMac: sessKeyMac,
    ti: ti,
    authKeyNo: 2,
    cmdCounter: counter,
  );

  group('construction', () {
    test('starts active with counter 0', () {
      final s = newSession();
      expect(s.isActive, isTrue);
      expect(s.cmdCounter, 0);
      expect(s.authKeyNo, 2);
    });

    test('copies the key material so callers cannot mutate it', () {
      final mutable = Uint8List.fromList(sessKeyMac);
      final s = DesfireSession(
        sessKeyEnc: sessKeyEnc,
        sessKeyMac: mutable,
        ti: ti,
        authKeyNo: 0,
      );
      mutable[0] ^= 0xFF;
      expect(s.sessKeyMac, sessKeyMac);
    });

    test('rejects a TI that is not 4 bytes', () {
      expect(
        () => DesfireSession(
          sessKeyEnc: sessKeyEnc,
          sessKeyMac: sessKeyMac,
          ti: Uint8List(8),
          authKeyNo: 0,
        ),
        throwsA(isA<ArgumentError>()),
      );
    });

    test('invalidate marks the session dead', () {
      final s = newSession()..invalidate();
      expect(s.isActive, isFalse);
    });
  });

  group('command MAC (matches _calc_cmd_mac)', () {
    test('GetValue(fileNo=1) at counter 0', () {
      final s = newSession();
      // C: df_cmac over [6C 00 00 DE AD BE EF 01] then df_truncate_mac.
      expect(
        s.commandMac(0x6C, Uint8List.fromList([0x01])),
        fromHex('72f979e18fe55d79'),
      );
    });

    test('Credit(fileNo=1, ciphertext) at counter 1', () {
      final s = newSession(counter: 1);
      final ciphertext = fromHex('fde1686ac5b681c54c3557113fa88fa6');
      final macInput = Uint8List.fromList([0x01, ...ciphertext]);
      expect(s.commandMac(0x0C, macInput), fromHex('e2a01421d6d0ab83'));
    });

    test('MAC input covers command, counter and TI even with no data', () {
      final s = newSession();
      final withoutData = s.commandMac(0xC7);
      final withEmptyData = s.commandMac(0xC7, Uint8List(0));
      expect(withoutData, withEmptyData);
      expect(withoutData, hasLength(8));
    });

    test('the same command at a different counter MACs differently', () {
      final a = newSession(
        counter: 0,
      ).commandMac(0x6C, Uint8List.fromList([1]));
      final b = newSession(
        counter: 1,
      ).commandMac(0x6C, Uint8List.fromList([1]));
      expect(a, isNot(b));
    });

    test('a different command byte MACs differently', () {
      final s = newSession();
      expect(
        s.commandMac(0x0C, Uint8List.fromList([1])),
        isNot(s.commandMac(0xDC, Uint8List.fromList([1]))),
      );
    });
  });

  group('response MAC (matches _verify_resp_mac)', () {
    test('empty response at counter 1', () {
      final s = newSession(counter: 1);
      // C: df_cmac over [00 01 00 DE AD BE EF] then truncate.
      expect(s.responseMac(null), fromHex('48a8dbcef2f0b8d0'));
    });

    test('verifyResponseMac accepts the matching MAC', () {
      final s = newSession(counter: 1);
      expect(s.verifyResponseMac(null, fromHex('48a8dbcef2f0b8d0')), isTrue);
    });

    test('verifyResponseMac rejects a tampered MAC', () {
      final s = newSession(counter: 1);
      expect(s.verifyResponseMac(null, fromHex('48a8dbcef2f0b8d1')), isFalse);
    });

    test(
      'response MAC differs from any real command MAC at the same counter',
      () {
        final s = newSession(counter: 1);
        for (final command in [0x6C, 0x0C, 0xDC, 0x1C, 0xC7, 0xA7]) {
          expect(
            s.responseMac(null),
            isNot(s.commandMac(command)),
            reason: 'command 0x${command.toRadixString(16)}',
          );
        }
      },
    );

    test(
      'the response MAC is structurally a command MAC with command byte 0x00',
      () {
        // Both build [rc/cmd][ctrLo][ctrHi][TI][data]. The response MAC uses
        // return code 0x00 in the first byte, so it collides with a command
        // MAC for command byte 0x00. That is harmless because 0x00 is not a
        // DESFire command, but it is worth pinning down: it means the domain
        // separation between the two directions comes entirely from the fact
        // that no real command uses 0x00.
        final s = newSession(counter: 1);
        expect(s.responseMac(null), s.commandMac(0x00));
      },
    );
  });

  group('command counter', () {
    test('incrementCounter advances by one', () {
      final s = newSession();
      s.incrementCounter();
      expect(s.cmdCounter, 1);
      s.incrementCounter();
      expect(s.cmdCounter, 2);
    });

    test('wraps at 16 bits, as the two counter bytes require', () {
      final s = newSession(counter: 0xFFFF);
      s.incrementCounter();
      expect(s.cmdCounter, 0);
    });

    test('IVs track the counter', () {
      final s = newSession();
      expect(s.commandIv, fromHex('d2cbbe3ac67413450a0002aac5eb6f7e'));
      s.incrementCounter();
      expect(s.commandIv, fromHex('62401812ce134067317a51e9e53cab26'));
      expect(s.responseIv, fromHex('1853b1b241df7dc4191451b7f9febfff'));
    });
  });

  group('CommMode.Full payload handling', () {
    test('encryptCommandData pads then CBC-encrypts under the command IV', () {
      final s = newSession(counter: 1);
      // 4-byte value 500 -> padded -> AES-CBC with ivCmd[1].
      expect(
        s.encryptCommandData(fromHex('f4010000')),
        fromHex('fde1686ac5b681c54c3557113fa88fa6'),
      );
    });

    test('decryptResponseData inverts an encryption done with the resp IV', () {
      final s = newSession(counter: 3);
      final plain = padIso7816(fromHex('e8030000'));
      final cipher = aesCbcEncrypt(sessKeyEnc, s.responseIv, plain);
      expect(s.decryptResponseData(cipher), plain);
    });
  });
}
