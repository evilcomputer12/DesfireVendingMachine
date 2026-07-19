import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_topup/desfire/desfire_crypto.dart';
import 'package:flutter_topup/desfire/desfire_legacy_crypto.dart';

/// Known-answer tests for the legacy DES / 2K3DES / 3K3DES layer.
///
/// Every expected value below was produced by **compiling and running the
/// project's own C implementation** (`plainc-desfire-rc522-main/lib/desfire/
/// desfire_crypto.c`) against the same inputs, not by asserting that the Dart
/// agrees with itself. The generator is reproduced in the comment at the
/// bottom of this file so the vectors can be regenerated.
///
/// This matters more here than anywhere else in the codebase: the legacy
/// handshake is the only thing standing between a blank card and a provisioned
/// one, there is no second chance to get it right on a card in the field, and
/// the failure mode (a card that rejects step 2 with 0xAE) gives no clue as to
/// which of the several plausible constructions was wrong.
void main() {
  // Inputs shared with the C generator.
  final key16 = fromHex('00112233445566778899aabbccddeeff');
  final key24 = fromHex('000102030405060708090a0b0c0d0e0f1011121314151617');
  final key8 = fromHex('0123456789abcdef');
  final iv8 = fromHex('0102030405060708');
  final zeroIv = Uint8List(8);
  final data16 = fromHex('000102030405060708090a0b0c0d0e0f');
  final zeroKey16 = Uint8List(16);

  group('2K3DES (df_3des_*)', () {
    test('df_3des_cbc_encrypt', () {
      expect(
        des3CbcEncrypt(key16, iv8, data16),
        fromHex('7ee61fabe84affbea4edcea547497723'),
      );
    });

    test('df_3des_cbc_decrypt', () {
      expect(
        des3CbcDecrypt(key16, iv8, data16),
        fromHex('74cad49a33968df99c815f0c3deac46d'),
      );
    });

    test('df_3des_cbc_encrypt with a zero IV', () {
      expect(
        des3CbcEncrypt(key16, zeroIv, data16),
        fromHex('5d990787b0673787ee2e5795fd340723'),
      );
    });

    test('df_3des_ecb_decrypt', () {
      expect(
        des3EcbDecrypt(key16, data16),
        fromHex('75c8d79e36908af19c805d0f39efc26a'),
      );
    });

    test('encrypt and decrypt round-trip', () {
      expect(
        des3CbcDecrypt(key16, iv8, des3CbcEncrypt(key16, iv8, data16)),
        data16,
      );
    });

    test('the 16-byte key is expanded to K1|K2|K1', () {
      expect(
        expand2k3desKey(key16),
        fromHex('00112233445566778899aabbccddeeff0011223344556677'),
      );
    });
  });

  group('3K3DES (df_3k3des_*)', () {
    test('df_3k3des_cbc_encrypt', () {
      expect(
        des3k3CbcEncrypt(key24, iv8, data16),
        fromHex('72e80b3cae2f3551ea4e283863960b3a'),
      );
    });

    test('df_3k3des_cbc_decrypt', () {
      expect(
        des3k3CbcDecrypt(key24, iv8, data16),
        fromHex('c14f3390d7e0f2bfc6ff9ec79dd2fe19'),
      );
    });

    test('rejects a key that is not 24 bytes', () {
      expect(
        () => des3k3CbcEncrypt(key16, iv8, data16),
        throwsA(isA<ArgumentError>()),
      );
    });
  });

  group('single DES (df_des_*)', () {
    test('df_des_cbc_encrypt', () {
      expect(
        desCbcEncrypt(key8, iv8, data16),
        fromHex('0cb96542d0cc907aaaa22ddbbc9295e9'),
      );
    });

    test('df_des_cbc_decrypt', () {
      expect(
        desCbcDecrypt(key8, iv8, data16),
        fromHex('71f137560c7b024e87e1e6b454d650c3'),
      );
    });

    test('doubling the key makes 2K3DES degenerate to single DES', () {
      // This is what lets a DESedeEngine stand in for the single-DES engine
      // pointycastle does not ship: E_K1(D_K1(E_K1(x))) == E_K1(x).
      final doubled = expandDesKey(key8);
      expect(
        des3CbcEncrypt(doubled, iv8, data16),
        desCbcEncrypt(key8, iv8, data16),
      );
      expect(
        des3CbcEncrypt(doubled, iv8, data16),
        fromHex('0cb96542d0cc907aaaa22ddbbc9295e9'),
      );
    });
  });

  group('df_crc32', () {
    test('the standard check value, without the final complement', () {
      // CRC-32/ISO-HDLC of "123456789" is 0xCBF43926; df_crc32 omits the
      // trailing XOR, so it yields the ones' complement of that.
      expect(desfireCrc32(fromHex('313233343536373839')), 0x340BC6D9);
    });

    test('over 16 zero bytes', () {
      expect(desfireCrc32(Uint8List(16)), 0x1344B4AA);
    });

    test('over the bench application keys', () {
      expect(
        desfireCrc32(Uint8List.fromList(List<int>.filled(16, 0x11))),
        0x9736C8A7,
      );
      expect(
        desfireCrc32(Uint8List.fromList(List<int>.filled(16, 0x22))),
        0xC0D14AF1,
      );
    });

    test('is serialised little-endian, as df_change_key writes it', () {
      expect(crc32ToBytes(0xC0D14AF1), fromHex('f14ad1c0'));
    });
  });

  group('legacy 0x0A handshake construction', () {
    // Card challenge and nonces shared with the C generator.
    final rndB = fromHex('a0a1a2a3a4a5a6a7');
    final rndA = fromHex('0102030405060708');
    final tokenPlain = Uint8List(16)
      ..setRange(0, 8, rndA)
      ..setRange(8, 16, rotateLeft1(rndB));

    test('the token plaintext is RndA || RndB rotated left one byte', () {
      expect(tokenPlain, fromHex('0102030405060708a1a2a3a4a5a6a7a0'));
    });

    group('factory key (16 zero bytes)', () {
      final encRndB = des3CbcEncrypt(zeroKey16, zeroIv, rndB);

      test('the card challenge matches the C', () {
        expect(encRndB, fromHex('05eec31f1e6a0cc3'));
      });

      test('step 1 recovers RndB', () {
        expect(desfireLegacyCbcReceive(zeroKey16, zeroIv, encRndB), rndB);
      });

      test('the D40 token matches the C', () {
        expect(
          desfireLegacyCbcSend(zeroKey16, encRndB, tokenPlain),
          fromHex('d1bbc36c61bb2f50a0c7f19fec28d493'),
        );
      });

      test(
        'the two constructions agree, because an all-zero DES key is weak',
        () {
          // Every round subkey of an all-zero DES key is identical, which makes
          // encryption and decryption the same permutation. This is the reason
          // df_authenticate_legacy works against real blank cards despite
          // building its token with the ISO construction rather than the D40
          // one — and the reason getting the direction wrong would not show up
          // until somebody used a card with a non-default legacy key.
          expect(
            desfireLegacyCbcSend(zeroKey16, encRndB, tokenPlain),
            des3CbcEncrypt(zeroKey16, encRndB, tokenPlain),
          );
          expect(
            des3EcbEncrypt(zeroKey16, rndB),
            des3EcbDecrypt(zeroKey16, rndB),
          );
        },
      );
    });

    group('non-default legacy key', () {
      final encRndB = des3CbcEncrypt(key16, zeroIv, rndB);

      test('the card challenge matches the C', () {
        expect(encRndB, fromHex('1c2ba85fd190823f'));
      });

      test('the D40 token matches the C', () {
        expect(
          desfireLegacyCbcSend(key16, encRndB, tokenPlain),
          fromHex('d1f133d2d39b97746d5f2d2a4f3a0283'),
        );
      });

      test('the df_authenticate_legacy token matches the C', () {
        expect(
          des3CbcEncrypt(key16, encRndB, tokenPlain),
          fromHex('c40cf4aa73e33f1d05fdeaf2cfdd39cf'),
        );
      });

      test('and here the two constructions genuinely differ', () {
        expect(
          desfireLegacyCbcSend(key16, encRndB, tokenPlain),
          isNot(des3CbcEncrypt(key16, encRndB, tokenPlain)),
        );
      });
    });
  });

  group('ISO 0x1A handshake construction', () {
    test('matches the C for the factory 3K3DES key', () {
      final key24Zero = Uint8List(24);
      final rndB = fromHex('b0b1b2b3b4b5b6b7');
      final rndA = fromHex('1112131415161718');
      final encRndB = des3k3CbcEncrypt(key24Zero, zeroIv, rndB);
      expect(encRndB, fromHex('fb7fbec17683e2b3'));

      final plain = Uint8List(16)
        ..setRange(0, 8, rndA)
        ..setRange(8, 16, rotateLeft1(rndB));
      expect(
        des3k3CbcEncrypt(key24Zero, encRndB, plain),
        fromHex('4ddb710169b789ee0bee8ca9bc9457f7'),
      );
    });
  });

  group('input validation', () {
    test('rejects payloads that are not a multiple of 8 bytes', () {
      expect(
        () => des3CbcEncrypt(key16, iv8, Uint8List(7)),
        throwsA(isA<ArgumentError>()),
      );
    });

    test('rejects an IV that is not 8 bytes', () {
      expect(
        () => des3CbcEncrypt(key16, Uint8List(16), data16),
        throwsA(isA<ArgumentError>()),
      );
    });
  });
}

// ---------------------------------------------------------------------------
// Regenerating these vectors
// ---------------------------------------------------------------------------
//
//   cc -O2 -I<repo>/plainc-desfire-rc522-main/lib/desfire \
//      gen_vectors.c <repo>/plainc-desfire-rc522-main/lib/desfire/desfire_crypto.c \
//      -o gen_vectors && ./gen_vectors
//
// where gen_vectors.c calls df_3des_cbc_encrypt / df_3des_cbc_decrypt /
// df_3des_ecb_decrypt / df_3k3des_cbc_encrypt / df_3k3des_cbc_decrypt /
// df_des_cbc_encrypt / df_des_cbc_decrypt / df_crc32 with the inputs at the
// top of this file, and builds the D40 token as
//
//   for each 8-byte block: block ^= chain; df_3des_ecb_decrypt(key, block);
//                          chain = result;
//
// with chain starting at the card's encRndB challenge.
