/// Large, legible money readouts.
library;

import 'package:flutter/material.dart';

import '../../desfire/value_file.dart';
import '../theme.dart';

/// Renders an amount in cents as a big euro figure.
class MoneyText extends StatelessWidget {
  const MoneyText(
    this.cents, {
    super.key,
    this.size = 64,
    this.color,
    this.showSign = false,
  });

  /// Amount in cents.
  final int cents;

  /// Font size of the major digits.
  final double size;

  /// Text colour; defaults to the theme's on-surface colour.
  final Color? color;

  /// When true, prefixes a `+` for positive amounts.
  final bool showSign;

  @override
  Widget build(BuildContext context) {
    final resolved = color ?? KioskColors.onSurface;
    final sign = showSign && cents > 0 ? '+' : '';
    final formatted = formatCents(cents);
    final parts = formatted.split('.');

    return FittedBox(
      fit: BoxFit.scaleDown,
      child: RichText(
        text: TextSpan(
          style: TextStyle(
            color: resolved,
            fontWeight: FontWeight.w700,
            height: 1.0,
            fontFeatures: const [FontFeature.tabularFigures()],
          ),
          children: [
            TextSpan(
              text: '€',
              style: TextStyle(
                fontSize: size * 0.52,
                fontWeight: FontWeight.w600,
                color: resolved.withValues(alpha: 0.65),
              ),
            ),
            TextSpan(
              text: ' $sign${parts[0]}',
              style: TextStyle(fontSize: size),
            ),
            TextSpan(
              text: '.${parts.length > 1 ? parts[1] : '00'}',
              style: TextStyle(
                fontSize: size * 0.52,
                color: resolved.withValues(alpha: 0.75),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The home screen's balance card.
class BalanceCard extends StatelessWidget {
  const BalanceCard({
    super.key,
    required this.balanceCents,
    this.uidHex,
    this.product,
    this.stale = false,
  });

  /// Balance in cents, or null when no card has been read yet.
  final int? balanceCents;

  /// Card UID as hex, when known.
  final String? uidHex;

  /// Card product name, when known.
  final String? product;

  /// Dims the figure while a refresh is in flight.
  final bool stale;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final hasBalance = balanceCents != null;

    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(28, 30, 28, 28),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Text(
                  'CARD BALANCE',
                  style: theme.textTheme.labelMedium?.copyWith(
                    letterSpacing: 1.6,
                    fontWeight: FontWeight.w700,
                    color: KioskColors.onSurfaceMuted,
                  ),
                ),
                const Spacer(),
                if (product != null)
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 5,
                    ),
                    decoration: BoxDecoration(
                      color: KioskColors.secondary.withValues(alpha: 0.12),
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: Text(
                      product!,
                      style: const TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.w700,
                        color: KioskColors.secondary,
                      ),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 18),
            AnimatedOpacity(
              opacity: stale ? 0.35 : 1,
              duration: const Duration(milliseconds: 200),
              child: hasBalance
                  ? MoneyText(balanceCents!, size: 68)
                  : Text(
                      '— . —',
                      style: theme.textTheme.displayMedium?.copyWith(
                        fontWeight: FontWeight.w700,
                        color: KioskColors.onSurfaceMuted,
                      ),
                    ),
            ),
            const SizedBox(height: 14),
            Text(
              hasBalance
                  ? (uidHex == null ? 'Read over NFC' : 'UID $uidHex')
                  : 'Tap your card to read the balance',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: KioskColors.onSurfaceMuted,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
